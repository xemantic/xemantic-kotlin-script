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

import com.xemantic.kotlin.core.text.unaryPlus
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.script.experimental.api.ScriptDiagnostic

/** The longest sequence of frames recognized as repeated, e.g. in mutual recursion. */
private const val MAX_REPEATED_FRAMES = 16

private val unresolvedReference = Regex("Unresolved reference '(.+)'\\.")

/** Reported without a location, for an object accessing the dependencies, which are the members of the script class. */
private val objectCapturingScript = Regex("Object '(.+)' captures the script class instance\\..*")

internal fun Iterable<ScriptDiagnostic>.filterNonIgnorable() = filterNot { diagnostic ->
    diagnostic.severity == ScriptDiagnostic.Severity.DEBUG && listOf(
        "Using JDK home inferred from java.home",
        "Loading modules:"
    ).any { diagnostic.message.startsWith(it) }
}

/**
 * Describes failures in terms of the original script.
 *
 * @param scriptClassName the binary name of the compiled script class,
 *   to strip it from the names of the classes declared in the script.
 * @param blockClassName the binary name of the class of the script block,
 *   to tell the frames of the script body.
 */
internal class ScriptErrorReporter(
    private val source: ScriptSource,
    private val scriptClassName: String? = null,
    private val blockClassName: String? = null
) {

    fun toFailureMessage(
        diagnostics: List<ScriptDiagnostic>
    ): String = buildString {
        diagnostics.forEachIndexed { index, diagnostic ->
            if (index > 0) +'\n'
            appendDiagnostic(diagnostic)
        }
    }.trimEnd()

    fun toFailureMessage(
        throwable: Throwable,
        warnings: List<ScriptDiagnostic>
    ): String = buildString {
        appendThrowable(
            throwable = throwable,
            prefix = "",
            indent = "",
            reported = Collections.newSetFromMap(IdentityHashMap())
        )
        warnings.forEach {
            +'\n'
            appendDiagnostic(it)
        }
    }.trimEnd()

    fun toUnmatchedClosingBraceMessage(
        position: ScriptPosition
    ): String = buildString {
        appendPosition(position)
        +"error: Syntax error: Unexpected '}' without matching '{'.\n"
        appendSourceLines(position, position.copy(column = position.column + 1))
    }.trimEnd()

    private fun StringBuilder.appendThrowable(
        throwable: Throwable,
        prefix: String,
        indent: String,
        reported: MutableSet<Throwable>
    ) {
        +indent
        +prefix
        if (!reported.add(throwable)) {
            +"[CIRCULAR REFERENCE: "
            appendDescription(throwable)
            +"]\n"
            return
        }
        appendDescription(throwable)
        +'\n'
        appendFrames(throwable.stackTrace, indent)
        throwable.suppressed.forEach {
            appendThrowable(it, "Suppressed: ", "$indent  ", reported)
        }
        throwable.cause?.let {
            appendThrowable(it, "Caused by: ", indent, reported)
        }
    }

    private fun StringBuilder.appendDescription(
        throwable: Throwable
    ) {
        +className(throwable.javaClass)
        throwable.message?.let {
            +": "
            +it
        }
    }

    private fun className(
        type: Class<*>
    ): String {
        val name = type.name
        return when {
            // e.g. the classes declared in the script body
            type.isLocalClass -> type.simpleName
            scriptClassName != null && name.startsWith("$scriptClassName$") ->
                name.removePrefix("$scriptClassName$").replace('$', '.')
            else -> name
        }
    }

    private class Frame(
        val location: String,
        val sourceLine: String?
    )

    private fun StringBuilder.appendFrames(
        stackTrace: Array<StackTraceElement>,
        indent: String
    ) {
        val frames = stackTrace.withIndex().mapNotNull { (index, element) ->
            // frames of other scripts, e.g. the one executing this script, are not this script's;
            // synthetic frames of the script block carry no line number
            val scriptLine = if (element.fileName == source.locationId && element.lineNumber > 0) {
                source.toScriptLine(element.lineNumber)
            } else {
                null
            }
            when {
                scriptLine != null -> Frame(
                    location = "${functionName(element)}(script.kts:${scriptLine + 1})",
                    sourceLine = source.scriptLines[scriptLine]
                )
                index == 0 -> Frame(
                    location = "${element.className}.${element.methodName}(${element.fileName}:${element.lineNumber})",
                    sourceLine = null
                )
                else -> null
            }
        }
        var index = 0
        while (index < frames.size) {
            val (length, repetitions) = repetitionAt(frames, index)
            frames.subList(index, index + length).forEach { frame ->
                +"$indent  at ${frame.location}\n"
                frame.sourceLine?.let {
                    +"$indent  | $it\n"
                }
            }
            if (repetitions > 1) {
                val repeated = if (length == 1) "frame" else "$length frames"
                +"$indent  ... (the $repeated above repeated ${repetitions - 1} more times)\n"
            }
            index += length * repetitions
        }
    }

    /**
     * The name of the function of the script [frame], in terms of the script,
     * e.g. `Checker.check`, `<script body>` or `<lambda>`.
     */
    private fun functionName(
        frame: StackTraceElement
    ): String {
        val className = frame.className
        val owner = when {
            className == blockClassName || className == scriptClassName -> ""
            blockClassName != null && className.startsWith("$blockClassName$") ->
                className.removePrefix("$blockClassName$")
            scriptClassName != null && className.startsWith("$scriptClassName$") ->
                className.removePrefix("$scriptClassName$")
            else -> className
        }
        // the local functions are named after their enclosing functions, like `invokeSuspend$outer$inner`
        val methodParts = frame.methodName.removeSuffix("\$default").split('$')
        val type = owner.split('$').lastOrNull { it.isNotEmpty() }
        return when {
            "lambda" in methodParts || (type != null && type.all { it.isDigit() }) -> "<lambda>"
            type == null && className == blockClassName && methodParts == listOf("invokeSuspend") -> "<script body>"
            type == null -> methodParts.last()
            else -> "$type.${methodParts.last()}"
        }
    }

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

    /** In the format of kotlinc, `script.kts:line:column: severity: message`, followed by the source lines. */
    private fun StringBuilder.appendDiagnostic(
        diagnostic: ScriptDiagnostic
    ) {
        val location = diagnostic.location
        val capturingObject = objectCapturingScript.matchEntire(diagnostic.message)?.groupValues[1]
        val range = if (location != null) {
            val start = source.toScriptPosition(location.start.line, location.start.col)
            val end = location.end?.let {
                source.toScriptPosition(it.line, it.col)
            }?.takeIf {
                (it.line > start.line) || (it.line == start.line && it.column >= start.column)
            } ?: start
            start to end
        } else {
            capturingObject?.let { source.movedDeclarationName(it) }
        }
        range?.let { appendPosition(it.first) }
        +diagnostic.severity.name.lowercase()
        +": "
        diagnostic.message.trimEnd().lines().joinTo(this, "\n  ")
        +'\n'
        if (range == null) return
        val (start, end) = range
        appendSourceLines(start, end)
        unresolvedReference.matchEntire(diagnostic.message)?.let { match ->
            appendHint(name = match.groupValues[1], position = start)
        }
        if (capturingObject != null) {
            +"  hint: '$capturingObject' is compiled outside of the script body, where an object cannot access the dependencies"
            +" - declare it as a class, or pass the dependencies as parameters.\n"
        }
    }

    private fun StringBuilder.appendPosition(
        position: ScriptPosition
    ) {
        +"script.kts:${position.line + 1}:${position.column + 1}: "
    }

    private fun StringBuilder.appendSourceLines(
        start: ScriptPosition,
        end: ScriptPosition
    ) {
        (start.line..end.line).forEach { line ->
            val scriptLine = StringBuilder(source.scriptLines[line])
            if (line == end.line) {
                scriptLine.insert(end.column.coerceIn(0, scriptLine.length), "</error>")
            }
            if (line == start.line) {
                scriptLine.insert(start.column.coerceIn(0, scriptLine.length), "<error>")
            }
            +"  | $scriptLine\n"
        }
    }

    /**
     * Unlike in a regular script, the declarations moved out of the script body cannot see its local declarations,
     * and the local functions of the script body are not visible before their declaration.
     */
    private fun StringBuilder.appendHint(
        name: String,
        position: ScriptPosition
    ) {
        val line = source.bodyDeclarationLine(name) ?: return
        val declaration = source.movedDeclarationAt(position)
        if (declaration != null) {
            +"  hint: '$declaration' is compiled outside of the script body, where '$name' (line ${line + 1}) is not visible"
            +" - pass it as a parameter, or declare it within '$declaration'.\n"
        } else if (line > position.line) {
            +"  hint: '$name' is declared at line ${line + 1}, while the functions of the script body are visible only after their declaration"
            +" - move it before line ${position.line + 1}.\n"
        }
    }

}
