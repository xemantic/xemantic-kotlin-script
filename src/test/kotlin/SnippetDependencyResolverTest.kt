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

import com.xemantic.kotlin.test.assert
import org.apache.maven.settings.Settings
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.script.experimental.api.valueOrNull
import kotlin.script.experimental.dependencies.RepositoryCoordinates
import kotlin.script.experimental.dependencies.maven.MavenDependenciesResolver
import kotlin.test.Test

/**
 * The shortening of the repositories in the messages of Maven, which the resolver reports,
 * also of Maven Central, which cannot be reached by a deterministic test, see [withRepositoryUrls],
 * the release of the resolver, and the behavior of `MavenDependenciesResolver` the resolver relies on.
 */
class SnippetDependencyResolverTest {

    @Test
    fun `should shorten repository with id derived from URL to the URL`() {
        // given the message of a repository declared with its URL, with an id derived from it
        val message = "Could not find artifact com.xemantic.fixture:missing:jar:1.0 in file_tmp_repository (file:/tmp/repository)"

        // when
        val shortened = message.withRepositoryUrls()

        // then
        assert(shortened == "Could not find artifact com.xemantic.fixture:missing:jar:1.0 in file:/tmp/repository")
    }

    @Test
    fun `should shorten Maven Central with its id of spaces to its URL`() {
        // given
        val message = "Could not find artifact org.jsoup:jsoup:jar:1.0 in maven central (https://repo.maven.apache.org/maven2/)"

        // when
        val shortened = message.withRepositoryUrls()

        // then
        assert(shortened == "Could not find artifact org.jsoup:jsoup:jar:1.0 in https://repo.maven.apache.org/maven2/")
    }

    @Test
    fun `should shorten Maven Central inside message of offline mode to its URL`() {
        // given the message of Maven Resolver in the offline mode of the settings
        val message = "Cannot access maven central (https://repo.maven.apache.org/maven2/) in offline mode " +
            "and the artifact org.jsoup:jsoup:jar:1.0 has not been downloaded from it before"

        // when
        val shortened = message.withRepositoryUrls()

        // then
        assert(
            shortened == "Cannot access https://repo.maven.apache.org/maven2/ in offline mode " +
                "and the artifact org.jsoup:jsoup:jar:1.0 has not been downloaded from it before"
        )
    }

    @Test
    fun `should keep parentheses of message around repository`() {
        // given the message of a transfer failure, with the status in parentheses of its own
        val message = "Could not transfer metadata com.xemantic.fixture:greeter/maven-metadata.xml " +
            "from/to http___127.0.0.1_8080_ (http://127.0.0.1:8080/): status code: 401, reason phrase: Unauthorized (401)"

        // when
        val shortened = message.withRepositoryUrls()

        // then
        assert(
            shortened == "Could not transfer metadata com.xemantic.fixture:greeter/maven-metadata.xml " +
                "from/to http://127.0.0.1:8080/: status code: 401, reason phrase: Unauthorized (401)"
        )
    }

    /** See #209. */
    @Test
    fun `should close version lookup once closed`() {
        // given
        val resolver = SnippetDependencyResolver(javaClass.classLoader, "test-session")

        // when
        resolver.close()

        // then
        val failure = runCatching {
            resolver.versionLookup.latestVersion("com.xemantic.fixture", "greeter", emptyList())
        }.exceptionOrNull()
        assert(failure is IllegalStateException && failure.message == "The version lookup is closed")
    }

    /** See #209. */
    @Test
    fun `should close class loader even if closing version lookup fails`() {
        // given a version lookup, which fails to shut down the repository system created by its lookup
        val localRepository = createTempDirectory("maven-local").toFile()
        val dependencies = createTempDirectory("dependencies")
        try {
            dependencies.resolve("marker.txt").writeText("marker")
            val versionLookup = MavenVersionLookup(
                settingsFactory = { Settings().apply { this.localRepository = localRepository.path } },
                repositorySystemFactory = {
                    newRepositorySystem().apply { addOnSystemEndedHandler { error("Shutdown failed") } }
                }
            )
            val resolver = SnippetDependencyResolver(javaClass.classLoader, "test-session", versionLookup)
            versionLookup.latestVersion("com.xemantic.fixture", "greeter", emptyList())
            resolver.classLoader.add(listOf(dependencies.toFile()))
            val markerBefore = resolver.classLoader.getResource("marker.txt")

            // when
            val failure = runCatching { resolver.close() }.exceptionOrNull()

            // then
            assert(markerBefore != null)
            assert(failure?.suppressed?.map { it.message } == listOf("Shutdown failed"))
            assert(resolver.classLoader.getResource("marker.txt") == null)
        } finally {
            localRepository.deleteRecursively()
            dependencies.toFile().deleteRecursively()
        }
    }


    /**
     * The resolver creates its `MavenDependenciesResolver` again from the repositories accepted once,
     * relying on it to accept each again, as it reads only the syntax of the URL, and the environment variables, see #237.
     */
    @Test
    fun `should accept file repository of deleted directory`() {
        // given
        val directory = createTempDirectory("deleted-repository").toFile()
        val coordinates = RepositoryCoordinates(directory.toURI().toString())
        directory.delete()

        // when
        val result = MavenDependenciesResolver().addRepository(coordinates)

        // then
        assert(result.valueOrNull() == true)
    }

}
