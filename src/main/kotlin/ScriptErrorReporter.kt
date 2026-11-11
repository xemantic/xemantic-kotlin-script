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
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.reflect.full.allSupertypes
import kotlin.script.experimental.api.ResultValue
import kotlin.script.experimental.api.ScriptDiagnostic
import kotlin.script.experimental.api.SourceCode

private val logger = KotlinLogging.logger {}

/** The message of a failure without any diagnostic. */
private const val NO_DIAGNOSTIC_MESSAGE = "Failed without any diagnostic message."

/** The line of the snippet of this source path, like `snippet3.kts:2`, as the diagnostics and the frames name it. */
internal fun snippetLine(
    sourcePath: String,
    line: Int
): String = "${sourcePath.substringAfterLast('/')}:$line"

/** The longest sequence of frames collapsed when repeated, like of a mutual recursion. */
private const val MAX_REPEATED_FRAMES = 16

/** The number of the exceptions reported at most, of the causes and the suppressed ones. */
private const val MAX_REPORTED_EXCEPTIONS = 32

private val snippetClassName = Regex("Snippet\\d+")

/**
 * Type arguments, like `<Int>` or `<List<String>?, *>`, followed by a `(` or a trailing lambda, starting the code after a reference,
 * which the parser takes for a call, like `echo<Int>(1)`, not for a comparison, like `limit < 3`,
 * a type argument of a function type, like `<() -> Int>`, not told.
 */
private val typeArgumentsCall = Regex("""^<\s*[\p{L}_*][\p{L}\p{N}_.,?*\s]*(?:<[\p{L}\p{N}_.,?*\s<>]*>[\p{L}\p{N}_.,?*\s]*)*>\s*[({]""")

/** Whether this code, following a reference, calls it, with a `(`, a trailing lambda, or type arguments, see [typeArgumentsCall]. */
private fun String.startsCall(): Boolean = startsWith("(") || startsWith("{") || typeArgumentsCall.containsMatchIn(this)

/**
 * How an unresolved reference is used in the source:
 * [onExplicitReceiver] if it follows a `.`, like `1.tagged()`, but not a range `..`,
 * [callable] if it follows a `::`, like `::shout`, [callableOnReceiver] if also a receiver, like `String::shout`, which cannot be a top-level declaration,
 * all `false` if the code before it cannot be told,
 * [called] if a `(`, a trailing lambda, or type arguments followed by either follow it, see [startsCall],
 * otherwise `false`, as it might be called still, like an infix function, `"a" and "b"`, or with type arguments of a function type, `echo<() -> Int>(f)`,
 * which cannot be told from a comparison, `size < 3`, by the source alone,
 * and [onIntegerLiteral] if it follows an integer literal without a suffix, like `1.tagged()`, whose type a compiler message does not render.
 */
private class UnresolvedReference(
    val onExplicitReceiver: Boolean,
    val callable: Boolean,
    val callableOnReceiver: Boolean,
    val called: Boolean,
    val onIntegerLiteral: Boolean
)

/** An integer literal without a suffix, followed by a `.`, ending the code before a reference, like `1.` or `0xFF.`, but not `1.0.`. */
private val integerLiteralReceiver = Regex("(?<![\\p{L}\\p{N}_.])(?:0[xXbB][0-9a-fA-F_]+|\\p{N}[\\p{N}_]*)\\.$")

/** The type of an integer literal receiver, as one too big for an `Int`, a `Long`, is rendered by a compiler message. */
private val integerLiteralTypes = listOf("Int")

/** A name the source can refer to without backticks. */
private val plainIdentifier = Regex("[\\p{L}_][\\p{L}\\p{N}_]*")

/** The hard keywords of Kotlin, which a name of a declaration is backticked as. */
private val hardKeywords = setOf(
    "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in", "interface", "is", "null", "object",
    "package", "return", "super", "this", "throw", "true", "try", "typealias", "typeof", "val", "var", "when", "while"
)

/** This name as the source refers to it, backticked if it is not a plain identifier, like `` `back tick` ``. */
private fun String.asReference(): String = if (plainIdentifier.matches(this) && this !in hardKeywords) this else "`$this`"

/**
 * The code before the [column] of the [line] of these lines, or of the nearest line before it of any code, if the [line] starts there,
 * without a trailing line comment, `null` if it cannot be told, as it might be within a comment or a multiline string.
 */
private fun List<String>.codeBefore(line: Int, column: Int): String? {
    val prefix = this[line].take(column)
    if (prefix.isNotBlank()) return if ("/*" in prefix || "*/" in prefix) null else prefix.trimEnd()
    for (index in line - 1 downTo 0) {
        val code = this[index].withoutLineComment() ?: return null
        // a line of a block comment, which ends at the nearest line of it, or a line of a KDoc
        if ("/*" in code || "*/" in code || code.trimStart().startsWith("*")) return null
        if (code.isNotBlank()) return code.trimEnd()
    }
    return ""
}

