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

import org.eclipse.aether.util.version.GenericVersionScheme
import org.w3c.dom.Element
import java.io.File
import java.io.FileNotFoundException
import java.net.URI
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/** The repository of a dependency, when no other one is declared, like for `MavenDependenciesResolver`. */
internal const val MAVEN_CENTRAL = "https://repo.maven.apache.org/maven2/"

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

private val versionScheme = GenericVersionScheme()

/** The numeric release of a version, like the `0.8.0` of `0.8.0-0.6.x-compat`. */
private val numericRelease = Regex("""^\d+(\.\d+)*""")

private fun String.isPreRelease() = split(versionTokenSeparator).any { preReleaseToken.matches(it) }

/**
 * The latest of the [versions] in the Maven ordering, preferring the stable ones,
 * never a `SNAPSHOT`, or `null`, if there is none.
 * A stable version with a qualifier, which Maven orders after its release, like `0.8.0-0.6.x-compat` after `0.8.0`,
 * gives way to its release, if there is one.
 */
internal fun latestMavenVersion(
    versions: Collection<String>
): LatestMavenVersion? {
    val candidates = versions.filterNot { it.endsWith("-SNAPSHOT", ignoreCase = true) }
    val (preReleases, stable) = candidates.partition { it.isPreRelease() }
    return stable.maxByOrNull { versionScheme.parseVersion(it) }?.let { highest ->
        val release = numericRelease.find(highest)?.value
        LatestMavenVersion(stable.firstOrNull { it == release } ?: highest, stable = true)
    }
        ?: preReleases.maxByOrNull { versionScheme.parseVersion(it) }?.let { LatestMavenVersion(it, stable = false) }
}

/**
 * The latest version of the artifact of the [groupId] and [artifactId],
 * among the versions listed by the `maven-metadata.xml` of any of the [repositories], a URL or a directory,
 * or `null`, if none lists any.
 *
 * @throws java.io.IOException if no repository lists any version, and some cannot be read.
 */
internal fun latestMavenVersion(
    groupId: String,
    artifactId: String,
    repositories: List<String>
): LatestMavenVersion? {
    val failures = mutableListOf<Exception>()
    val versions = repositories.flatMap { repository ->
        try {
            metadataVersions(repository, groupId, artifactId)
        } catch (e: FileNotFoundException) {
            emptyList()
        } catch (e: Exception) {
            failures += e
            emptyList()
        }
    }
    if (versions.isEmpty() && failures.isNotEmpty()) throw failures.first()
    return latestMavenVersion(versions)
}

private fun metadataVersions(
    repository: String,
    groupId: String,
    artifactId: String
): List<String> {
    val base = repository.toRepositoryUri().toString().removeSuffix("/")
    val url = URI("$base/${groupId.replace('.', '/')}/$artifactId/maven-metadata.xml").toURL()
    val connection = url.openConnection().apply {
        connectTimeout = 10_000
        readTimeout = 30_000
    }
    val document = connection.getInputStream().use { input ->
        DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        }.newDocumentBuilder().parse(input)
    }
    val versionLists = document.getElementsByTagName("versions")
    return (0 until versionLists.length).flatMap { index ->
        val list = versionLists.item(index) as Element
        val versions = list.getElementsByTagName("version")
        (0 until versions.length).map { versions.item(it).textContent.trim() }
    }.filter { it.isNotEmpty() }
}

/** The URI of a repository declared with `@file:Repository`, a URL, or a directory. */
private fun String.toRepositoryUri(): URI = runCatching { URI(this) }.getOrNull()?.takeIf {
    it.scheme != null && it.scheme.length > 1 // not a Windows drive letter
} ?: File(this).toURI()
