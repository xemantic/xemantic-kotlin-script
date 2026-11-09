/*
 * xemantic-kotlin-script - Kotlin script execution with LLM-friendly diagnostics
 * Copyright (C) 2026  Kazimierz Pogoda / Xemantic
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */


package com.xemantic.kotlin.script

import org.apache.maven.repository.internal.MavenRepositorySystemUtils
import org.apache.maven.settings.Settings
import org.eclipse.aether.RepositorySystem
import org.eclipse.aether.RepositorySystemSession
import org.eclipse.aether.artifact.DefaultArtifact
import org.eclipse.aether.connector.basic.BasicRepositoryConnectorFactory
import org.eclipse.aether.impl.DefaultServiceLocator
import org.eclipse.aether.repository.LocalRepository
import org.eclipse.aether.repository.Proxy
import org.eclipse.aether.repository.RemoteRepository
import org.eclipse.aether.resolution.VersionRangeRequest
import org.eclipse.aether.spi.connector.RepositoryConnectorFactory
import org.eclipse.aether.spi.connector.transport.TransporterFactory
import org.eclipse.aether.transfer.MetadataNotFoundException
import org.eclipse.aether.transport.file.FileTransporterFactory
import org.eclipse.aether.transport.http.HttpTransporterFactory
import org.eclipse.aether.util.repository.AuthenticationBuilder
import org.eclipse.aether.util.repository.DefaultAuthenticationSelector
import org.eclipse.aether.util.repository.DefaultMirrorSelector
import org.eclipse.aether.util.repository.DefaultProxySelector
import org.eclipse.aether.util.version.GenericVersionScheme
import org.eclipse.aether.version.VersionScheme
import java.io.File
import kotlin.script.experimental.dependencies.ExternalDependenciesResolver
import kotlin.script.experimental.dependencies.RepositoryCoordinates
import kotlin.script.experimental.dependencies.impl.mavenRepoId
import kotlin.script.experimental.dependencies.impl.password
import kotlin.script.experimental.dependencies.impl.privateKeyFile
import kotlin.script.experimental.dependencies.impl.privateKeyPassphrase
import kotlin.script.experimental.dependencies.impl.toRepositoryUrlOrNull
import kotlin.script.experimental.dependencies.impl.username
import kotlin.script.experimental.dependencies.maven.impl.createMavenSettings

/**
 * The latest [version] of a Maven artifact, which is [stable],
 * or a pre-release one, if the artifact has no stable version, like `pip install` does.
 */
internal class LatestMavenVersion(val version: String, val stable: Boolean)

/**
 * A token of a pre-release version, like the `RC` of `2.0-RC1`, or the `Beta` of `1.9.20-Beta2`,
 * with the version split by its separators, and between its digits and letters, like Maven does.
 */
private val preReleaseToken = Regex(
    "alpha|beta|a|b|milestone|m|rc|cr|preview|pre|eap|ea|dev|nightly|snapshot",
    RegexOption.IGNORE_CASE
)

private val versionTokenSeparator = Regex("""[.\-_+]|(?<=\d)(?=\p{L})|(?<=\p{L})(?=\d)""")

private val versionScheme: VersionScheme = GenericVersionScheme()

/** The numeric release of a version, like the `0.8.0` of `0.8.0-0.6.x-compat`. */
private val numericRelease = Regex("""^\d+(\.\d+)*""")

/** A version, which major component is a date, like the `20040616` of commons-collections, or its `20031027.000000`. */
private val dateStyleVersion = Regex("""^(19|20)\d{6}(\D.*)?$""")

private fun String.isPreRelease() = split(versionTokenSeparator).any { preReleaseToken.matches(it) }

/**
 * The latest of the [versions] in the Maven ordering, preferring the stable ones,
 * never a `SNAPSHOT`, or `null`, if there is none.
 * A date-style version, like the `20040616` of commons-collections, which Maven orders after any release,
 * gives way to any other stable version, if there is one.
 * A stable version with a qualifier, which Maven orders after its release, like `0.8.0-0.6.x-compat` after `0.8.0`,
 * gives way to its release, if there is one.
 */
