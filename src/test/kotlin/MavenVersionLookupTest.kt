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

import com.xemantic.kotlin.script.test.MavenRepository
import com.xemantic.kotlin.script.test.MavenRepositoryServer
import com.xemantic.kotlin.script.test.ProxyServer
import com.xemantic.kotlin.test.assert
import org.apache.maven.repository.internal.MavenRepositorySystemUtils
import org.apache.maven.settings.Mirror
import org.apache.maven.settings.Proxy
import org.apache.maven.settings.Server
import org.apache.maven.settings.Settings
import org.eclipse.aether.RepositorySystemSession
import org.eclipse.aether.connector.basic.BasicRepositoryConnectorFactory
import org.eclipse.aether.repository.RemoteRepository
import org.eclipse.aether.spi.connector.RepositoryConnectorFactory
import org.eclipse.aether.spi.connector.transport.Transporter
import org.eclipse.aether.spi.connector.transport.TransporterFactory
import org.eclipse.aether.transfer.NoRepositoryConnectorException
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.script.experimental.api.valueOrThrow
import kotlin.script.experimental.dependencies.RepositoryCoordinates
import kotlin.script.experimental.dependencies.impl.SimpleExternalDependenciesResolverOptionsParser
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * The lookup of the latest version of an artifact with the Maven settings,
 * given to it directly, as the settings of the other tests are shared by the system property set by the [MavenRepository].
 */
class MavenVersionLookupTest {

    private val localRepository: Path = createTempDirectory("maven-local")

    /** A repository which cannot be connected to. */
    @Suppress("HttpUrlsUsage")
    private val unreachable = RemoteRepository.Builder("unreachable", "default", "http://127.0.0.1:1/").build()

    @AfterTest
    fun deleteLocalRepository() {
        localRepository.toFile().deleteRecursively()
    }

    private fun settings(block: Settings.() -> Unit = {}): Settings = Settings().apply {
        localRepository = this@MavenVersionLookupTest.localRepository.toString()
        block()
    }

    @Test
    fun `should look up latest version in repository of declared credentials`() {
        MavenRepositoryServer(username = "alice", password = "secret").use { server ->
            // given
            val lookup = MavenVersionLookup { settings() }
            val repository = remoteRepository(server.url, "username=alice", "password=secret")

            // when
            val latest = lookup.latestVersion("com.xemantic.fixture", "greeter", listOf(repository))

            // then
            assert(latest?.version == "1.10" && latest.stable)
        }
    }

    @Test
    fun `should look up latest version in repository of credentials of server in settings`() {
        MavenRepositoryServer(username = "alice", password = "secret").use { server ->
            // given
            val lookup = MavenVersionLookup {
                settings {
                    addServer(Server().apply {
                        id = "fixture"
                        username = "alice"
                        password = "secret"
                    })
                }
            }
            val repository = remoteRepository(server.url, "maven_repository_id=fixture")

            // when
            val latest = lookup.latestVersion("com.xemantic.fixture", "greeter", listOf(repository))

            // then
            assert(latest?.version == "1.10" && latest.stable)
        }
    }

    @Test
    fun `should fail to look up latest version in repository without its credentials`() {
        MavenRepositoryServer(username = "alice", password = "secret").use { server ->
            // given
            val lookup = MavenVersionLookup { settings() }
            val repository = remoteRepository(server.url)

            // when
            val result = runCatching {
                lookup.latestVersion("com.xemantic.fixture", "greeter", listOf(repository))
            }

            // then
            assert(result.exceptionOrNull()?.message?.contains("401") == true)
        }
    }

    @Test
    fun `should look up latest version in mirror of repository in settings`() {
        // given
        val lookup = MavenVersionLookup {
            settings {
                addMirror(Mirror().apply {
                    id = "fixture"
                    mirrorOf = "*"
                    url = MavenRepository.url
                })
            }
        }

        // when
        val latest = lookup.latestVersion("com.xemantic.fixture", "greeter", listOf(unreachable))

        // then
        assert(latest?.version == "1.10" && latest.stable)
    }

