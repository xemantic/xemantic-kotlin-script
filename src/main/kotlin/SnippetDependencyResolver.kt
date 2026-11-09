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

import kotlinx.coroutines.runBlocking
import org.eclipse.aether.repository.RemoteRepository
import java.io.File
import java.net.URLClassLoader
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptCollectedData
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.ScriptConfigurationRefinementContext
import kotlin.script.experimental.api.asSuccess
import kotlin.script.experimental.api.collectedAnnotations
import kotlin.script.experimental.api.ScriptDiagnostic
import kotlin.script.experimental.api.ScriptSourceAnnotation
import kotlin.script.experimental.api.SourceCode
import kotlin.script.experimental.api.with
import kotlin.script.experimental.api.valueOrNull
import kotlin.script.experimental.dependencies.DependsOn
import kotlin.script.experimental.dependencies.ExternalDependenciesResolver
import kotlin.script.experimental.dependencies.Repository
import kotlin.script.experimental.dependencies.RepositoryCoordinates
import kotlin.script.experimental.dependencies.maven.MavenDependenciesResolver
import kotlin.script.experimental.dependencies.maven.impl.mavenCentral
import kotlin.script.experimental.dependencies.resolveFromScriptSourceAnnotations
import kotlin.script.experimental.jvm.updateClasspath

/**
 * Resolves the Maven dependencies declared by the snippets of a session with `@file:DependsOn`,
 * from the Maven repositories declared with `@file:Repository` by the snippets so far, or Maven Central, if none.
 *
 * A dependency declared without version, like `org.jsoup:jsoup`, is resolved to its latest version, see [versionOf].
 *
 * The resolved JARs are added to the [classLoader] of the snippets at once,
 * as the compiler keeps them on its classpath even if the snippet declaring them fails to compile,
 * so the next snippets can call them, while the evaluator would load them only for an evaluated snippet.
 */
internal class SnippetDependencyResolver(parent: ClassLoader) : AutoCloseable {

    val classLoader = DependencyClassLoader(parent)

    private val mavenResolver = MavenDependenciesResolver()

    /** The resolver, keeping the [repositories] added to the [mavenResolver]. */
    private val resolver = object : ExternalDependenciesResolver by mavenResolver {
        override fun addRepository(
            repositoryCoordinates: RepositoryCoordinates,
            options: ExternalDependenciesResolver.Options,
            sourceCodeLocation: SourceCode.LocationWithId?
        ): ResultWithDiagnostics<Boolean> = mavenResolver.addRepository(
            repositoryCoordinates, options, sourceCodeLocation
        ).also { result ->
            if (result.valueOrNull() == true) {
                remoteRepository(repositoryCoordinates, options)?.let { repository ->
                    // a repository declared again with the same options is looked up once,
                    // while declared again with other options, like corrected credentials, or without them,
                    // it is looked up with each, like the mavenResolver, which keeps every declaration, resolves from each,
                    // until a declaration refused its credentials is superseded by another one read, see versionOf
                    if (repositories.none { (_, it) -> it == repository }) {
                        repositories += repositoryCoordinates.string to repository
                    }
                }
            }
        }
    }

    /**
     * The distinct repositories declared by the snippets so far, by their coordinates, in the order of their declaration,
     * as added to the [mavenResolver], with their options, like the credentials.
     */
    private val repositories = mutableListOf<Pair<String, RemoteRepository>>()

    private val versionLookup = MavenVersionLookup()

    /** The latest versions of the dependencies declared without version, by their coordinates, kept for the session. */
    private val latestVersions = HashMap<String, LatestMavenVersion>()

    /** The resolution of the snippet compiled last, as the compiler refines its configuration more than once. */
    private var last: Resolution? = null

    private class Resolution(
        val snippet: String,
        val files: List<File>,
        val reports: List<ScriptDiagnostic>,
        val warnings: List<ScriptDiagnostic>,
        val errors: List<ScriptDiagnostic>,
        val resolvedVersions: Map<String, String>
    )

    /**
     * The dependencies of a snippet: the [warnings] reporting the [resolvedVersions]
     * of the dependencies declared without version, and the [errors], if any.
     */
    class SnippetDependencies(
        val warnings: List<ScriptDiagnostic> = emptyList(),
        val errors: List<ScriptDiagnostic> = emptyList(),
        val resolvedVersions: Map<String, String> = emptyMap()
    )

