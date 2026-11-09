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

import java.util.Collections
import java.util.IdentityHashMap
import kotlin.script.experimental.api.ScriptDiagnostic

/** The longest sequence of frames collapsed when repeated, like of a mutual recursion. */
private const val MAX_REPEATED_FRAMES = 16

/** The number of the exceptions reported at most, of the causes and the suppressed ones. */
private const val MAX_REPORTED_EXCEPTIONS = 32

/**
 * The name of a class of a snippet, qualified by the snippet class, like `Snippet3.Color` or `Snippet3$Box`,
 * also as the element class of an array, like `[LSnippet3$Box;`.
 */
private val snippetQualifiedName = Regex("(?:(?<=\\[L)|(?<![\\p{L}\\p{N}_.$]))Snippet\\d+[.$](?=[\\p{L}_`])")

private val snippetClassName = Regex("Snippet\\d+")

private val unresolvedReference = Regex("Unresolved reference '(.+)'\\.")

/** The mangled name of a method taking or returning a value class, like `getTimeout-UwyO8pc`. */
private val mangledMethod = Regex("[\\p{L}_][\\p{L}\\p{N}_]*-[\\p{L}\\p{N}_]{7}\\(")

/** The method evaluating the body of a snippet. */
private const val SNIPPET_BODY_METHOD = "\$\$eval"

/**
 * Describes the failures of the snippets of a session in a form an LLM can act on,
 * pointing to the offending lines of the snippets.
 *
 * @param snippets the lines of the snippets by their names, like `snippet3.kts`.
 * @param snippetClassNames the simple names of the classes declared by the evaluated snippets.
 * @param uninitializedProperties the properties of the snippets which failed in the evaluation, `null` then.
 */