    @Test
    fun `should look up latest version in mirror of repository through proxy in settings`() {
        ProxyServer().use { proxy ->
            // given a mirror behind a firewall, which only the proxy of the settings passes
            @Suppress("HttpUrlsUsage")
            val mirrorUrl = "http://127.0.0.1:1/"
            val lookup = MavenVersionLookup {
                settings {
                    addMirror(Mirror().apply {
                        id = "fixture"
                        mirrorOf = "*"
                        url = mirrorUrl
                    })
                    addProxy(Proxy().apply {
                        id = "firewall"
                        protocol = "http"
                        host = proxy.host
                        port = proxy.port
                    })
                }
            }

            // when
            val latest = lookup.latestVersion(
                "com.xemantic.fixture", "greeter", listOf(remoteRepository("https://repo.example.com/"))
            )

            // then
            assert(latest?.version == "1.10" && latest.stable)
            assert(proxy.urls.contains("${mirrorUrl}com/xemantic/fixture/greeter/maven-metadata.xml"))
        }
    }

    @Test
    fun `should fail to look up latest version in repository blocked by mirror in settings`() {
        // given
        val lookup = MavenVersionLookup { settings { addMirror(httpBlocker()) } }
        @Suppress("HttpUrlsUsage")
        val repository = remoteRepository("http://repo.example.com/")

        // when
        val result = runCatching {
            lookup.latestVersion("com.xemantic.fixture", "greeter", listOf(repository))
        }

        // then
        val message = result.exceptionOrNull()?.message
        assert(
            message == "Repository http://repo.example.com/ is blocked by the mirror 'maven-default-http-blocker' of the Maven settings"
        )
    }

    @Test
    fun `should report repository blocked by mirror in settings over earlier failure`() {
        // given a repository, which cannot be connected to, declared before the blocked one
        val lookup = MavenVersionLookup { settings { addMirror(httpBlocker()) } }
        @Suppress("HttpUrlsUsage")
        val blocked = remoteRepository("http://repo.example.com/")

        // when
        val result = runCatching {
            lookup.latestVersion("com.xemantic.fixture", "greeter", listOf(unreachable, blocked))
        }

        // then
        val message = result.exceptionOrNull()?.message
        assert(
            message == "Repository http://repo.example.com/ is blocked by the mirror 'maven-default-http-blocker' of the Maven settings"
        )
    }

    @Test
    fun `should report all repositories blocked by mirror in settings`() {
        // given
        val lookup = MavenVersionLookup { settings { addMirror(httpBlocker()) } }
        @Suppress("HttpUrlsUsage")
        val repositories = listOf(remoteRepository("http://a.example.com/"), remoteRepository("http://b.example.com/"))

        // when
        val result = runCatching {
            lookup.latestVersion("com.xemantic.fixture", "greeter", repositories)
        }

        // then
        val message = result.exceptionOrNull()?.message
        assert(
            message == "Repositories http://a.example.com/, http://b.example.com/ " +
                "are blocked by the mirror 'maven-default-http-blocker' of the Maven settings"
        )
    }

    @Test
    fun `should report repositories blocked by several mirrors in settings by mirror`() {
        // given a blocking mirror of each repository, by its id
        val lookup = MavenVersionLookup {
            settings {
                addMirror(httpBlocker(id = "blocker-a", mirrorOf = "a"))
                addMirror(httpBlocker(id = "blocker-b", mirrorOf = "b"))
            }
        }
        val repositories = listOf(
            remoteRepository("https://a.example.com/", "maven_repository_id=a"),
            remoteRepository("https://b.example.com/", "maven_repository_id=b")
        )

        // when
        val result = runCatching {
            lookup.latestVersion("com.xemantic.fixture", "greeter", repositories)
        }

        // then
        val message = result.exceptionOrNull()?.message
        assert(
            message == "Repository https://a.example.com/ is blocked by the mirror 'blocker-a' of the Maven settings\n" +
                "Repository https://b.example.com/ is blocked by the mirror 'blocker-b' of the Maven settings"
        )
    }