/** This line of code without its trailing line comment, outside a string, `null` if it has a multiline string. */
private fun String.withoutLineComment(): String? {
    if ("\"\"\"" in this) return null
    var quote: Char? = null
    var index = 0
    while (index < length) {
        val char = this[index]
        when {
            quote != null && char == '\\' -> index++
            quote != null -> if (char == quote) quote = null
            char == '"' || char == '\'' -> quote = char
            char == '/' && getOrNull(index + 1) == '/' -> return substring(0, index)
        }
        index++
    }
    return this
}

/** An unresolved reference, with the receiver of an extension, if the reference is qualified, like `"a".shout()`. */
private val unresolvedReference = Regex("Unresolved reference '(.+?)'(?: on receiver of type '(.+)')?\\.")

/** The mangled name of a method taking or returning a value class, like `getTimeout-UwyO8pc`. */
private val mangledMethod = Regex("[\\p{L}_][\\p{L}\\p{N}_]*-[\\p{L}\\p{N}_]{7}\\(")

/**
 * The method missing by the message of a [NoSuchMethodError], with its class, and its parameter types,
 * like `'int Snippet1$Box.open$main(int, java.lang.String)'`.
 */
private val missingMethod = Regex("'[^\\s'(]+ ([^\\s(]+)\\.([^.\\s(]+)\\(([^()]*)\\)'")

/** The simple name of the error of the interpreter of the constants of the compiler not finding a function, like the getter of a `const val`. */
private const val INTERPRETER_METHOD_NOT_FOUND = "InterpreterMethodNotFoundError"

/** The function not found by the interpreter of the constants, with the class declaring it, like `Unknown function: LIMIT(Snippet1)`. */
private val unknownFunction = Regex("Unknown function: ([^\\s()]+)\\(([^\\s()]+)\\)")

/**
 * The field accessed as an instance field, while it is static, with the class declaring it,
 * by the message of an [IncompatibleClassChangeError] of HotSpot, like `Expected non-static field Snippet1.limit`.
 */
private val nonStaticField = Regex("Expected non-static field (\\S+)")

/** The lead of the hints at the K2 REPL compiler not resolving a declaration of an earlier snippet, completed by what, and where. */
private const val K2_REPL_DOES_NOT_RESOLVE = "the K2 REPL compiler does not resolve"

