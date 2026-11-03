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


package com.xemantic.kotlin.script.test

import java.nio.file.Path
import java.security.MessageDigest
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.name
import kotlin.io.path.outputStream
import kotlin.io.path.readBytes
import kotlin.io.path.relativeTo
import kotlin.io.path.walk
import kotlin.io.path.writeText

/**
 * A local Maven repository of the libraries absent from the classpath of the tests, built once per test run:
 *
 * * `com.xemantic.fixture:greeter:1.0`, with `fixture.greeter.Greeter.greet(name)`,
 *   also published, in this order, as `1.10`, a later `1.9` and `2.0-RC1`, each greeting differently,
 *   so the latest stable version, `1.10`, is neither the last published stable one, nor the highest one;
 * * `com.xemantic.fixture:greeting-card:1.0`, depending on the `greeter`,
 *   with `fixture.card.GreetingCard.write(name)`;
 * * `com.xemantic.fixture:preview:0.1-beta`, without any stable version,
 *   with `fixture.preview.Preview.version()`.
 *
 * The libraries are resolved into a local repository of their own,
 * with Maven settings given by the `org.apache.maven.user-settings` system property,
 * so the tests neither read the `~/.m2/settings.xml`, nor write to the `~/.m2/repository`.
 */
object MavenRepository {

    private val fixtureDir: Path = createTempDirectory("maven-fixture").also { dir ->
        Runtime.getRuntime().addShutdownHook(Thread { dir.toFile().deleteRecursively() })
    }

    /** The directory of the repository. */
    val directory: Path = fixtureDir.resolve("repository")

    /** The URL of the repository, to be declared with `@file:Repository`. */
    val url: String = directory.toUri().toString()

    /** The local repository, which the libraries are resolved into. */
    val localRepository: Path = fixtureDir.resolve("local")

    init {
        val repository = directory
        val settings = fixtureDir.resolve("settings.xml")
        settings.writeText("""
            <settings>
              <localRepository>$localRepository</localRepository>
            </settings>
        """.trimIndent())
        System.setProperty("org.apache.maven.user-settings", settings.toString())
        val greeters = listOf("1.0" to "Hello", "1.10" to "Hi", "1.9" to "Hey", "2.0-RC1" to "Howdy").map { (version, greeting) ->
            repository.library(
                artifactId = "greeter",
                version = version,
                sources = mapOf(
                    "fixture/greeter/Greeter.java" to """
                        package fixture.greeter;
                        public class Greeter {
                            public static String greet(String name) { return "$greeting " + name; }
                        }
                    """.trimIndent()
                )
            )
        }
        repository.metadata("greeter", greeters.map { it.version })
        repository.library(
            artifactId = "greeting-card",
            sources = mapOf(
                "fixture/card/GreetingCard.java" to """
                    package fixture.card;
                    public class GreetingCard {
                        public static String write(String name) {
                            return "[" + fixture.greeter.Greeter.greet(name) + "]";
                        }
                    }
                """.trimIndent()
            ),
            dependencies = listOf("greeter"),
            classpath = listOf(greeters.first().jar)
        )
        repository.metadata("greeting-card", listOf("1.0"))
        repository.library(
            artifactId = "preview",
            version = "0.1-beta",
            sources = mapOf(
                "fixture/preview/Preview.java" to """
                    package fixture.preview;
                    public class Preview {
                        public static String version() { return "0.1-beta"; }
                    }
                """.trimIndent()
            )
        )
        repository.metadata("preview", listOf("0.1-beta"))
        repository.checksums()
    }

    /** Writes the `.sha1` of every file, as Maven warns about each download without a checksum. */
    private fun Path.checksums() {
        walk().toList().forEach { file ->
            val digest = MessageDigest.getInstance("SHA-1").digest(file.readBytes())
            file.resolveSibling("${file.name}.sha1").writeText(digest.toHexString())
        }
    }

    private class Library(val version: String, val jar: Path)

    /** Writes the `maven-metadata.xml` of the [artifactId], listing its [versions] in the order of publication. */
    private fun Path.metadata(artifactId: String, versions: List<String>) {
        resolve("com/xemantic/fixture/$artifactId/maven-metadata.xml").writeText("""
            <metadata>
              <groupId>com.xemantic.fixture</groupId>
              <artifactId>$artifactId</artifactId>
              <versioning>
                <latest>${versions.last()}</latest>
                <release>${versions.last()}</release>
                <versions>
                  ${versions.joinToString("\n") { "<version>$it</version>" }}
                </versions>
                <lastUpdated>20261103000000</lastUpdated>
              </versioning>
            </metadata>
        """.trimIndent())
    }

    /** Compiles the [sources] into a JAR of the [artifactId], with a POM declaring its [dependencies] in their version `1.0`. */
    private fun Path.library(
        artifactId: String,
        version: String = "1.0",
        sources: Map<String, String>,
        dependencies: List<String> = emptyList(),
        classpath: List<Path> = emptyList()
    ): Library {
        val work = fixtureDir.resolve("work/$artifactId/$version")
        val sourceFiles = sources.map { (path, source) ->
            work.resolve("src/$path").also {
                it.parent.createDirectories()
                it.writeText(source)
            }
        }
        val classes = work.resolve("classes").createDirectories()
        val compiled = ToolProvider.getSystemJavaCompiler().run(
            null, null, null,
            "-d", classes.toString(),
            "-cp", classpath.joinToString(java.io.File.pathSeparator),
            *sourceFiles.map { it.toString() }.toTypedArray()
        )
        check(compiled == 0) { "Cannot compile the $artifactId fixture" }
        val directory = resolve("com/xemantic/fixture/$artifactId/$version").createDirectories()
        val jar = directory.resolve("$artifactId-$version.jar")
        JarOutputStream(jar.outputStream()).use { out ->
            classes.walk().forEach { file ->
                out.putNextEntry(ZipEntry(file.relativeTo(classes).joinToString("/")))
                out.write(file.readBytes())
                out.closeEntry()
            }
        }
        directory.resolve("$artifactId-$version.pom").writeText("""
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>com.xemantic.fixture</groupId>
              <artifactId>$artifactId</artifactId>
              <version>$version</version>
              <dependencies>
                ${dependencies.joinToString("\n") { """
                <dependency>
                  <groupId>com.xemantic.fixture</groupId>
                  <artifactId>$it</artifactId>
                  <version>1.0</version>
                </dependency>
                """ }}
              </dependencies>
            </project>
        """.trimIndent())
        return Library(version, jar)
    }

}