    @Test
    fun `should find blocking mirror wrapped deeper in cause chain`() {
        // given the missing connector of a blocking mirror, wrapped twice
        @Suppress("HttpUrlsUsage")
        val mirror = RemoteRepository.Builder("blocker", "default", "http://0.0.0.0/").setBlocked(true).build()
        val failure = IllegalStateException(
            "Could not resolve", IllegalStateException("Could not read", NoRepositoryConnectorException(mirror))
        )

        // when
        val blocking = failure.blockedMirror()

        // then
        assert(blocking?.id == "blocker")
    }

    @Test
    fun `should look up latest version in local repository in offline mode`() {
        // given
        localRepository.artifact("com/xemantic/fixture/greeter", "1.0", "1.10", "2.0-RC1")
        val lookup = MavenVersionLookup {
            settings {
                isOffline = true
            }
        }

        // when
        val latest = lookup.latestVersion("com.xemantic.fixture", "greeter", listOf(unreachable))

        // then
        assert(latest?.version == "1.10" && latest.stable)
    }

    @Test
    fun `should look up latest version in metadata cached in local repository from repository unavailable since`() {
        // given the metadata looked up from a server, which is closed then
        val repository = MavenRepositoryServer(username = "alice", password = "secret").use { server ->
            remoteRepository(server.url, "username=alice", "password=secret").also {
                MavenVersionLookup { settings() }.latestVersion("com.xemantic.fixture", "greeter", listOf(it))
            }
        }
        val lookup = MavenVersionLookup { settings() }

        // when
        val latest = lookup.latestVersion("com.xemantic.fixture", "greeter", listOf(repository))

        // then
        assert(latest?.version == "1.10" && latest.stable)
    }

    @Test
    fun `should look up latest of versions which Maven orders below zero`() {
        // given the versions of early Guava releases, which Maven orders below any numeric one
        val repository = createTempDirectory("maven-remote")
        try {
            repository.resolve("com/google/guava/guava").createDirectories().resolve("maven-metadata.xml").writeText("""
                <metadata>
                  <groupId>com.google.guava</groupId>
                  <artifactId>guava</artifactId>
                  <versioning>
                    <versions>
                      <version>r03</version>
                      <version>r09</version>
                    </versions>
                  </versioning>
                </metadata>
            """.trimIndent())
            val lookup = MavenVersionLookup { settings() }

            // when
            val latest = lookup.latestVersion(
                "com.google.guava", "guava", listOf(remoteRepository(repository.toUri().toString()))
            )

            // then
            assert(latest?.version == "r09" && latest.stable)
        } finally {
            repository.toFile().deleteRecursively()
        }
    }

    @Test
    fun `should look up latest version again after failure`() {
        MavenRepositoryServer(username = "alice", password = "secret").use { server ->
            // given
            val lookup = MavenVersionLookup { settings() }
            val repository = remoteRepository(server.url, "username=alice", "password=secret")
            server.failingRequests = 1
            val failure = runCatching {
                lookup.latestVersion("com.xemantic.fixture", "greeter", listOf(repository))
            }.exceptionOrNull()

            // when
            val latest = lookup.latestVersion("com.xemantic.fixture", "greeter", listOf(repository))

            // then
            assert(failure?.message?.contains("500") == true)
            assert(latest?.version == "1.10" && latest.stable)
        }
    }