    /** Adds the dependencies declared by the snippet of the [context] to its compilation classpath. */
    fun refine(
        context: ScriptConfigurationRefinementContext
    ): ResultWithDiagnostics<ScriptCompilationConfiguration> {
        val annotations = context.collectedData?.get(ScriptCollectedData.collectedAnnotations)
        if (annotations.isNullOrEmpty()) return context.compilationConfiguration.asSuccess()
        val snippet = "${context.script.name}\n${context.script.text}"
        val resolution = last?.takeIf { it.snippet == snippet } ?: resolve(snippet, annotations, context.script.text.lines()).also {
            last = it
        }
        if (resolution.errors.isNotEmpty()) return ResultWithDiagnostics.Failure(resolution.errors)
        return context.compilationConfiguration.with {
            updateClasspath(resolution.files)
        }.asSuccess(resolution.reports)
    }

    /**
     * The dependencies of the snippet compiled last, forgetting its resolution.
     * Their warnings and errors are not returned to the compiler, which reports them stripped of their end.
     * On an error, the compiler continues compiling the snippet without the refined configuration,
     * also without the default imports, so the diagnostics of the failed compilation follow from it.
     */
    fun takeDependencies(): SnippetDependencies = (last?.let {
        SnippetDependencies(it.warnings, it.errors, it.resolvedVersions)
    } ?: SnippetDependencies()).also {
        last = null
    }

    private fun resolve(
        snippet: String,
        annotations: List<ScriptSourceAnnotation<*>>,
        lines: List<String>
    ): Resolution {
        val files = mutableListOf<File>()
        val reports = mutableListOf<ScriptDiagnostic>()
        val warnings = mutableListOf<ScriptDiagnostic>()
        val errors = mutableListOf<ScriptDiagnostic>()
        val resolvedVersions = LinkedHashMap<String, String>()
        // each coordinate resolved on its own, to report which one failed
        annotations.flatMap { (annotation, location) ->
            when (annotation) {
                is DependsOn -> annotation.artifactsCoordinates.map {
                    ScriptSourceAnnotation(DependsOn(it, options = annotation.options), location)
                }
                else -> listOf(ScriptSourceAnnotation(annotation, location))
            }
        }.forEach { declared ->
            val annotation = declared.withLatestVersion(lines, warnings, errors, resolvedVersions) ?: return@forEach
            // called on the thread of the session, while compiling, which is never interrupted
            when (val result = runBlocking { resolver.resolveFromScriptSourceAnnotations(listOf(annotation)) }) {
                is ResultWithDiagnostics.Success -> {
                    files += result.value
                    reports += result.reports
                }
                is ResultWithDiagnostics.Failure -> errors += annotation.failure(result.reports, lines)
            }
        }
        // also the dependencies of a snippet failing to compile, which the compiler keeps on its classpath
        classLoader.add(files)
        return Resolution(snippet, files, reports, warnings, errors, resolvedVersions)
    }

    /**
     * This annotation, with the dependency it declares without version, like `org.jsoup:jsoup`,
     * declared in its latest version instead, reported as a warning, as an LLM might not know its API,
     * and added to the [resolvedVersions],
     * or `null`, with the error added to the [errors], if it has no version.
     */
    private fun ScriptSourceAnnotation<*>.withLatestVersion(
        lines: List<String>,
        warnings: MutableList<ScriptDiagnostic>,
        errors: MutableList<ScriptDiagnostic>,
        resolvedVersions: MutableMap<String, String>
    ): ScriptSourceAnnotation<*>? {
        val dependsOn = annotation as? DependsOn ?: return this
        val coordinates = dependsOn.artifactsCoordinates.single()
        if (coordinates.split(':').size != 2) return this
        val latest = try {
            versionOf(coordinates)
        } catch (e: Exception) {
            val cause = (e.message ?: e.javaClass.name).withRepositoryUrls()
            errors += diagnostic("Unresolved dependency '$coordinates': $cause", lines)
            return null
        }
        resolvedVersions[coordinates] = latest.version
        warnings += diagnostic(
            message = if (latest.stable) {
                "Resolved '$coordinates' to its latest stable version '${latest.version}'"
            } else {
                "Resolved '$coordinates' to its latest pre-release version '${latest.version}', as it has no stable one"
            },
            lines = lines,
            severity = ScriptDiagnostic.Severity.WARNING
        )
        return ScriptSourceAnnotation(DependsOn("$coordinates:${latest.version}", options = dependsOn.options), location)
    }

