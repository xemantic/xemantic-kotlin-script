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

/** The most exceptions reported, including causes and suppressed ones, as the script might chain them endlessly. */
private const val MAX_REPORTED_EXCEPTIONS = 32

private val unresolvedReference = Regex("Unresolved reference '(.+)'\\.")

/** Reported without a location, e.g. for an object accessing the dependencies, which are the members of the script class. */
private val declarationCapturingScript = Regex("(Object|Enum class|Interface|Nested class) '(.+)' captures the script class instance\\..*")

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
    ): String = diagnostics.describe().joinToString(separator = "\n").trimEnd().ifEmpty {
        // e.g. when all the diagnostics of the failure were ignorable
        "error: Failed without any diagnostic message."
    }

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
        warnings.describe().forEach {
            +'\n'
            +it
        }
    }.trimEnd()

    /** The descriptions of the diagnostics, without the repeated ones, e.g. of the same type used twice in an accessor. */
    private fun List<ScriptDiagnostic>.describe(): List<String> = map { diagnostic ->
        buildString { appendDiagnostic(diagnostic) }
    }.distinct()

    /** The failure recognized in the [source] before compiling it. */
    fun toSourceFailureMessage(): String = source.unmatchedClosingBrace?.let {
        // the brace would close the script block, misplacing all the errors
        toUnmatchedClosingBraceMessage(it)
    } ?: source.conflictingDependencyDeclarations.joinToString(separator = "\n") { declaration ->
        buildString {
            appendPosition(declaration.start)
            +"error: Conflicting declaration of '${declaration.name}', which is also the name of a dependency.\n"
            appendSourceLines(declaration.start, declaration.end)
            +"  hint: '${declaration.name}' is compiled outside of the script body, together with the dependencies - rename it.\n"
        }
    }.trimEnd()

    /** The [references] of the declarations moved out of the script body to other functions, see [ScriptSource.hiddenLocalFunctionCandidates]. */
    fun toHiddenLocalFunctionMessage(
        references: List<LaterDeclaredReference>
    ): String = references.joinToString(separator = "\n") { hidden ->
        val reference = hidden.reference
        val declaration = source.movedDeclarationAt(reference.start)
        val kind = if (hidden.isFunction) "function" else "property"
        buildString {
            appendPosition(reference.start)
            +"error: '${reference.name}' does not refer to the $kind of the script body declared at line ${hidden.line + 1}, which is not visible in '$declaration'.\n"
            appendSourceLines(reference.start, reference.end)
            +"  hint: '$declaration' is compiled outside of the script body, where '${reference.name}' (line ${hidden.line + 1}) is not visible"
            +" - pass it as a parameter, or declare it within '$declaration'.\n"
        }
    }.trimEnd()

    /** The [references] of the script body to the dependencies, preceding the local properties of their names, see [ScriptSource.laterDeclaredDependencyCandidates]. */
    fun toLaterDeclaredDependencyMessage(
        references: List<LaterDeclaredReference>
    ): String = references.joinToString(separator = "\n") { later ->
        val reference = later.reference
        val kind = if (later.isFunction) "function" else "property"
        buildString {
            appendPosition(reference.start)
            +"error: '${reference.name}' refers to the dependency, as the $kind '${reference.name}' of the script body is declared later, at line ${later.line + 1}.\n"
            appendSourceLines(reference.start, reference.end)
            +"  hint: declare the $kind before line ${reference.start.line + 1}, or rename it.\n"
        }
    }.trimEnd()

    private fun toUnmatchedClosingBraceMessage(
        position: ScriptPosition
    ): String = buildString {
        appendPosition(position)
        +"error: Syntax error: Unexpected '}' without matching '{'.\n"
        appendSourceLines(position, position.copy(column = position.column + 1))
    }.trimEnd()

    /**
     * @return `false`, if the [throwable], or any exception it refers to, was omitted,
     *   as too many exceptions were reported already, so no further ones are reported.
     */
    private fun StringBuilder.appendThrowable(
        throwable: Throwable,
        prefix: String,
        indent: String,
        reported: MutableSet<Throwable>
    ): Boolean {
        +indent
        +prefix
        if (throwable in reported) {
            +"[CIRCULAR REFERENCE: "
            appendDescription(throwable)
            +"]\n"
            return true
        }
        if (reported.size >= MAX_REPORTED_EXCEPTIONS) {
            +"[FURTHER EXCEPTIONS OMITTED]\n"
            return false
        }
        reported += throwable
        appendDescription(throwable)
        +'\n'
        // the exception class of the script might override it to contain nulls
        appendFrames(throwable.read(emptyList()) { stackTrace.filterNotNull() }, indent)
        for (suppressed in throwable.read(emptyArray()) { suppressed }) {
            if (!appendThrowable(suppressed, "Suppressed: ", "$indent  ", reported)) return false
        }
        return throwable.read(null) { cause }?.let {
            appendThrowable(it, "Caused by: ", indent, reported)
        } ?: true
    }

    private fun StringBuilder.appendDescription(
        throwable: Throwable
    ) {
        +className(throwable.javaClass)
        val message = try {
            throwable.message
        } catch (e: Throwable) {
            val description = listOfNotNull(e.javaClass.name, e.read(null) { message }).joinToString(": ")
            "[message unavailable: $description]"
        }
        message?.let {
            +": "
            +it
        }
    }

    private fun className(
        type: Class<*>
    ): String {
        val name = type.name
        return when {
            // the synthetic name, like `Script$result$1$fail$1`, would not tell anything
            type.isAnonymousClass -> "<anonymous ${className(type.superclass)}>"
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
        stackTrace: List<StackTraceElement>,
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
                    location = "${element.className}.${element.methodName}(${sourceOf(element)})",
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

    /** Like in [StackTraceElement.toString], e.g. `Native Method`, instead of the negative line number. */
    private fun sourceOf(
        frame: StackTraceElement
    ): String {
        val fileName = frame.fileName
        return when {
            frame.isNativeMethod -> "Native Method"
            fileName == null -> "Unknown Source"
            frame.lineNumber >= 0 -> "$fileName:${frame.lineNumber}"
            else -> fileName
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
        // the lambdas are named like `invokeSuspend$lambda$0`, unlike the functions named `lambda`
        val isLambda = methodParts.zipWithNext().any { (name, index) ->
            name == "lambda" && index.isNotEmpty() && index.all { it.isDigit() }
        }
        return when {
            isLambda || (type != null && type.all { it.isDigit() }) -> "<lambda>"
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

    /**
     * In the format of kotlinc, `script.kts:line:column: severity: message`, followed by the source lines,
     * or `dependency 'name': severity: message`, if reported in the accessor of the dependency.
     */
    private fun StringBuilder.appendDiagnostic(
        diagnostic: ScriptDiagnostic
    ) {
        val location = diagnostic.location
        location?.let { source.preambleDependencyAt(it.start.line) }?.let { dependency ->
            +"dependency '$dependency': "
            appendSeverityAndMessage(diagnostic)
            return
        }
        val capturing = declarationCapturingScript.matchEntire(diagnostic.message)?.groupValues
        val capturingDeclaration = capturing?.get(2)
        val range = if (location != null) {
            val start = source.toScriptPosition(location.start.line, location.start.col)
            val end = location.end?.let {
                source.toScriptPosition(it.line, it.col)
            }?.takeIf {
                (it.line > start.line) || (it.line == start.line && it.column >= start.column)
            } ?: start
            start to end
        } else {
            capturingDeclaration?.let { source.movedDeclarationName(it) }
        }
        range?.let { appendPosition(it.first) }
        appendSeverityAndMessage(diagnostic)
        if (range == null) return
        val (start, end) = range
        appendSourceLines(start, end)
        unresolvedReference.matchEntire(diagnostic.message)?.let { match ->
            appendHint(
                name = match.groupValues[1],
                position = start,
                // unlike a property, a function of the same name is called, also with a trailing lambda
                preferFunction = source.scriptLines[end.line].drop(end.column).trimStart().let {
                    it.startsWith('(') || it.startsWith('{')
                }
            )
        }
        if (capturing != null) {
            val kind = capturing[1].lowercase()
            val article = if (kind[0] in "aeiou") "an" else "a"
            val alternative = if (kind == "object") "declare it as a class, or " else ""
            +"  hint: '$capturingDeclaration' is compiled outside of the script body, where $article $kind cannot access the dependencies"
            +" - ${alternative}pass the dependencies as parameters.\n"
        }
    }

    private fun StringBuilder.appendSeverityAndMessage(
        diagnostic: ScriptDiagnostic
    ) {
        +diagnostic.severity.name.lowercase()
        +": "
        diagnostic.message.trimEnd().lines().joinTo(this, "\n  ")
        +'\n'
        // e.g. of the failure to instantiate the script class
        diagnostic.exception?.let {
            appendThrowable(it, "Caused by: ", "  ", Collections.newSetFromMap(IdentityHashMap()))
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
     * and the local declarations of the script body are not visible before their declaration.
     */
    private fun StringBuilder.appendHint(
        name: String,
        position: ScriptPosition,
        preferFunction: Boolean = false
    ) {
        val (line, kind) = (if (preferFunction) source.bodyDeclarationLine(name, DeclarationKind.FUNCTION) else null)
            ?: source.bodyDeclarationLine(name) ?: return
        val declaration = source.movedDeclarationAt(position)
        if (declaration != null) {
            +"  hint: '$declaration' is compiled outside of the script body, where '$name' (line ${line + 1}) is not visible"
            +" - pass it as a parameter, or declare it within '$declaration'.\n"
        } else if (line > position.line) {
            val declarations = when (kind) {
                CLASS -> "classes"
                FUNCTION -> "functions"
                else -> "properties"
            }
            +"  hint: '$name' is declared at line ${line + 1}, while the $declarations of the script body are visible only after their declaration"
            +" - move it before line ${position.line + 1}.\n"
        }
    }

}

/**
 * The property of the [Throwable], or the [fallback], if reading it throws,
 * as the exception class of the script might override it.
 */
internal inline fun <T> Throwable.read(
    fallback: T,
    property: Throwable.() -> T
): T = try {
    property()
} catch (_: Throwable) {
    fallback
}