    @Test
    fun `should look up latest version of artifact published after it was not found`() {
        // given
        val repository = createTempDirectory("maven-remote")
        try {
            val lookup = MavenVersionLookup { settings() }
            val remote = listOf(remoteRepository(repository.toUri().toString()))
            val notFound = lookup.latestVersion("com.xemantic.fixture", "published", remote)
            repository.resolve("com/xemantic/fixture/published").createDirectories().resolve("maven-metadata.xml").writeText("""
                <metadata>
                  <groupId>com.xemantic.fixture</groupId>
                  <artifactId>published</artifactId>
                  <versioning>
                    <versions>
                      <version>1.0</version>
                    </versions>
                  </versioning>
                </metadata>
            """.trimIndent())

            // when
            val latest = lookup.latestVersion("com.xemantic.fixture", "published", remote)

            // then
            assert(notFound == null)
            assert(latest?.version == "1.0" && latest.stable)
        } finally {
            repository.toFile().deleteRecursively()
        }
    }

    @Test
    fun `should look up latest version in file repository in offline mode`() {
        // given
        val lookup = MavenVersionLookup {
            settings {
                isOffline = true
            }
        }

        // when
        val latest = lookup.latestVersion(
            "com.xemantic.fixture", "greeter", listOf(remoteRepository(MavenRepository.url))
        )

        // then
        assert(latest?.version == "1.10" && latest.stable)
    }

    @Test
    fun `should not find any version of missing artifact`() {
        // given
        val lookup = MavenVersionLookup { settings() }

        // when
        val latest = lookup.latestVersion(
            "com.xemantic.fixture", "missing", listOf(remoteRepository(MavenRepository.url))
        )

        // then
        assert(latest == null)
    }

    @Test
    fun `should report root cause of service of Maven Resolver which cannot be created`() {
        // given a transporter factory, which the locator creates for the repository system through 4 other services
        @Suppress("DEPRECATION")
        val locator = MavenRepositorySystemUtils.newServiceLocator().apply {
            addService(RepositoryConnectorFactory::class.java, BasicRepositoryConnectorFactory::class.java)
            addService(TransporterFactory::class.java, FailingTransporterFactory::class.java)
        }

        // when
        val failure = runCatching { locator.repositorySystem() }.exceptionOrNull()

        // then
        assert(
            failure?.message == "Could not create the TransporterFactory of Maven Resolver, " +
                "${FailingTransporterFactory::class.java.name}: java.lang.IllegalStateException: No transport"
        )
    }

    /**
     * A blocking mirror of the settings, like the `maven-default-http-blocker` of Maven by default,
     * which blocks the plain HTTP repositories, except these of the localhost.
     */
    private fun httpBlocker(
        id: String = "maven-default-http-blocker",
        mirrorOf: String = "external:http:*"
    ): Mirror = Mirror().apply {
        this.id = id
        this.mirrorOf = mirrorOf
        @Suppress("HttpUrlsUsage")
        url = "http://0.0.0.0/"
        isBlocked = true
    }

    /** The repository declared with `@file:Repository` with these [coordinates] and [options]. */
    private fun remoteRepository(coordinates: String, vararg options: String): RemoteRepository = remoteRepository(
        RepositoryCoordinates(coordinates),
        SimpleExternalDependenciesResolverOptionsParser(*options).valueOrThrow()
    )!!

    /** Writes the POM of each of the [versions] of the artifact of this [path] in the local repository. */
    private fun Path.artifact(path: String, vararg versions: String) {
        val artifactId = path.substringAfterLast('/')
        versions.forEach { version ->
            resolve("$path/$version").createDirectories().resolve("$artifactId-$version.pom").writeText("<project/>")
        }
    }

}

/**
 * A transporter factory, which cannot be created,
 * public in the bytecode, as the locator accesses the constructor of a package-private class reflectively.
 */
internal class FailingTransporterFactory : TransporterFactory {

    init {
        error("No transport")
    }

    override fun newInstance(
        session: RepositorySystemSession,
        repository: RemoteRepository
    ): Transporter = throw UnsupportedOperationException()

    override fun getPriority(): Float = 0f

}