internal fun latestMavenVersion(
    versions: Collection<String>
): LatestMavenVersion? {
    val candidates = versions.filterNot { it.endsWith("-SNAPSHOT", ignoreCase = true) }
    val (preReleases, allStable) = candidates.partition { it.isPreRelease() }
    val stable = allStable.filterNot { dateStyleVersion.matches(it) }.ifEmpty { allStable }
    return stable.maxByOrNull { versionScheme.parseVersion(it) }?.let { highest ->
        val release = numericRelease.find(highest)?.value
        LatestMavenVersion(stable.firstOrNull { it == release } ?: highest, stable = true)
    }
        ?: preReleases.maxByOrNull { versionScheme.parseVersion(it) }?.let { LatestMavenVersion(it, stable = false) }
}

/**
 * Looks up the latest version of a Maven artifact with Maven Resolver,
 * set up like `MavenDependenciesResolver` resolves the artifact in the version found:
 * with the local repository, the mirrors, the proxies and the server credentials of the Maven [settings],
 * and also in their offline mode, which `MavenDependenciesResolver` does not honor.
 */
internal class MavenVersionLookup(
    settingsFactory: () -> Settings = ::createMavenSettings
) {

    private val settings by lazy(settingsFactory)

    private val system: RepositorySystem by lazy {
        @Suppress("DEPRECATION")
        MavenRepositorySystemUtils.newServiceLocator().apply {
            addService(RepositoryConnectorFactory::class.java, BasicRepositoryConnectorFactory::class.java)
            addService(TransporterFactory::class.java, HttpTransporterFactory::class.java)
            addService(TransporterFactory::class.java, FileTransporterFactory::class.java)
        }.repositorySystem()
    }

    /** The active proxy of the Maven settings, if any. */
    private val proxySelector by lazy {
        DefaultProxySelector().apply {
            settings.activeProxy?.let { proxy ->
                add(
                    Proxy(
                        proxy.protocol,
                        proxy.host,
                        proxy.port,
                        AuthenticationBuilder().addUsername(proxy.username).addPassword(proxy.password).build()
                    ),
                    proxy.nonProxyHosts
                )
            }
        }
    }

    /** The mirrors of the Maven settings. */
    private val mirrorSelector by lazy {
        DefaultMirrorSelector().apply {
            settings.mirrors.orEmpty().forEach {
                add(it.id, it.url, it.layout, false, false, it.mirrorOf, it.mirrorOfLayouts)
            }
        }
    }

    /** The server credentials of the Maven settings, by the server id. */
    private val authenticationSelector by lazy {
        DefaultAuthenticationSelector().apply {
            settings.servers.orEmpty().filter { it.id != null }.forEach {
                add(
                    it.id,
                    AuthenticationBuilder()
                        .addUsername(it.username)
                        .addPassword(it.password)
                        .addPrivateKey(it.privateKey, it.passphrase)
                        .build()
                )
            }
        }
    }

    /** Whether the Maven settings are in the offline mode. */
    val offline: Boolean get() = settings.isOffline

    /**
     * A new session, for each lookup, like `MavenDependenciesResolver` does for each resolution,
     * as a session remembers a failure, or a missing `maven-metadata.xml`, not to look it up again.
     */
    private fun newSession(): RepositorySystemSession = MavenRepositorySystemUtils.newSession().apply {
        val localRepository = LocalRepository(File(settings.localRepository))
        localRepositoryManager = system.newLocalRepositoryManager(this, localRepository)
        isOffline = settings.isOffline
        // in the offline mode, the file repositories are still read, like by Maven itself
        setConfigProperty("aether.offline.protocols", "file")
        // the timeouts of a lookup before Maven Resolver, unlike its defaults, 10 s to connect, and 30 min to wait for data
        setConfigProperty("aether.connector.connectTimeout", 10_000)
        setConfigProperty("aether.connector.requestTimeout", 30_000)
    }

    /**
     * The latest version of the artifact of the [groupId] and [artifactId],
     * among the versions listed by the `maven-metadata.xml` of any of the [repositories],
     * as cached in the local repository, refreshed once a day, or installed into it,
     * or, in the offline mode, if none lists any, among the versions present in the local repository,
     * or `null`, if there is none, see [latestMavenVersion].
     *
     * @throws Exception if there is no version, and some repository cannot be read.
     */
    fun latestVersion(
        groupId: String,
        artifactId: String,
        repositories: List<RemoteRepository>
    ): LatestMavenVersion? {
        val request = VersionRangeRequest(
            // not [0,), which excludes the versions ordered below 0, like r09, v1.2 or M1
            DefaultArtifact(groupId, artifactId, "jar", "(,)"),
            repositories.mapNotNull { it.withSettings() },
            null
        )
        val result = system.resolveVersionRange(newSession(), request)
        val versions = result.versions.map { it.toString() }.ifEmpty {
            if (settings.isOffline) localVersions(groupId, artifactId) else emptyList()
        }
        val failure = result.exceptions.firstOrNull { it !is MetadataNotFoundException }
        if (versions.isEmpty() && failure != null) throw failure
        return latestMavenVersion(versions)
    }

    /** The versions of the artifact in the local repository, also these resolved without its `maven-metadata.xml`. */
    private fun localVersions(groupId: String, artifactId: String): List<String> =
        File(settings.localRepository, "${groupId.replace('.', '/')}/$artifactId").listFiles().orEmpty().filter {
            File(it, "$artifactId-${it.name}.pom").isFile
        }.map { it.name }

    /**
     * This repository, with the mirror, the proxy and the server credentials of the Maven settings applied,
     * or `null`, if its protocol is neither HTTP(S), nor `file`.
     *
     * The mirror is selected first, and the proxy and the credentials are selected for the mirror,
     * in the order of Maven itself, so a mirror is reached through the proxy, like behind a corporate firewall.
     * `MavenDependenciesResolver` selects the proxy for the original repository instead,
     * so it resolves the artifact from a mirror bypassing the proxy.
     */
    private fun RemoteRepository.withSettings(): RemoteRepository? {
        if (!protocol.matches(supportedProtocol)) return null
        val repository = mirrorSelector.getMirror(this) ?: this
        return RemoteRepository.Builder(repository)
            .setProxy(proxySelector.getProxy(repository))
            // the credentials declared with the repository take precedence over these of the settings
            .setAuthentication(repository.authentication ?: authenticationSelector.getAuthentication(repository))
            .build()
    }

}