    /**
     * The latest version of the dependency of these [coordinates], `group:artifact`,
     * in the repositories declared so far, or Maven Central, if none,
     * looked up with their options, like the credentials, and the Maven settings, like the dependency is resolved, see [MavenVersionLookup]:
     * the highest stable one in the Maven ordering, neither a `SNAPSHOT`, nor a pre-release, like `2.0-RC1`,
     * or the highest pre-release one, if it has no stable one, like `pip install` does —
     * a version with a qualifier, like `0.8.0-0.6.x-compat`, gives way to its release, like `0.8.0`,
     * and a date-style version, like `20040616`, to any other one of the same stability, see [latestMavenVersion].
     * It is kept for the session, so a later snippet declaring it again does not load another one.
     *
     * A repository declared with wrong credentials, and again with the corrected ones,
     * is dropped once its declaration with the corrected credentials is read, while the wrong ones are refused with `401`,
     * so neither its failure hides the reason of a failed lookup, nor its credentials are sent again by a later version lookup —
     * the mavenResolver, keeping every declaration, still sends them, resolving the version found.
     */
    private fun versionOf(coordinates: String): LatestMavenVersion = latestVersions.getOrPut(coordinates) {
        val (groupId, artifactId) = coordinates.split(':')
        val searched = repositories.toList().ifEmpty { listOf(mavenCentral.url to mavenCentral) }
        versionLookup.latestVersion(groupId, artifactId, searched.map { it.second }) { stale ->
            repositories.removeAll { (_, repository) -> repository == stale }
        } ?: throw IllegalStateException(
            "Could not find any version of it in ${searched.map { it.first }.distinct().joinToString(", ")}" +
                if (versionLookup.offline) ", in the offline mode of the Maven settings" else ""
        )
    }

    /**
     * The error of this annotation, spanning its whole line,
     * caused by the innermost exception of the [reports], or their message,
     * without the exception itself, which would be reported as an internal failure of the compiler.
     */
    private fun ScriptSourceAnnotation<*>.failure(
        reports: List<ScriptDiagnostic>,
        lines: List<String>
    ): ScriptDiagnostic {
        val subject = when (val annotation = annotation) {
            is DependsOn -> "Unresolved dependency '${annotation.artifactsCoordinates.single()}'"
            is Repository -> "Invalid repository '${annotation.repositoriesCoordinates.joinToString("', '")}'"
            else -> "Unresolved annotation $annotation"
        }
        val cause = reports.joinToString("\n") { report ->
            report.exception?.let { exception ->
                generateSequence(exception) { it.cause }.last().message
            } ?: report.message
        }.withRepositoryUrls()
        return diagnostic("$subject: $cause", lines)
    }

    /** The diagnostic of this annotation, spanning its whole line. */
    private fun ScriptSourceAnnotation<*>.diagnostic(
        message: String,
        lines: List<String>,
        severity: ScriptDiagnostic.Severity = ScriptDiagnostic.Severity.ERROR
    ): ScriptDiagnostic {
        val line = location?.locationInText?.start?.line
        return ScriptDiagnostic(
            code = ScriptDiagnostic.unspecifiedError,
            message = message,
            severity = severity,
            sourcePath = location?.codeLocationId,
            location = line?.let {
                SourceCode.Location(
                    start = SourceCode.Position(it, 1),
                    end = SourceCode.Position(it, (lines.getOrNull(it - 1)?.length ?: 0) + 1)
                )
            }
        )
    }

    override fun close() {
        classLoader.close()
    }

    class DependencyClassLoader(parent: ClassLoader) : URLClassLoader(emptyArray(), parent) {

        fun add(files: List<File>) {
            val urls = urLs.toSet()
            files.map { it.toURI().toURL() }.filter { it !in urls }.forEach(::addURL)
        }

    }

}

/**
 * This message of Maven with each repository, like `repo_id (https://repo/)`, shortened to its URL,
 * as the id is derived from the URL by `MavenDependenciesResolver`, or given by the `mavenRepoId` option,
 * neither of which can contain whitespace, unlike the id of Maven Central, `maven central`.
 */
internal fun String.withRepositoryUrls(): String = replace(repositoryIdWithUrl, "$1")

/**
 * A repository in a message of Maven, like `repo_id (https://repo/)`, see [withRepositoryUrls]:
 * the id of Maven Central, with its space, or any id without whitespace, and the URL in the parentheses following it,
 * so the parentheses of the message itself, like `Unauthorized (401)`, are kept.
 */
private val repositoryIdWithUrl = Regex("""(?:${Regex.escape(mavenCentral.id)}|\S+) \(([a-z]+:[^)]*)\)""")