/** The class of the default methods of an interface, like `Greeter$DefaultImpls`. */
private const val DEFAULT_IMPLS = "DefaultImpls"

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
    ): String = inlinedFrames.report().let { report ->
        diagnostics.reportable().map { describe(it, report) }
    }.distinct().joinToString("\n").trimEnd().ifEmpty {
        "error: $NO_DIAGNOSTIC_MESSAGE"
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
            report = inlinedFrames.report(),
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

    /** @param report the frames of the inlined code resolved for all the diagnostics described together. */
    private fun describe(
        diagnostic: ScriptDiagnostic,
        report: InlinedFrames.Report
    ): String = buildString {
        val location = diagnostic.location
        val lines = diagnostic.sourcePath?.let { path -> snippets[path.substringAfterLast('/')] }
        val name = diagnostic.sourcePath?.substringAfterLast('/')
        if (location != null && lines != null && name != null) {
            append("${snippetLine(name, location.start.line)}:${location.start.col}: ")
        }
        append(diagnostic.severity.name.lowercase())
        append(": ")
        val exception = diagnostic.exception
        if (exception != null && diagnostic.severity >= ScriptDiagnostic.Severity.ERROR) {
            append("The compiler failed internally: ")
            appendThrowable(exception, "", "  ", Collections.newSetFromMap(IdentityHashMap()), report)
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
        if (exception != null && diagnostic.severity >= ScriptDiagnostic.Severity.ERROR) {
            appendConstPropertyHint(exception)
        }
        // a hint failing is left out, not to lose the report
        append(logger.readOr("", { "cannot hint at the unresolved reference of: ${diagnostic.message}" }) {
            buildString { appendUnresolvedReferenceHint(diagnostic.message, location, lines) }
        })
    }

    private fun StringBuilder.appendUnresolvedReferenceHint(message: String, location: SourceCode.Location?, lines: List<String>?) {
        unresolvedReference.matchEntire(message)?.let { match ->
            val name = match.groupValues[1]
            val line = location?.let { lines?.getOrNull(it.start.line - 1) }
            val column = location?.start?.col?.minus(1)
            val usage = if (line != null && column != null && column in 0..line.length) {
                val before = lines.orEmpty().codeBefore(location.start.line - 1, column)
                val after = line.substring(column).removePrefix("`$name`").removePrefix(name).trimStart()
                val callable = before != null && before.endsWith("::")
                UnresolvedReference(
                    onExplicitReceiver = before != null && before.endsWith(".") && !before.endsWith(".."),
                    callable = callable,
                    callableOnReceiver = callable && before.dropLast(2).lastOrNull().let { it != null && (it.isLetterOrDigit() || it in "_`>?)]") },
                    called = after.startsCall(),
                    onIntegerLiteral = before != null && integerLiteralReceiver.containsMatchIn(before)
                )
            } else null
            appendUnresolvedEarlierDeclarationHint(name, receiverType = match.groups[2]?.value, usage)
        }
    }

    /**
     * The hint at the K2 REPL compiler not resolving a class, a property or a function of this [name], declared by an earlier snippet,
     * within a declaration of an inferred type used in the same snippet, which resolves once its type is declared,
     * also an extension, called on the receiver of the [receiverType] of the message, if its receiver accepts it.
     * The compiler does not render the type of an integer literal receiver, like `1.tagged()`, nor any implicit receiver,
     * also when no receiver of the type of the extension is in scope at all, so the hint is conditional then,
     * on the compiler reporting the mismatch of the receiver once the type is declared, which the hint makes no worse.
     * The form of an extension, a property or a function, is told by its [usage] in the source, if any.
     * A declaration of a snippet which failed to compile is not kept, so not hinted at, as it stays unresolved also then,
     * nor an inaccessible one, see [SnippetDeclarations].
     * A reference called, see [UnresolvedReference.called], also with type arguments, see [startsCall], is hinted at as a function first, and only then as a property, like one of a function type,
     * unless an inaccessible function of the name is declared, which the compiler calls instead,
     * and one not known to be called as a property first, also a `const val` or a `@JvmField` one, see [appendPropertyHint],
     * and only then as a function, unless a property of the name is declared, which the compiler takes instead.
     */
    private fun StringBuilder.appendUnresolvedEarlierDeclarationHint(name: String, receiverType: String?, usage: UnresolvedReference?) {
        val reference = name.asReference()
        // a callable reference to an extension of a snippet is not resolved also with the type declared, as a member and an extension at the same time
        val callable = usage?.callable == true
        // a reference to a member or an extension of a receiver, not to a top-level declaration
        if (usage?.callableOnReceiver == true) return
        val called = usage?.called == true
        if (receiverType != null) {
            if (callable) return
            appendExplicitReceiverHint(name, listOf(receiverType), called, certain = true)
            return
        }
        val onExplicitReceiver = usage?.onExplicitReceiver == true
        // on a receiver not known, an inaccessible extension of the name of the kind of the reference might be called instead,
        // which a callable reference on an implicit receiver fails on also with the type declared
        val inaccessibleMightBeCalled = inaccessibleExtensions(name, call = called || callable).isNotEmpty()
        if (!onExplicitReceiver) {
            if (snippetDeclarations.isClass(name)) {
                // a call of the constructor might call an inaccessible extension function of the name instead
                if (called && snippetDeclarations.inaccessibleExtensionFunctions(name).isNotEmpty()) return
                append("  hint: '$name' is declared in an earlier snippet, which the K2 REPL compiler does not resolve within a declaration of an inferred type,")
                append(" like `fun f(x: Any) = x is $reference` - declare the type, like `fun f(x: Any): Boolean = x is $reference`.\n")
                return
            }
            if (inaccessibleMightBeCalled) return
            if (!called && appendPropertyHint(name)) return
            // not known to be called, it is still called if no property of the name is declared
            if ((called || snippetDeclarations.property(name) == null) && snippetDeclarations.isFunction(name)) {
                appendDeclareTypeHint(name, "fun f() = $reference(...).toString()", "fun f(): String = $reference(...).toString()")
                return
            }
            // a call of an inaccessible function of the name, which the compiler calls rather than a property of a function type, fails also then
            if (called && !snippetDeclarations.isAnyFunction(name) && appendPropertyHint(name)) return
        }
        if (callable) return
        if (usage?.onIntegerLiteral == true) {
            appendExplicitReceiverHint(name, integerLiteralTypes, called, certain = false)
            return
        }
        // a reference to an extension not known to be called might be an infix call, if no accessible extension property of the name could be read
        val call = called || callable || snippetDeclarations.extensionPropertyReceivers(name).isEmpty()
        if (inaccessibleExtensions(name, call).isNotEmpty()) return
        val propertyReceivers = snippetDeclarations.extensionPropertyReceivers(name)
        val functionReceivers = snippetDeclarations.extensionFunctionReceivers(name)
        val isCall = called || propertyReceivers.isEmpty()
        // the receivers of the form of the reference first, the other ones being possible as well, like a property of a function type called
        val receivers = if (isCall) functionReceivers + propertyReceivers else propertyReceivers + functionReceivers
        if (receivers.isEmpty()) return
        val calledOn = if (isCall) "$reference(...)" else reference
        if (onExplicitReceiver) {
            appendConditionalExplicitReceiverHint(name, receivers, "receiver.$calledOn")
        } else {
            val ofType = if (receivers.size == 1) "of this type" else "of one of these types"
            append("  hint: '$name' is an extension of ${receivers.joinToString(" or ")} declared in an earlier snippet,")
            append(" which the K2 REPL compiler does not resolve within a declaration of an inferred type used in the same snippet,")
            val declaration = receivers.first().declaration
            val receiver = declaration ?: "Receiver"
            append(" even on an implicit receiver $ofType, like `fun $receiver.f() = $calledOn.toString()` - declare the type, like `fun $receiver.f(): String = $calledOn.toString()`,")
            if (declaration == null) append(" Receiver being the type of the receiver,")
            append(" and the compiler tells if there is no such receiver.\n")
        }
    }

    /**
     * The hint at an extension of this [name] called on an explicit receiver of one of these [types], as rendered by a compiler message,
     * by the extensions whose receiver accepts it, see [ExtensionReceiver.acceptance],
     * hinted at as a function if [called], or if no extension property accepts it, as one of a function type is also called.
     * The hint is conditional, on the compiler reporting the mismatch of the receiver once the type is declared,
     * unless a receiver surely accepts the type, and the type is [certain], not of an integer literal.
     * No hint if an inaccessible extension of the name, of the kind of the reference, might accept the type, see [ExtensionReceiver.mightAccept],
     * unless an accessible one surely accepting it is called rather than each of them, see [isCalledRatherThan].
     */
    private fun StringBuilder.appendExplicitReceiverHint(name: String, types: List<String>, called: Boolean, certain: Boolean) {
        fun Set<ExtensionReceiver>.accepting() = associateWith { receiver -> types.maxOf { receiver.acceptance(it) } }
            .filterValues { it != Acceptance.REJECTED }
        val properties = snippetDeclarations.extensionPropertyReceivers(name).accepting()
        val functions = snippetDeclarations.extensionFunctionReceivers(name).accepting()
        if (properties.isEmpty() && functions.isEmpty()) return
        val isCall = called || properties.isEmpty()
        val inaccessible = inaccessibleExtensions(name, isCall).filter { extension ->
            extension.receiver == null || types.any { extension.receiver.mightAccept(it, extension.receiverClass, snippetDeclarations::classOf) }
        }
        if (inaccessible.isNotEmpty()) {
            // a read resolves only a property, a call a function or a property of a function type
            val accessible = snippetDeclarations.accessibleExtensionProperties(name) +
                if (isCall) snippetDeclarations.accessibleExtensionFunctions(name) else emptyList()
            val accepting = accessible.filter { extension -> types.any { extension.receiver?.acceptance(it) == Acceptance.ACCEPTED } }
            if (accepting.none { extension -> inaccessible.all { extension.isCalledRatherThan(it) } }) return
        }
        val reference = name.asReference()
        val calledOn = if (isCall) "receiver.$reference(...)" else "receiver.$reference"
        if (certain && (properties.containsValue(Acceptance.ACCEPTED) || functions.containsValue(Acceptance.ACCEPTED))) {
            appendDeclareTypeHint(name, "fun f() = $calledOn.toString()", "fun f(): String = $calledOn.toString()")
            return
        }
        appendConditionalExplicitReceiverHint(name, if (isCall) functions.keys + properties.keys else properties.keys + functions.keys, calledOn)
    }

    /**
     * The receivers of the inaccessible extensions of this [name] which a reference to it might call instead of an accessible one:
     * of the properties, as of a function type a [call] calls too, and of the functions, if a [call].
     */
    private fun inaccessibleExtensions(name: String, call: Boolean): List<SnippetExtension> =
        snippetDeclarations.inaccessibleExtensionProperties(name) +
            if (call) snippetDeclarations.inaccessibleExtensionFunctions(name) else emptyList()

    /**
     * Whether the compiler calls this extension rather than the [other] one, both accepting a receiver:
     * of a later snippet, as the compiler resolves the snippets from the last one, whatever the receivers,
     * or of the same snippet, of a receiver differing only by its nullability, like `String?` and `String`, or `Any` and a type parameter,
     * or more specific, see [ExtensionReceiver.isLessSpecificThan], also by the supertypes of its class.
     */
    private fun SnippetExtension.isCalledRatherThan(other: SnippetExtension): Boolean {
        if (snippetIndex != other.snippetIndex) return snippetIndex > other.snippetIndex
        val receiver = receiver ?: return false
        val otherReceiver = other.receiver ?: return false
        if (receiver.name == otherReceiver.name && receiver.typeArguments == otherReceiver.typeArguments) return true
        if (otherReceiver.isLessSpecificThan(receiver)) return true
        val receiverClass = receiverClass ?: return false
        return (!receiver.nullable || otherReceiver.nullable) &&
            other.receiverClass != receiverClass &&
            receiverClass.allSupertypes.any { it.classifier == other.receiverClass }
    }

    /**
     * The hint at an extension of this [name] of these [receivers] [called on][calledOn] an explicit receiver, like `receiver.f(...)`,
     * conditional on the compiler reporting the mismatch of the receiver once the type is declared.
     */
    private fun StringBuilder.appendConditionalExplicitReceiverHint(name: String, receivers: Collection<ExtensionReceiver>, calledOn: String) {
        val ofType = if (receivers.size == 1) "of this type" else "of one of these types"
        append("  hint: '$name' is an extension of ${receivers.joinToString(" or ")} declared in an earlier snippet,")
        append(" which the K2 REPL compiler does not resolve within a declaration of an inferred type used in the same snippet,")
        append(" like `fun f() = $calledOn.toString()` - declare the type, like `fun f(): String = $calledOn.toString()`,")
        append(" and the compiler tells if the receiver is not $ofType.\n")
    }

    /**
     * The hint at the last property of this [name] declared by an earlier snippet, if any, whether it was given:
     * to declare the type, or, for a `const val` or a `@JvmField` one, which a later snippet fails on also then,
     * to declare it again public, as an `internal` one cannot be told, see [SnippetDeclarations],
     * and the code of earlier snippets accessing a `@JvmField` one, which compiles, but keeps accessing it,
     * and then to declare the type.
     */
    private fun StringBuilder.appendPropertyHint(name: String): Boolean {
        val property = snippetDeclarations.property(name) ?: return false
        val failure = when (property.kind) {
            SnippetProperty.Kind.ACCESSIBLE -> {
                appendDeclareTypeHint(name, "fun f() = ${name.asReference()}.toString()", "fun f(): String = ${name.asReference()}.toString()")
                return true
            }
            SnippetProperty.Kind.CONST -> constFailure(name, property.snippet)
            SnippetProperty.Kind.JVM_FIELD -> jvmFieldFailure(name, property.snippet)
            SnippetProperty.Kind.INTERNAL -> return false
        }
        val modifier = if (property.kind == SnippetProperty.Kind.CONST) "const modifier" else "@JvmField annotation"
        append("  hint: $failure - declare it again public, without the $modifier, ")
        if (property.kind == SnippetProperty.Kind.JVM_FIELD) {
            appendDeclareCallersAgainAdvice(emptyList(), verb = "accessing", kept = "@JvmField", code = "the code of earlier snippets")
            append(", ")
        }
        append("and declare the type of a declaration of a later snippet reading it, like `fun f(): String = ${name.asReference()}.toString()`,")
        append(" as $K2_REPL_DOES_NOT_RESOLVE a property of an earlier snippet within a declaration of an inferred type used in the same snippet.\n")
        return true
    }

    private fun StringBuilder.appendDeclareTypeHint(name: String, inferred: String, declared: String) {
        append("  hint: '$name' is declared in an earlier snippet, which $K2_REPL_DOES_NOT_RESOLVE within a declaration of an inferred type used in the same snippet,")
        append(" like `$inferred` - declare the type, like `$declared`.\n")
    }

    private fun constFailure(name: String, snippet: String): String =
        "'$name' is declared const in $snippet, which a later snippet cannot read, as the K2 REPL compiler fails on it"

    private fun jvmFieldFailure(name: String, snippet: String): String =
        "'$name' is declared @JvmField in $snippet, which a later snippet cannot access, as the K2 REPL compiler accesses it as an instance field, while it is static"

    /**
     * The hint at the `const val` of an earlier snippet, which the compiler failed on with this internal [exception],
     * caused by the interpreter of the constants not finding its getter, like `Unknown function: LIMIT(Snippet1)`,
     * so it is hinted at only if that snippet declares the last property of the name, a `const val`,
     * not a `const val` of an object, like `Unknown function: X(O)`, which cannot be told from another class of the name.
     */
    private fun StringBuilder.appendConstPropertyHint(exception: Throwable) {
        val (name, owner) = generateSequence(exception) { it.read(null) { cause } }
            .take(MAX_REPORTED_EXCEPTIONS)
            .filter { it.javaClass.simpleName == INTERPRETER_METHOD_NOT_FOUND }
            .firstNotNullOfOrNull { error -> error.read(null) { message }?.let { unknownFunction.matchEntire(it) } }
            ?.destructured ?: return
        val snippet = snippetDeclarations.snippetOf(owner) ?: return
        val property = snippetDeclarations.property(name) ?: return
        if (property.kind != SnippetProperty.Kind.CONST || property.snippet != snippet) return
        append("  hint: ${constFailure(name, snippet)} - declare it again public, without the const modifier.\n")
    }

    /**
     * @param prefix the lead of the first line, with its indent, like `  Caused by: `, as the caller might continue a line.
     * @param indent the indent of the following lines, the frames, the suppressed exceptions and the causes.
     * @param report the frames of the inlined code resolved for the whole report, see [InlinedFrames.Report].
     * @param uninitialized the properties left uninitialized by the snippet throwing the [throwable], hinted at after it.
     */
    private fun StringBuilder.appendThrowable(
        throwable: Throwable,
        prefix: String,
        indent: String,
        reported: MutableSet<Throwable>,
        report: InlinedFrames.Report,
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
        if (throwable is NoSuchMethodError) {
            appendInternalMemberHint(throwable, stackTrace, indent, reported)
        } else if (throwable is IncompatibleClassChangeError) {
            appendJvmFieldPropertyHint(throwable, stackTrace, indent, reported)
        }
        if (throwable is NullPointerException) {
            uninitializedProperties.readBy(throwable, stackTrace)?.let { property ->
                appendUninitializedReadHint(property, stackTrace, indent)
            }
        }
        appendUninitializedHint(uninitialized, indent)
        appendFrames(stackTrace, indent, report)
        for (suppressed in throwable.read(emptyArray()) { suppressed }) {
            if (!appendThrowable(suppressed, "$indent  Suppressed: ", "$indent  ", reported, report)) return false
        }
        return throwable.read(null) { cause }?.let {
            appendThrowable(it, "${indent}Caused by: ", indent, reported, report)
        } ?: true
    }

    /**
     * The hint at the member of an earlier snippet, declared `internal`, which the [error] thrown at the [stackTrace] failed to call, see [SnippetDeclarations.internalMember],
     * unless hinted at already, see [stackTraceToHint], to declare it `public`, unless declared again public by a later snippet already,
     * and to declare again the code calling it, see [appendDeclareCallersAgainAdvice], also when the snippet calls it directly,
     * as the stack trace does not tell whether it calls it also through the code of an earlier snippet.
     */
    private fun StringBuilder.appendInternalMemberHint(
        error: NoSuchMethodError,
        stackTrace: List<StackTraceElement>,
        indent: String,
        reported: Set<Throwable>
    ) {
        val message = error.read(null) { message } ?: return
        val match = missingMethod.matchEntire(message) ?: return
        val calling = error.stackTraceToHint(message, stackTrace, reported) ?: return
        val (owner, method, parameters) = match.destructured
        val member = snippetDeclarations.internalMember(owner, method, parameters.split(", ").filter { it.isNotEmpty() }) ?: return
        val (subject, declared) = if (member.internalSetter) "the setter of '${member.name}'" to "the setter" else "'${member.name}'" to "it"
        append("$indent  hint: $subject is declared internal in ${member.snippet}, which a later snippet cannot call,")
        append(" as the K2 REPL compiler calls it by a JVM name it is not compiled to - ")
        when (member.redeclaration) {
            InternalMember.Redeclaration.NONE -> append("declare $declared public, without the internal modifier, and ")
            InternalMember.Redeclaration.PUBLIC -> append("it is declared again by a later snippet, so ")
            InternalMember.Redeclaration.INTERNAL ->
                append("it is declared again internal by a later snippet, so declare $declared again public, without the internal modifier, and ")
        }
        appendDeclareCallersAgainAdvice(calling, member.declaringClass, member.calledOn, verb = "calling", kept = "internal")
        append(".\n")
    }

    /**
     * The hint at the `@JvmField` property of an earlier snippet, which the [error] thrown at the [stackTrace] accessed as an instance field, while it is static,
     * to declare it again public, without the annotation, or the modifier a later snippet declares it again with, `internal` or `const`, unless declared again public by a later snippet already, see [SnippetDeclarations.isJvmFieldProperty],
     * and to declare again the code accessing it, see [appendDeclareCallersAgainAdvice], unless hinted at already, see [stackTraceToHint].
     */
    private fun StringBuilder.appendJvmFieldPropertyHint(
        error: IncompatibleClassChangeError,
        stackTrace: List<StackTraceElement>,
        indent: String,
        reported: Set<Throwable>
    ) {
        val message = error.read(null) { message } ?: return
        val field = nonStaticField.matchEntire(message)?.groupValues?.get(1) ?: return
        val owner = field.substringBeforeLast('.', missingDelimiterValue = "")
        val name = field.substringAfterLast('.')
        // the snippet first, as telling the property reads the classes of the snippets not read yet
        val snippet = snippetDeclarations.snippetOf(owner) ?: return
        val accessing = error.stackTraceToHint(message, stackTrace, reported) ?: return
        if (!snippetDeclarations.isJvmFieldProperty(name, snippet)) return
        append("$indent  hint: ${jvmFieldFailure(name, snippet)} - ")
        when (snippetDeclarations.property(name)?.kind) {
            SnippetProperty.Kind.ACCESSIBLE -> append("it is declared again by a later snippet, so ")
            SnippetProperty.Kind.INTERNAL -> append("it is declared again internal by a later snippet, so declare it again public, without the internal modifier, and ")
            SnippetProperty.Kind.CONST -> append("it is declared again const by a later snippet, so declare it again public, without the const modifier, and ")
            else -> append("declare it again public, without the @JvmField annotation, and ")
        }
        appendDeclareCallersAgainAdvice(accessing, verb = "accessing", kept = "@JvmField")
        append(".\n")
    }

    /**
     * The stack trace of this [error] of the [message], thrown at the [stackTrace], to hint at,
     * or `null` if hinted at already by an error of the same class and message [reported] before, as kotlinx.coroutines wraps the error of a coroutine,
     * like of `runBlocking`, in a copy of it with the stack trace of its caller, so the stack trace of the error wrapped last, of the coroutine, is read then.
     */
    private fun Throwable.stackTraceToHint(
        message: String,
        stackTrace: List<StackTraceElement>,
        reported: Set<Throwable>
    ): List<StackTraceElement>? {
        fun Throwable.isCopy() = javaClass == this@stackTraceToHint.javaClass && read(null) { this.message } == message
        if (reported.any { it !== this && it.isCopy() }) return null
        val wrapped = generateSequence(this) { exception -> exception.read(null) { cause } }
            .take(MAX_REPORTED_EXCEPTIONS)
            .lastOrNull { it.isCopy() }
        return if (wrapped == null || wrapped === this) stackTrace else wrapped.read(emptyList()) { this.stackTrace.filterNotNull() }
    }

    /**
     * The advice to declare again the [code] [calling][verb] a member of an earlier snippet, directly or not, and to create again the values depending on it,
     * as they keep calling the [kept] one even after it is declared again, not ending the sentence, which the caller continues or ends.
     * The declarations on the [stackTrace], and the classes of its frames whose values a later snippet can create again, see [DeclaredClass.Kind],
     * also the [ownerClass] declaring the member, are named only as examples, as the stack trace cannot tell all of them,
     * like a lambda calling it created by an earlier call, and kept by a value.
     * The subclasses of an open class are named to be declared again as well, as they keep extending the old class,
     * like the [subclass] the member was called on, if it inherits it,
     * and so is the code referring to an object, or to a companion object by its class, and the values holding it, as they keep the old one.
     */
    private fun StringBuilder.appendDeclareCallersAgainAdvice(
        stackTrace: List<StackTraceElement>,
        ownerClass: DeclaredClass? = null,
        subclass: String? = null,
        verb: String,
        kept: String,
        code: String = "the code"
    ) {
        // not a class like DefaultImpls of an interface
        val snippetFrames = stackTrace.filter { it.fileName in snippets && DEFAULT_IMPLS !in it.ownerClasses() }
        val callers = snippetFrames.filter {
            // a synthetic method, like a bridge, or a lambda, but not a function declared internal
            it.declarationName() != null && '$' !in it.declaredMethodName()
        }.declarations()
        // also of a lambda, or an anonymous object, created by a class, which its values might hold, like a lambda of a property
        val classes = (listOfNotNull(ownerClass) + snippetFrames.map { it.className }.distinct().mapNotNull {
            snippetDeclarations.declaredClass(it)
        }).distinct()
        val finalClasses = classes.filter { it.kind == DeclaredClass.Kind.FINAL }.map { "'${it.name}'" }
        val openClasses = classes.filter { it.kind == DeclaredClass.Kind.OPEN }.map { "'${it.name}'" }
        val objects = classes.mapNotNull {
            when (it.kind) {
                DeclaredClass.Kind.OBJECT -> "the object '${it.name}'"
                DeclaredClass.Kind.COMPANION -> "the companion object of '${it.name.substringBeforeLast('.')}'"
                else -> null
            }
        }.enumerated()
        fun List<String>.ofClasses() = "of the ${if (size > 1) "classes" else "class"} ${enumerated()}"
        append("declare again $code $verb it, directly or not, ")
        callers.takeIf { it.isNotEmpty() }?.let { append("like ${it.enumerated()}, ") }
        if (objects.isNotEmpty()) append("or referring to $objects, ")
        if (openClasses.isNotEmpty()) {
            append("and the subclasses of ${openClasses.enumerated()}, ")
            subclass?.let { append("like '$it', ") }
        }
        append("and create again the values depending on it, like the ones ")
        val values = listOfNotNull(
            finalClasses.takeIf { it.isNotEmpty() }?.ofClasses(),
            openClasses.takeIf { it.isNotEmpty() }?.let { "${it.ofClasses()} or of ${if (it.size > 1) "their" else "its"} subclasses" }
        )
        values.forEach { append("$it, ") }
        if (objects.isNotEmpty()) append("holding $objects, ")
        if (values.isNotEmpty() || objects.isNotEmpty()) append("or ")
        append("holding a lambda $verb it, as they keep $verb the $kept one")
    }

    /** The declarations of the snippet frames of this stack trace, like `'greet' of snippet2.kts`, see [declarationName]. */
    private fun List<StackTraceElement>.declarations(): List<String> = filter {
        it.fileName in snippets
    }.mapNotNull { frame ->
        frame.declarationName()?.let { "'$it' of ${frame.fileName}" }
    }.distinct()

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
        val readers = stackTrace.filter { !property.isGetter(it) }.declarations()
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
     *
     * A resolved frame of an inline function of a library, like `forEach` calling the lambda which throws, is dropped like any frame of a library below the top one:
     * it has no line of a snippet to show, while the frame of the snippet calling it shows the line of the call.
     */
    private fun StringBuilder.appendFrames(
        stackTrace: List<StackTraceElement>,
        indent: String,
        report: InlinedFrames.Report
    ) {
        val frames = stackTrace.flatMap { element ->
            report.resolve(element) ?: listOf(element)
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
     * e.g. `Checker.check`, `<snippet body>`, or `<lambda>`,
     * of a nested class named by all the classes within the snippet, like `Box.Lid.open`, if [qualified], otherwise by the innermost one, like `Lid.open`.
     */
    private fun StackTraceElement.functionName(qualified: Boolean = false): String {
        val owner = ownerClasses()
        val methodParts = declaredMethodName().split('$').filter { it.isNotEmpty() }
        val isLambda = methodParts.zipWithNext().any { (name, index) ->
            name == "lambda" && index.all { it.isDigit() }
        } || owner.any { segment -> segment.all { it.isDigit() } }
        val type = if (qualified) owner.joinToString(".").ifEmpty { null } else owner.lastOrNull()
        return when {
            isLambda -> "<lambda>"
            methodName == SNIPPET_BODY_METHOD && type == null -> "<snippet body>"
            else -> listOfNotNull(type, methodParts.lastOrNull() ?: methodName).joinToString(".")
        }
    }

    /**
     * The name of the method of this snippet frame as declared, without the suffix of the stub of the default arguments,
     * nor the module the compiler mangles the name of an `internal` function with, like `helper` of `helper$snippet2_kts$default`.
     */
    private fun StackTraceElement.declaredMethodName(): String =
        methodName.removeSuffix(DEFAULT_STUB_SUFFIX).let { name -> fileName?.let { name.removeSuffix(it.internalSuffix()) } ?: name }

    /** The classes of this frame within its snippet, like `[Box, Lid]` of `Snippet2$Box$Lid`, or all the segments of the class of another frame. */
    private fun StackTraceElement.ownerClasses(): List<String> = className.split('$').let { segments ->
        if (snippetClassName.matches(segments.first())) segments.drop(1) else segments
    }.filter { it.isNotEmpty() }

    /**
     * The name of the declaration of this snippet frame, like `greet`, `Checker.check`, `Box.Lid.open` of a nested class, or `Config` of its initializer,
     * the property of an accessor, like `t` of `getT`, see [PropertyAccessors],
     * or `null` for the body of a snippet, or a lambda, which is not a declaration to redeclare.
     */
    private fun StackTraceElement.declarationName(): String? = functionName(qualified = true).takeIf {
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
 * with the warnings of the [dependencies] of the snippet, and of the [compilationReports], on every path,
 * hinting at the properties the snippet left [uninitialized].
 * The warnings of a fatal error, which is thrown, are lost, but the supersession of a repository declaration,
 * logged by the [SnippetDependencyResolver].
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
    is ResultValue.Value -> ScriptResult.Value(
        result.value, warnings(dependencies.warnings(succeeded = true) + compilationReports), dependencies.resolvedVersions
    )
    is ResultValue.Unit -> ScriptResult.Value(
        Unit, warnings(dependencies.warnings(succeeded = true) + compilationReports), dependencies.resolvedVersions
    )
    is ResultValue.Error -> {
        val error = result.error
        if (error.isFatal) throw error
        ScriptResult.Error(
            describe(error, dependencies.warnings(succeeded = false) + compilationReports, uninitialized),
            ExecutionPhase.EVALUATION
        )
    }
    is ResultValue.NotEvaluated, null -> ScriptResult.Error(
        describe(
            evaluationReports.withFailure() + dependencies.warnings(succeeded = false) + compilationReports
        ),
        ExecutionPhase.EVALUATION
    )
}

/** These reports of a failed evaluation, preceded by an error, if none of them is one, so the warnings following them do not read as the failure. */
private fun List<ScriptDiagnostic>.withFailure(): List<ScriptDiagnostic> =
    if (any { it.severity >= ScriptDiagnostic.Severity.ERROR }) this else listOf(
        ScriptDiagnostic(ScriptDiagnostic.unspecifiedError, NO_DIAGNOSTIC_MESSAGE)
    ) + this

/**
 * This message with its lines after the first indented by the [indent], so that they read as a continuation of the first,
 * where a blank line stays empty, the trailing whitespace of the message and of each line is dropped,
 * and line breaks of any kind are normalized to `\n`.
 */
private fun String.indentContinuationLines(indent: String): String = trimEnd().lines().mapIndexed { index, line ->
    line.trimEnd().let { if (index == 0 || it.isEmpty()) it else "$indent$it" }
}.joinToString("\n")

/** The names of the classes of the snippets without the snippet class, like `Color.BLUE` instead of `Snippet3.Color.BLUE`. */
private fun String.withoutSnippetClassName(): String = replace(snippetQualifier, "")

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
