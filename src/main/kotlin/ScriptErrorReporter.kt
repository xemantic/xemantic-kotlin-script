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
import kotlin.script.experimental.api.ResultValue
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

/** An unresolved reference, with the receiver of an extension, if the reference is qualified, like `"a".shout()`. */
private val unresolvedReference = Regex("Unresolved reference '(.+?)'(?: on receiver of type '(.+)')?\\.")

/** The mangled name of a method taking or returning a value class, like `getTimeout-UwyO8pc`. */
private val mangledMethod = Regex("[\\p{L}_][\\p{L}\\p{N}_]*-[\\p{L}\\p{N}_]{7}\\(")

/** The method evaluating the body of a snippet. */
private const val SNIPPET_BODY_METHOD = "\$\$eval"

/** The most properties named in the hint at the ones left uninitialized by a snippet, the first ones declared. */
private const val MAX_NAMED_UNINITIALIZED = 5

/**
 * Describes the failures of the snippets of a session in a form an LLM can act on,
 * pointing to the offending lines of the snippets.
 *
 * @param snippets the lines of the snippets by their names, like `snippet3.kts`.
 * @param snippetDeclarations the declarations of the evaluated snippets, also of the provided properties.
 * @param uninitializedProperties the properties of the snippets which failed in the evaluation, `null` then.
 * @param inlinedFrames the frames of the code inlined into the snippets, of the lines beyond the end of a snippet, or of an inlined lambda.
 * @param propertyAccessors the properties of the accessors of the classes of the snippets, named in the hints by the property.
 */