internal class ScriptErrorReporter(
    private val snippets: Map<String, List<String>>,
    private val snippetClassNames: Set<String> = emptySet(),
    private val uninitializedProperties: UninitializedProperties = UninitializedProperties()
) {

    /**
     * The diagnostics in the format of kotlinc, `snippet3.kts:line:column: severity: message`,
     * followed by the source lines with the offending fragment marked with `<error>` tags.
     */
    fun describe(
        diagnostics: List<ScriptDiagnostic>
    ): String = diagnostics.reportable().map { describe(it) }.distinct().joinToString("\n").trimEnd().ifEmpty {
        "error: Failed without any diagnostic message."
    }

    /** The warnings of the [diagnostics], or `null`, if there are none. */
    fun warnings(
        diagnostics: List<ScriptDiagnostic>
    ): String? = diagnostics.filter {
        it.severity == ScriptDiagnostic.Severity.WARNING
    }.takeIf { it.isNotEmpty() }?.let { describe(it) }

    fun describe(
        throwable: Throwable,
        warnings: List<ScriptDiagnostic> = emptyList()
    ): String = buildString {
        appendThrowable(
            throwable = throwable,
            prefix = "",
            indent = "",
            reported = Collections.newSetFromMap(IdentityHashMap())
        )
        warnings(warnings)?.let {
            append('\n')
            append(it)
        }
    }.trimEnd()

    private fun List<ScriptDiagnostic>.reportable() = filter {
        it.severity >= ScriptDiagnostic.Severity.WARNING
    }

    private fun describe(
        diagnostic: ScriptDiagnostic
    ): String = buildString {
        val location = diagnostic.location
        val lines = diagnostic.sourcePath?.let { path -> snippets[path.substringAfterLast('/')] }
        val name = diagnostic.sourcePath?.substringAfterLast('/')
        if (location != null && lines != null && name != null) {
            append("$name:${location.start.line}:${location.start.col}: ")
        }
        append(diagnostic.severity.name.lowercase())
        append(": ")
        val exception = diagnostic.exception
        if (exception != null && diagnostic.severity >= ScriptDiagnostic.Severity.ERROR) {
            append("The compiler failed internally: ")
            appendThrowable(exception, "", "  ", Collections.newSetFromMap(IdentityHashMap()))
        } else {
            append(diagnostic.message.trimEnd().lines().joinToString("\n  "))
            append('\n')
        }
        if (location != null && lines != null) {
            val startLine = (location.start.line - 1).coerceIn(lines.indices)
            val startColumn = location.start.col - 1
            val end = location.end
            val (endLine, endColumn) = if (end != null && end.line - 1 >= startLine) {
                (end.line - 1).coerceIn(lines.indices) to end.col - 1
            } else {
                startLine to startColumn
            }
            (startLine..endLine).forEach { line ->
                val sourceLine = StringBuilder(lines[line])
                if (line == endLine) {
                    sourceLine.insert(endColumn.coerceIn(0, sourceLine.length), "</error>")
                }
                if (line == startLine) {
                    sourceLine.insert(startColumn.coerceIn(0, sourceLine.length), "<error>")
                }
                append("  | $sourceLine\n")
            }
        }
        unresolvedReference.matchEntire(diagnostic.message)?.groupValues?.get(1)?.takeIf { it in snippetClassNames }?.let { name ->
            append("  hint: '$name' is declared in an earlier snippet, which the K2 REPL compiler does not resolve within a declaration of an inferred type,")
            append(" like `fun f(x: Any) = x is $name` - declare the type, like `fun f(x: Any): Boolean = x is $name`.\n")
        }
    }

    /**
     * @param prefix the lead of the first line, with its indent, like `  Caused by: `, as the caller might continue a line.
     * @param indent the indent of the following lines, the frames, the suppressed exceptions and the causes.
     */
    private fun StringBuilder.appendThrowable(
        throwable: Throwable,
        prefix: String,
        indent: String,
        reported: MutableSet<Throwable>
    ): Boolean {
        append(prefix)
        if (throwable in reported) {
            append("[CIRCULAR REFERENCE: ")
            appendDescription(throwable)
            append("]\n")
            return true
        }
        if (reported.size >= MAX_REPORTED_EXCEPTIONS) {
            append("[FURTHER EXCEPTIONS OMITTED]\n")
            return false
        }
        reported += throwable
        appendDescription(throwable)
        append('\n')
        if (throwable is NoSuchMethodError && throwable.read(null) { message }?.let { mangledMethod.containsMatchIn(it) } == true) {
            append("$indent  hint: a property or a function of a value class type, like Duration, declared in an earlier snippet,")
            append(" cannot be called from another snippet, as the K2 REPL compiler mangles its name only when calling it")
            append(" - declare it in the same snippet, or wrap the value in a regular class.\n")
        }
        // the exception class of a snippet might override it to contain nulls
        val stackTrace = throwable.read(emptyList()) { stackTrace.filterNotNull() }
        if (throwable is NullPointerException) {
            uninitializedProperties.readBy(throwable, stackTrace)?.let { property ->
                append("$indent  hint: '${property.name}' is declared in ${property.snippet}, which failed in the evaluation,")
                append(" so it might be left uninitialized, null despite its type")
                append(" - declare it again, together with the declarations reading it,")
                stackTrace.firstOrNull { it.fileName in snippets && !property.isGetter(it) }?.let { frame ->
                    frame.declarationName()?.let { append(" like '$it' of ${frame.fileName},") }
                }
                append(" as they keep reading the uninitialized one.\n")
            }
        }
        appendFrames(stackTrace, indent)
        for (suppressed in throwable.read(emptyArray()) { suppressed }) {
            if (!appendThrowable(suppressed, "$indent  Suppressed: ", "$indent  ", reported)) return false
        }
        return throwable.read(null) { cause }?.let {
            appendThrowable(it, "${indent}Caused by: ", indent, reported)
        } ?: true
    }

    private fun StringBuilder.appendDescription(
        throwable: Throwable
    ) {
        append(throwable.javaClass.name.withoutSnippetClassName().replace('$', '.'))
        val message = try {
            throwable.message
        } catch (e: Throwable) {
            "[message unavailable: ${e.javaClass.name}]"
        }
        message?.let {
            append(": ")
            append(it.withoutSnippetClassName())
        }
    }

    private class Frame(
        val location: String,
        val sourceLine: String?
    )

    /**
     * The frames of the snippets, preceded by the frame throwing the exception, if it is not one of them,
     * like `java.lang.Class.getDeclaredField(Class.java:2794)`,
     * with the repeated sequences of frames, like of a recursion, collapsed.
     */
    private fun StringBuilder.appendFrames(
        stackTrace: List<StackTraceElement>,
        indent: String
    ) {
        val frames = stackTrace.withIndex().mapNotNull { (index, element) ->
            val lines = snippets[element.fileName]
            val line = element.lineNumber.takeIf { lines != null && it in 1..lines.size }
            when {
                line != null -> Frame(
                    location = "${element.functionName()}(${element.fileName}:$line)",
                    sourceLine = lines!![line - 1]
                )
                // like a static initializer of an object declared in a snippet
                lines != null -> Frame(
                    location = "${element.functionName()}(${element.fileName})",
                    sourceLine = null
                )
                index == 0 -> Frame(
                    location = "${element.className}.${element.methodName}(${element.source})",
                    sourceLine = null
                )
                else -> null
            }
        }
        var index = 0
        while (index < frames.size) {
            val (length, repetitions) = repetitionAt(frames, index)
            frames.subList(index, index + length).forEach { frame ->
                append("$indent  at ${frame.location}\n")
                frame.sourceLine?.let {
                    append("$indent  | $it\n")
                }
            }
            if (repetitions > 1) {
                val repeated = if (length == 1) "frame" else "$length frames"
                append("$indent  ... (the $repeated above repeated ${repetitions - 1} more times)\n")
            }
            index += length * repetitions
        }
    }

    /**
     * The name of the function of this snippet frame, in terms of the snippet,
     * e.g. `Checker.check`, `<snippet body>`, or `<lambda>`.
     */
    private fun StackTraceElement.functionName(): String {
        val owner = className.split('$').let { segments ->
            if (snippetClassName.matches(segments.first())) segments.drop(1) else segments
        }.filter { it.isNotEmpty() }
        val methodParts = methodName.removeSuffix("\$default").split('$').filter { it.isNotEmpty() }
        val isLambda = methodParts.zipWithNext().any { (name, index) ->
            name == "lambda" && index.all { it.isDigit() }
        } || owner.any { segment -> segment.all { it.isDigit() } }
        val type = owner.lastOrNull()
        return when {
            isLambda -> "<lambda>"
            methodName == SNIPPET_BODY_METHOD && type == null -> "<snippet body>"
            else -> listOfNotNull(type, methodParts.lastOrNull() ?: methodName).joinToString(".")
        }
    }

    /**
     * The name of the declaration of this snippet frame, like `greet`, `Checker.check`, or `Config` of its initializer,
     * or `null` for the body of a snippet, or a lambda, which is not a declaration to redeclare.
     */
    private fun StackTraceElement.declarationName(): String? = functionName().takeIf {
        it != "<snippet body>" && it != "<lambda>"
    }?.removeSuffix(".<init>")?.removeSuffix(".<clinit>")

    /**
     * The length of the sequence of frames starting at the [index], and the number of its consecutive repetitions,
     * choosing the sequence which collapses the most frames.
     */
    private fun repetitionAt(
        frames: List<Frame>,
        index: Int
    ): Pair<Int, Int> {
        var best = 1 to 1
        for (length in 1..minOf(MAX_REPEATED_FRAMES, (frames.size - index) / 2)) {
            var repetitions = 1
            while (
                index + (repetitions + 1) * length <= frames.size
                && (0 until length).all {
                    frames[index + repetitions * length + it].location == frames[index + it].location
                }
            ) {
                repetitions++
            }
            if (length * (repetitions - 1) > best.first * (best.second - 1)) {
                best = length to repetitions
            }
        }
        return best
    }

}

/** The names of the classes of the snippets without the snippet class, like `Color.BLUE` instead of `Snippet3.Color.BLUE`. */
private fun String.withoutSnippetClassName(): String = replace(snippetQualifiedName, "")

/** Like in [StackTraceElement.toString], e.g. `Native Method`, instead of the negative line number. */
private val StackTraceElement.source: String get() = when {
    isNativeMethod -> "Native Method"
    fileName == null -> "Unknown Source"
    lineNumber >= 0 -> "$fileName:$lineNumber"
    else -> fileName
}

/**
 * The property of the [Throwable], or the [fallback], if reading it throws,
 * as the exception class of a snippet might override it.
 */
internal inline fun <T> Throwable.read(
    fallback: T,
    property: Throwable.() -> T
): T = try {
    property()
} catch (_: Throwable) {
    fallback
}