/** The protocols of the repositories supported by `MavenDependenciesResolver`. */
private val supportedProtocol = Regex("https?|file")

/**
 * The repository system of Maven Resolver, with its services registered in this locator.
 *
 * @throws ServiceCreationException if a service cannot be created.
 */
@Suppress("DEPRECATION")
internal fun DefaultServiceLocator.repositorySystem(): RepositorySystem {
    // otherwise a service failing to instantiate is returned as null, reported as a bare NPE
    setErrorHandler(object : DefaultServiceLocator.ErrorHandler() {
        override fun serviceCreationFailed(type: Class<*>, impl: Class<*>, exception: Throwable) {
            // the locator reports the failure of a service again for each service depending on it
            throw exception as? ServiceCreationException ?: ServiceCreationException(type, impl, exception)
        }
    })
    return getService(RepositorySystem::class.java)
}

/**
 * A service of Maven Resolver of the [type], which [impl]ementation cannot be created,
 * reported with the root [cause] of the failure, as the locator wraps it in its reflection.
 */
private class ServiceCreationException(
    type: Class<*>,
    impl: Class<*>,
    cause: Throwable
) : IllegalStateException(
    "Could not create the ${type.simpleName} of Maven Resolver, ${impl.name}: ${generateSequence(cause) { it.cause }.last()}",
    cause
)

/**
 * The repository declared with `@file:Repository` with these [coordinates] and [options],
 * with the id and the credentials given by the options, like `MavenDependenciesResolver` declares it,
 * or `null`, if they are not valid, which `MavenDependenciesResolver` reports itself.
 */
internal fun remoteRepository(
    coordinates: RepositoryCoordinates,
    options: ExternalDependenciesResolver.Options
): RemoteRepository? {
    val url = coordinates.toRepositoryUrlOrNull() ?: return null
    // a value starting with `$` names an environment variable, which MavenDependenciesResolver requires to be set
    fun String?.resolved(): String? = this?.let { if (it.startsWith("$")) System.getenv(it.substring(1)) else it }
    val authentication = AuthenticationBuilder()
        .addUsername(options.username.resolved())
        .addPassword(options.password.resolved())
        .addPrivateKey(options.privateKeyFile.resolved(), options.privateKeyPassphrase.resolved())
        .build()
    val id = options.mavenRepoId ?: coordinates.string.replace(forbiddenRepositoryIdChars, "_")
    return RemoteRepository.Builder(id, "default", url.toString()).setAuthentication(authentication).build()
}

/** The characters replaced in a repository id derived from its URL, like `MavenDependenciesResolver` does. */
private val forbiddenRepositoryIdChars = Regex("[/\\\\:<>\"|?*]")
