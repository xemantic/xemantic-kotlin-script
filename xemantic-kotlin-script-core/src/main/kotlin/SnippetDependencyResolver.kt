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

import io.github.oshai.kotlinlogging.KotlinLogging
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
import kotlin.script.experimental.dependencies.ArtifactWithLocation
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
 *
 * @param name the name of the session, logged with a superseded repository declaration, see [withLatestVersion].
 * @property versionLookup the lookup of the latest version of a dependency, exposed only for the tests.
 */
internal class SnippetDependencyResolver(
    parent: ClassLoader,
    private val name: String,
    val versionLookup: MavenVersionLookup = MavenVersionLookup()
) : AutoCloseable {

    val classLoader = DependencyClassLoader(parent)

    /**
     * A repository declared by a snippet with `@file:Repository`, by its [coordinates], [options] and [location],
     * as added to the [mavenResolver], and as the [repository] looked up by the [versionLookup].
     */
    private class Declaration(
        val coordinates: RepositoryCoordinates,
        val options: ExternalDependenciesResolver.Options,
        val location: SourceCode.LocationWithId?,
        val repository: RemoteRepository
    )

    /**
     * The distinct repositories declared by the snippets so far, in the order of their declaration, with their options, like the credentials,
     * but the superseded ones, see [versionOf] — the only state of the repositories, which the [mavenResolver] is derived from.
     *
     * A repository declared again with the same options is kept once,
     * while declared again with other options, like corrected credentials, or without them, it is kept with each,
     * and resolved from each, until a declaration refused its credentials is superseded by another one served.
     */
    private val declarations = mutableListOf<Declaration>()

    /**
     * The resolver of the dependencies in their versions, given the [declarations] so far, each new one added to it,
     * or `null`, once one is dropped, to be created again, as it cannot drop a declaration itself, see [mavenResolver].
     */
    private var currentMavenResolver: MavenDependenciesResolver? = null

    /**
     * The resolver of the dependencies in their versions, given the [declarations],
     * which were all accepted once added, so it accepts each again:
     * `MavenDependenciesResolver` reads only the syntax of the URL, and the environment variables of the options, which cannot change in the JVM,
     * so a `file:` repository is accepted also once its directory is deleted, like it is before the directory is created.
     */
    private fun mavenResolver(): MavenDependenciesResolver = currentMavenResolver ?: MavenDependenciesResolver().apply {
        declarations.forEach { add(it.coordinates, it.options, it.location) }
    }.also {
        currentMavenResolver = it
    }

    /**
     * Adds the repository accepted once already to this resolver, see [mavenResolver].
     *
     * @throws IllegalStateException if a later version of `MavenDependenciesResolver` rejects it,
     *   instead of resolving silently without it, while it stays among the [declarations].
     */
    private fun MavenDependenciesResolver.add(
        coordinates: RepositoryCoordinates,
        options: ExternalDependenciesResolver.Options,
        location: SourceCode.LocationWithId?
    ) {
        val result = addRepository(coordinates, options, location)
        check(result.valueOrNull() == true) {
            "The repository '${coordinates.string}' accepted once is no longer accepted: ${result.reports.joinToString { it.message }}"
        }
    }

    /** The resolver of the annotations of a snippet, adding a repository it declares to the [declarations]. */
    private val resolver = object : ExternalDependenciesResolver {

        override fun acceptsRepository(repositoryCoordinates: RepositoryCoordinates): Boolean =
            mavenResolver().acceptsRepository(repositoryCoordinates)

        override fun acceptsArtifact(artifactCoordinates: String): Boolean =
            mavenResolver().acceptsArtifact(artifactCoordinates)

        override suspend fun resolve(
            artifactsWithLocations: List<ArtifactWithLocation>,
            options: ExternalDependenciesResolver.Options
        ): ResultWithDiagnostics<List<File>> = mavenResolver().resolve(artifactsWithLocations, options)

        override suspend fun resolve(
            artifactCoordinates: String,
            options: ExternalDependenciesResolver.Options,
            sourceCodeLocation: SourceCode.LocationWithId?
        ): ResultWithDiagnostics<List<File>> = mavenResolver().resolve(artifactCoordinates, options, sourceCodeLocation)

        /**
         * Validates the repository by adding it to a new `MavenDependenciesResolver`, which reports an invalid one,
         * like an unset environment variable of its credentials,
         * and adds it to the [declarations], if it is accepted, which `remoteRepository` declares alike,
         * and to the [currentMavenResolver], if any, instead of creating it again from all the [declarations],
         * which would be quadratic in their number.
         */
        override fun addRepository(
            repositoryCoordinates: RepositoryCoordinates,
            options: ExternalDependenciesResolver.Options,
            sourceCodeLocation: SourceCode.LocationWithId?
        ): ResultWithDiagnostics<Boolean> = MavenDependenciesResolver().addRepository(
            repositoryCoordinates, options, sourceCodeLocation
        ).also { result ->
            if (result.valueOrNull() == true) {
                remoteRepository(repositoryCoordinates, options)?.let { repository ->
                    if (declarations.none { it.repository == repository }) {
                        // to the resolver first, so a declaration it rejects is not left among the declarations without it
                        currentMavenResolver?.add(repositoryCoordinates, options, sourceCodeLocation)
                        declarations += Declaration(repositoryCoordinates, options, sourceCodeLocation, repository)
                    }
                }
            }
        }

    }

    /** The latest versions of the dependencies declared without version, by their coordinates, kept for the session. */
    private val latestVersions = HashMap<String, LatestMavenVersion>()

    /** The resolution of the snippet compiled last, as the compiler refines its configuration more than once. */
    private var last: Resolution? = null

    /**
     * @property reports the reports of the resolution, returned to the compiler.
     * @property dependencies the dependencies of the snippet.
     */
    private class Resolution(
        val snippet: String,
        val files: List<File>,
        val reports: List<ScriptDiagnostic>,
        val dependencies: SnippetDependencies
    )

    /**
     * The dependencies of a snippet: the warnings reporting the [resolvedVersions]
     * of the dependencies declared without version, and the repository declarations superseded, see [versionOf],
     * and the [errors], if any.
     *
     * @param diagnostics the warnings and the errors, in the order of the lines of the snippet.
     * @param versionWarnings the warnings among the [diagnostics] reporting the [resolvedVersions].
     */
    class SnippetDependencies(
        val diagnostics: List<ScriptDiagnostic> = emptyList(),
        val resolvedVersions: Map<String, String> = emptyMap(),
        private val versionWarnings: Set<ScriptDiagnostic> = emptySet()
    ) {

        /** The errors among the [diagnostics], which fail the snippet, returned to the compiler. */
        internal val errors: List<ScriptDiagnostic> = diagnostics.filter { it.severity >= ScriptDiagnostic.Severity.ERROR }

        /** Whether resolving a dependency failed, reported by the [diagnostics] then. */
        val failed: Boolean get() = errors.isNotEmpty()

        /**
         * The warnings, in the order of the lines of the snippet,
         * of a snippet which [succeeded] without the ones of the [resolvedVersions], which report them then.
         */
        fun warnings(succeeded: Boolean): List<ScriptDiagnostic> = diagnostics.filter {
            it.severity == ScriptDiagnostic.Severity.WARNING && !(succeeded && it in versionWarnings)
        }

    }

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
        if (resolution.dependencies.failed) return ResultWithDiagnostics.Failure(resolution.dependencies.errors)
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
    fun takeDependencies(): SnippetDependencies = (last?.dependencies ?: SnippetDependencies()).also {
        last = null
    }

    private fun resolve(
        snippet: String,
        annotations: List<ScriptSourceAnnotation<*>>,
        lines: List<String>
    ): Resolution {
        val files = mutableListOf<File>()
        val reports = mutableListOf<ScriptDiagnostic>()
        // in the order of the annotations, which is the one of their lines
        val diagnostics = mutableListOf<ScriptDiagnostic>()
        val versionWarnings = mutableSetOf<ScriptDiagnostic>()
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
            val annotation = declared.withLatestVersion(lines, diagnostics, versionWarnings, resolvedVersions) ?: return@forEach
            // called on the thread of the session, while compiling, which is never interrupted
            when (val result = runBlocking { resolver.resolveFromScriptSourceAnnotations(listOf(annotation)) }) {
                is ResultWithDiagnostics.Success -> {
                    files += result.value
                    reports += result.reports
                }
                is ResultWithDiagnostics.Failure -> diagnostics += annotation.failure(result.reports, lines)
            }
        }
        // also the dependencies of a snippet failing to compile, which the compiler keeps on its classpath
        classLoader.add(files)
        return Resolution(snippet, files, reports, SnippetDependencies(diagnostics, resolvedVersions, versionWarnings))
    }

    /**
     * This annotation, with the dependency it declares without version, like `org.jsoup:jsoup`,
     * declared in its latest version instead, reported as a warning added to the [diagnostics] and the [versionWarnings],
     * as an LLM might not know its API, and added to the [resolvedVersions],
     * or `null`, with the error added to the [diagnostics], if it has no version.
     *
     * A repository declaration superseded by the lookup of its version is reported as a warning added to the [diagnostics] before,
     * naming the line declaring it, as the warning points to this annotation, while the declaration might be in an earlier snippet.
     * It is also logged, as it is not reported again, while the result of the snippet might be lost,
     * to a fatal error thrown by the compiler or the snippet, or to the cancellation of the caller.
     * It is logged on every path, also when the result reports it, as the resolver cannot tell whether the result reaches the caller,
     * while logging it on the paths losing it would spread over the compilation, the evaluation, and the end of [ScriptSession.execute].
     * The URL is logged and reported with its user info masked, see [withoutUserInfo].
     */
    private fun ScriptSourceAnnotation<*>.withLatestVersion(
        lines: List<String>,
        diagnostics: MutableList<ScriptDiagnostic>,
        versionWarnings: MutableSet<ScriptDiagnostic>,
        resolvedVersions: MutableMap<String, String>
    ): ScriptSourceAnnotation<*>? {
        val dependsOn = annotation as? DependsOn ?: return this
        val coordinates = dependsOn.artifactsCoordinates.single()
        if (coordinates.split(':').size != 2) return this
        val latest = try {
            versionOf(coordinates) { superseded ->
                // the lookup of a version is kept for the session, and the resolution of a snippet for its refinements, so it is reported once
                val message = "Superseded the declaration of the repository '${superseded.coordinates.string.withoutUserInfo()}'" +
                    superseded.location.named(" at ", ",") +
                    " with the credentials refused (401), by its other declaration, so they are no longer sent — " +
                    "declare it again to restore it"
                diagnostics += diagnostic(message, lines, ScriptDiagnostic.Severity.WARNING)
                logger.info { "$name: ${location.named(suffix = ": ")}$message" }
            }
        } catch (e: Exception) {
            val cause = (e.message ?: e.javaClass.name).withRepositoryUrls()
            diagnostics += diagnostic("Unresolved dependency '$coordinates': $cause", lines)
            return null
        }
        resolvedVersions[coordinates] = latest.version
        diagnostics += diagnostic(
            message = if (latest.stable) {
                "Resolved '$coordinates' to its latest stable version '${latest.version}'"
            } else {
                "Resolved '$coordinates' to its latest pre-release version '${latest.version}', as it has no stable one"
            },
            lines = lines,
            severity = ScriptDiagnostic.Severity.WARNING
        ).also { versionWarnings += it }
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
     * A repository declared with wrong credentials, and again with the corrected ones, does not hide the reason of a failed lookup
     * by the `401` of the wrong ones, and its declaration with the wrong ones is superseded,
     * once the corrected ones return the metadata of a dependency, while the wrong ones are refused with `401`,
     * see [MavenVersionLookup.latestVersion]:
     * neither a later version lookup, nor the resolution of any dependency in its version sends them again,
     * as it is dropped from the [declarations], which the [mavenResolver] is derived from,
     * and the dropped declaration is passed to [superseded], to be reported, as a dependency resolved later, which it alone serves,
     * like a private one served to the credentials scoped to it, fails then.
     * Declaring it again with the wrong ones restores it, like any new declaration.
     *
     * Only the lookup of a version supersedes a declaration,
     * so a session declaring every dependency in its version keeps sending the wrong credentials, while resolving each of them.
     */
    private fun versionOf(
        coordinates: String,
        superseded: (Declaration) -> Unit
    ): LatestMavenVersion = latestVersions.getOrPut(coordinates) {
        val (groupId, artifactId) = coordinates.split(':')
        val searched = declarations.map { it.coordinates.string to it.repository }.ifEmpty { listOf(mavenCentral.url to mavenCentral) }
        versionLookup.latestVersion(groupId, artifactId, searched.map { it.second }) { stale ->
            declarations.filter { it.repository == stale }.forEach { declaration ->
                declarations -= declaration
                currentMavenResolver = null
                superseded(declaration)
            }
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

    /** This location named like a frame, `snippet1.kts:2`, between the [prefix] and the [suffix], or empty, if it is `null`. */
    private fun SourceCode.LocationWithId?.named(
        prefix: String = "",
        suffix: String = ""
    ): String = this?.let { location ->
        "$prefix${snippetLine(location.codeLocationId, location.locationInText.start.line)}$suffix"
    } ?: ""

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

    /**
     * Closes the [versionLookup] and the [classLoader], also if closing the former fails,
     * throwing the failure of either, see [releaseAll].
     */
    override fun close() {
        releaseAll(versionLookup::close, classLoader::close)
    }

    class DependencyClassLoader(parent: ClassLoader) : URLClassLoader(emptyArray(), parent) {

        fun add(files: List<File>) {
            val urls = urLs.toSet()
            files.map { it.toURI().toURL() }.filter { it !in urls }.forEach(::addURL)
        }

    }

}

private val logger = KotlinLogging.logger {}

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
/**
 * This message with the user info of each URL, like `alice:token` of `https://alice:token@host/`, masked as `***`,
 * as a URL might carry the credentials of a repository, which neither the log, nor the LLM should see.
 */
internal fun String.withoutUserInfo(): String = replace(urlUserInfo, "$1***@")

/** The user info of a URL, after its scheme and before its host, `alice:token@` of `https://alice:token@host/`. */
private val urlUserInfo = Regex("""\b([a-zA-Z][a-zA-Z0-9+.-]*://)[^/?#@\s']+@""")