internal class ScriptErrorReporter(
    private val snippets: Map<String, List<String>>,
    private val snippetDeclarations: SnippetDeclarations = SnippetDeclarations(),
    private val uninitializedProperties: UninitializedProperties = UninitializedProperties(),
    private val inlinedFrames: InlinedFrames = InlinedFrames(snippets),
    private val propertyAccessors: PropertyAccessors = PropertyAccessors()
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

    /**
     * @param uninitialized the properties the snippet throwing the [throwable] left uninitialized, see [UninitializedProperties.keep].
     */
    fun describe(
        throwable: Throwable,
        warnings: List<ScriptDiagnostic> = emptyList(),
        uninitialized: List<UninitializedProperty> = emptyList()
    ): String = buildString {
        appendThrowable(
            throwable = throwable,
            prefix = "",
            indent = "",
            reported = Collections.newSetFromMap(IdentityHashMap()),
            uninitialized = uninitialized
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
            append(diagnostic.message.indentContinuationLines("  "))
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
        unresolvedReference.matchEntire(diagnostic.message)?.let { match ->
            appendUnresolvedEarlierDeclarationHint(match.groupValues[1], receiverType = match.groups[2]?.value)
        }
    }

    /**
     * The hint at the K2 REPL compiler not resolving a class, a property or a function of this [name], declared by an earlier snippet,
     * within a declaration of an inferred type used in the same snippet, which resolves once its type is declared,
     * also an extension, called on the receiver of the [receiverType] of the message, if its receiver accepts it,
     * or on an implicit receiver, hinted at conditionally, as the receivers in scope are not known.
     * A declaration of a snippet which failed to compile is not kept, so not hinted at, as it stays unresolved also then,
     * nor an inaccessible one, see [SnippetDeclarations].
     */
    private fun StringBuilder.appendUnresolvedEarlierDeclarationHint(name: String, receiverType: String?) {
        if (receiverType != null) {
            val reference = when {
                snippetDeclarations.extensionPropertyReceivers(name).any { it.accepts(receiverType) } -> "receiver.$name"
                snippetDeclarations.extensionFunctionReceivers(name).any { it.accepts(receiverType) } -> "receiver.$name(...)"
                else -> return
            }
            appendDeclareTypeHint(name, "fun f() = $reference.toString()", "fun f(): String = $reference.toString()")
            return
        }
        if (snippetDeclarations.isClass(name)) {
            append("  hint: '$name' is declared in an earlier snippet, which the K2 REPL compiler does not resolve within a declaration of an inferred type,")
            append(" like `fun f(x: Any) = x is $name` - declare the type, like `fun f(x: Any): Boolean = x is $name`.\n")
            return
        }
        when {
            snippetDeclarations.isProperty(name) -> {
                appendDeclareTypeHint(name, "fun f() = $name.toString()", "fun f(): String = $name.toString()")
                return
            }
            snippetDeclarations.isFunction(name) -> {
                appendDeclareTypeHint(name, "fun f() = $name(...).toString()", "fun f(): String = $name(...).toString()")
                return
            }
        }
        val propertyReceivers = snippetDeclarations.extensionPropertyReceivers(name)
        val (receivers, reference) = if (propertyReceivers.isNotEmpty()) {
            propertyReceivers to name
        } else {
            snippetDeclarations.extensionFunctionReceivers(name) to "$name(...)"
        }
        val receiver = receivers.firstOrNull() ?: return
        val ofType = if (receivers.size == 1) "of this type" else "of one of these types"
        append("  hint: '$name' is an extension of ${receivers.joinToString(" or ")} declared in an earlier snippet")
        append(" - if it is called on an implicit receiver $ofType, the K2 REPL compiler does not resolve it within a declaration of an inferred type used in the same snippet,")
        append(" like `fun $receiver.f() = $reference.toString()` - declare the type, like `fun $receiver.f(): String = $reference.toString()`.\n")
    }

    private fun StringBuilder.appendDeclareTypeHint(name: String, inferred: String, declared: String) {
        append("  hint: '$name' is declared in an earlier snippet, which the K2 REPL compiler does not resolve within a declaration of an inferred type used in the same snippet,")
        append(" like `$inferred` - declare the type, like `$declared`.\n")
    }

    /**
     * @param prefix the lead of the first line, with its indent, like `  Caused by: `, as the caller might continue a line.
     * @param indent the indent of the following lines, the frames, the suppressed exceptions and the causes.
     * @param uninitialized the properties left uninitialized by the snippet throwing the [throwable], hinted at after it.
     */
    private fun StringBuilder.appendThrowable(
        throwable: Throwable,
        prefix: String,
        indent: String,
        reported: MutableSet<Throwable>,
        uninitialized: List<UninitializedProperty> = emptyList()
    ): Boolean {
        append(prefix)
        if (throwable in reported) {
            append("[CIRCULAR REFERENCE: ")
            appendDescription(throwable, indent)
            append("]\n")
            return true
        }
        if (reported.size >= MAX_REPORTED_EXCEPTIONS) {
            append("[FURTHER EXCEPTIONS OMITTED]\n")
            return false
        }
        reported += throwable
        appendDescription(throwable, indent)
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
                appendUninitializedReadHint(property, stackTrace, indent)
            }
        }
        appendUninitializedHint(uninitialized, indent)
        appendFrames(stackTrace, indent)
        for (suppressed in throwable.read(emptyArray()) { suppressed }) {
            if (!appendThrowable(suppressed, "$indent  Suppressed: ", "$indent  ", reported)) return false
        }
        return throwable.read(null) { cause }?.let {
            appendThrowable(it, "${indent}Caused by: ", indent, reported)
        } ?: true
    }

    /**
     * The hint at the [property] of an earlier snippet, read as `null` by the exception thrown at the [stackTrace],
     * naming the declaration reading it, the first snippet frame of a declaration, like `greet`, so not a lambda, or the body of a snippet,
     * and the declarations calling it, which keep calling the one reading the uninitialized property, even after redeclaring both,
     * while a `var` is assigned instead, which all of them see.
     */
    private fun StringBuilder.appendUninitializedReadHint(
        property: UninitializedProperty,
        stackTrace: List<StackTraceElement>,
        indent: String
    ) {
        val readers = stackTrace.filter {
            it.fileName in snippets && !property.isGetter(it)
        }.mapNotNull { frame ->
            frame.declarationName()?.let { "'$it' of ${frame.fileName}" }
        }.distinct()
        append("$indent  hint: '${property.name}' is declared in ${property.snippet}, which failed in the evaluation,")
        append(" so it might be left uninitialized, null despite its type - ")
        if (property.mutable) {
            append("assign it, instead of declaring it again, as the declarations reading it")
            readers.firstOrNull()?.let { append(", like $it,") }
            append(" keep reading the same one.\n")
        } else {
            append("declare it again, together with the declarations reading it,")
            readers.firstOrNull()?.let { append(" like $it,") }
            readers.drop(1).takeIf { it.isNotEmpty() }?.let { append(" and the ones calling them, like ${it.enumerated()},") }
            append(" as they keep reading the uninitialized one.\n")
        }
    }

    /**
     * The hints at the properties left [uninitialized] by the snippet which failed, the `val`s, and then the `var`s,
     * which are assigned instead of declaring them again, naming the first [MAX_NAMED_UNINITIALIZED] of each, and counting the rest.
     */
    private fun StringBuilder.appendUninitializedHint(
        uninitialized: List<UninitializedProperty>,
        indent: String
    ) {
        val (vals, vars) = uninitialized.partition { !it.mutable }
        listOf(vals, vars).filter { it.isNotEmpty() }.forEach { properties ->
            val plural = properties.size > 1
            val them = if (plural) "them" else "it"
            val one = if (plural) "ones" else "one"
            val named = properties.take(MAX_NAMED_UNINITIALIZED).map { "'${it.name}'" }
            val more = properties.size - named.size
            append("$indent  hint: ${(named + listOfNotNull("$more more".takeIf { more > 0 })).enumerated()} ${if (plural) "are" else "is"} left uninitialized,")
            append(" null despite ${if (plural) "their" else "its"} type, as the snippet failed before initializing $them,")
            append(" while ${if (plural) "they stay" else "it stays"} declared - ")
            if (properties.first().mutable) {
                append("assign $them, instead of declaring $them again, as the declarations reading $them keep reading the same $one.\n")
            } else {
                append("declare $them again, together with the declarations reading $them, as they keep reading the uninitialized $one.\n")
            }
        }
    }

    /** Like `'a', 'b' and 'c'`. */
    private fun List<String>.enumerated(): String =
        if (size <= 1) joinToString() else dropLast(1).joinToString() + " and " + last()

    /**
     * The class and the message of the [throwable], with the later lines of a multi-line message
     * indented like the frames, by the [indent] of the throwable, so that they read as a part of the headline
     * also within a cause or a suppressed exception, and do not break the structure of the report.
     */
    private fun StringBuilder.appendDescription(
        throwable: Throwable,
        indent: String
    ) {
        append(throwable.javaClass.name.withoutSnippetClassName().replace('$', '.'))
        val message = try {
            throwable.message
        } catch (e: Throwable) {
            "[message unavailable: ${e.javaClass.name}]"
        }
        message?.let {
            append(": ")
            append(it.withoutSnippetClassName().indentContinuationLines("$indent  "))
        }
    }

    private class Frame(
        val location: String,
        val sourceLine: String?
    )

    /**
     * The frames of the snippets, preceded by the frame throwing the exception, if it is not one of them,
     * like `java.lang.Class.getDeclaredField(Class.java:2794)`,
     * with a frame of inlined code, its line beyond the end of the snippet, or within an inlined lambda,
     * resolved to the frames of the inlined function and of its call site, see [InlinedFrames],
     * and with the repeated sequences of frames, like of a recursion, collapsed.
     */
    private fun StringBuilder.appendFrames(
        stackTrace: List<StackTraceElement>,
        indent: String
    ) {
        val frames = stackTrace.flatMap { element ->
            inlinedFrames.resolve(element) ?: listOf(element)
        }.withIndex().mapNotNull { (index, element) ->
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
     * the property of an accessor, like `t` of `getT`, see [PropertyAccessors],
     * or `null` for the body of a snippet, or a lambda, which is not a declaration to redeclare.
     */
    private fun StackTraceElement.declarationName(): String? = functionName().takeIf {
        it != "<snippet body>" && it != "<lambda>"
    }?.removeSuffix(".<init>")?.removeSuffix(".<clinit>")?.let { name ->
        val property = propertyAccessors.propertyOf(this) ?: return name
        name.substring(0, name.lastIndexOf('.') + 1) + property
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

}

/**
 * The [ScriptResult] of a snippet from the [result] of its evaluation,
 * which is `null` if the evaluation failed without one, reported by the [evaluationReports] then,
 * like [ResultValue.NotEvaluated], which is a failure as well,
 * with the warnings of the [compilationReports], and the [dependencies] of the snippet,
 * hinting at the properties the snippet left [uninitialized].
 *
 * @throws VirtualMachineError thrown by the snippet, other than [StackOverflowError].
 */
internal fun ScriptErrorReporter.scriptResult(
    result: ResultValue?,
    evaluationReports: List<ScriptDiagnostic>,
    compilationReports: List<ScriptDiagnostic>,
    dependencies: SnippetDependencyResolver.SnippetDependencies,
    uninitialized: List<UninitializedProperty> = emptyList()
): ScriptResult = when (result) {
    is ResultValue.Value -> ScriptResult.Value(result.value, warnings(dependencies.supersessions + compilationReports), dependencies.resolvedVersions)
    is ResultValue.Unit -> ScriptResult.Value(Unit, warnings(dependencies.supersessions + compilationReports), dependencies.resolvedVersions)
    is ResultValue.Error -> {
        val error = result.error
        if (error.isFatal) throw error
        ScriptResult.Error(
            describe(error, dependencies.warnings + compilationReports, uninitialized),
            ExecutionPhase.EVALUATION
        )
    }
    is ResultValue.NotEvaluated, null -> ScriptResult.Error(
        describe(evaluationReports.ifEmpty { dependencies.warnings + compilationReports }),
        ExecutionPhase.EVALUATION
    )
}

/**
 * This message with its lines after the first indented by the [indent], so that they read as a continuation of the first,
 * where a blank line stays empty, the trailing whitespace of the message and of each line is dropped,
 * and line breaks of any kind are normalized to `\n`.
 */
private fun String.indentContinuationLines(indent: String): String = trimEnd().lines().mapIndexed { index, line ->
    line.trimEnd().let { if (index == 0 || it.isEmpty()) it else "$indent$it" }
}.joinToString("\n")

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
