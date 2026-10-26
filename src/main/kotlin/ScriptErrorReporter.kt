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
import org.jetbrains.org.objectweb.asm.ClassReader
import org.jetbrains.org.objectweb.asm.ClassVisitor
import org.jetbrains.org.objectweb.asm.Label
import org.jetbrains.org.objectweb.asm.MethodVisitor
import org.jetbrains.org.objectweb.asm.Opcodes
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.reflect.KFunction
import kotlin.reflect.KMutableProperty
import kotlin.reflect.KProperty
import kotlin.reflect.full.declaredMembers
import kotlin.reflect.jvm.javaGetter
import kotlin.reflect.jvm.javaMethod
import kotlin.reflect.jvm.javaSetter
import kotlin.script.experimental.api.ScriptDiagnostic

/** The longest sequence of frames recognized as repeated, e.g. in mutual recursion. */
private const val MAX_REPEATED_FRAMES = 16

/** The most exceptions reported, including causes and suppressed ones, as the script might chain them endlessly. */
private const val MAX_REPORTED_EXCEPTIONS = 32

/** The most causes of an internal failure reported, as they tell little about the script. */
private const val MAX_INTERNAL_FAILURE_CAUSES = 8

/** The longest message of an internal failure reported, as it might carry a dump of the bytecode, or of the source. */
private const val MAX_INTERNAL_FAILURE_MESSAGE_LENGTH = 500

/** The error of the unresolved reference, with its name. */
internal val unresolvedReference = Regex("Unresolved reference '(.+)'\\.")

/** The compile-time constant required, like an annotation argument, or the default value of an annotation parameter, reported differently for the members. */
private val constantRequired = Regex(".* must be a compile-time constant\\.|Only 'const val' can be used in constant expressions\\.")

/** Reported for the suspending calls of the lambdas passed to the inline functions compiled as the local ones, see [ScriptSource.localInlineFunctionArguments]. */
private val suspensionOutsideCoroutine = Regex(
    "Suspension functions can only be called within coroutine body\\..*|Suspend function .+ can only be called from a coroutine or another suspend function\\..*"
)

/** Reported without a location, e.g. for an object accessing the dependencies, which are the members of the script class. */
private val declarationCapturingScript = Regex(
    "(${CapturingClassKind.entries.joinToString("|") { it.label }}) '(.+)' captures the script class instance\\..*"
)

/**
 * The first line of the failure of the compiler backend, reported without a location, followed by the intermediate representation of the code,
 * with the name of the generated function, if it is one, and the path of the declaration containing it within the script class, if any, like `T.I`,
 * in the wording of the compiler, which might change with its version, so the other failures of the backend are recognized by their exceptions,
 * and reported without a location, see [isBackendCrash].
 */
private val backendCrash = Regex("Exception while generating code for (?:.* fun (\\S+) .*|.*) declared in (.+):")

/** The package of the compiler backend, throwing the exceptions of its failures, or failing in its frames. */
private const val BACKEND_PACKAGE = "org.jetbrains.kotlin.backend."

/** The accessor of a property, like `<get-x>`, named so by the compiler in the failures of its backend. */
private val accessorFunction = Regex("<[gs]et-(.+)>")

/** The longest name of a class of the script looked up in a text resembling one. */
private const val MAX_CLASS_NAME_LENGTH = 256

/** The most names of the classes of the script looked up in vain, like in a long message resembling them. */
private const val MAX_MISSING_CLASSES = 256

/** The superclasses of the classes of the Kotlin lambdas, not compiled with `invokedynamic`, like the suspend ones. */
private val lambdaSuperclasses = setOf(
    "kotlin.jvm.internal.Lambda",
    "kotlin.coroutines.jvm.internal.SuspendLambda",
    "kotlin.coroutines.jvm.internal.RestrictedSuspendLambda"
)

/**
 * The diagnostics without the ignorable ones, and without the warnings located in the code generated in the [source],
 * like the redundant projection, or the deprecation, of the type of a dependency accessor, which the script cannot change,
 * while the errors located there are still reported, e.g. of the type of a dependency requiring an opt-in.
 */
internal fun Iterable<ScriptDiagnostic>.filterNonIgnorable(
    source: ScriptSource
) = filterNot { diagnostic ->
    when (diagnostic.severity) {
        ScriptDiagnostic.Severity.DEBUG -> listOf(
            "Using JDK home inferred from java.home",
            "Loading modules:"
        ).any { diagnostic.message.startsWith(it) }
        ScriptDiagnostic.Severity.WARNING -> diagnostic.location?.let { source.isGeneratedLine(it.start.line) } == true
        // the parser of a regular script reports each token following the unmatched closing brace as unexpected
        else -> source.unmatchedClosingBrace?.let { brace ->
            diagnostic.location?.start?.let { start ->
                source.toScriptPosition(start.line, start.col).let { it.line > brace.line || (it.line == brace.line && it.column > brace.column) }
            }
        } == true
    }
}

/**
 * Describes failures in terms of the original script.
 *
 * @param scriptClassName the binary name of the compiled script class,
 *   to strip it from the names of the classes declared in the script.
 * @param blockClassName the binary name of the class of the script block,
 *   to tell the frames of the script body.
 * @param classLoader the class loader of the script class,
 *   resolving the names of the classes declared in the script, in the exception messages, and their methods in the frames.
 */
internal class ScriptErrorReporter(
    private val source: ScriptSource,
    private val scriptClassName: String? = null,
    private val blockClassName: String? = null,
    private val classLoader: ClassLoader? = null
) {

    /**
     * The name of a class qualified by the script class, like `_.Color` of `Enum.valueOf`, or `_$Box$Lid` of `ClassCastException`,
     * and of the default string representation, as long as the binary name continues, not preceded by a part of another name,
     * matching it with a single character class, as a repeated group would overflow the stack on a long message,
     * followed by the address of a lambda, if any, like `/0x000071a8` of `_$$$result$1$1$$Lambda/0x000071a8`.
     */
    private val scriptQualifiedName = scriptClassName?.let {
        // also the element class of an array, like `[L_$Circle;`
        Regex("(?:(?<=\\[L)|(?<![\\p{L}\\p{N}_.$]))${Regex.escape(it)}([.$][\\p{L}\\p{N}_.$]*)(/0x\\p{XDigit}+)?")
    }

    /** The constant parts of the string literals of the script resembling the names qualified by the script class, like `_.Color`, which are kept. */
    private val writtenNames by lazy {
        source.stringLiterals.filter { literal -> scriptQualifiedName?.containsMatchIn(literal) == true }
    }

    /** The classes of the script by their binary names, or `null`, if there is no such class. */
    private val scriptClasses = HashMap<String, Class<*>?>()

    /** See [kotlinMethodNames]. */
    private val kotlinMethodNames = HashMap<Class<*>, Map<String, String>>()

    /** The number of the names of the classes looked up in vain, see [scriptClass]. */
    private var missingClassCount = 0

    fun toFailureMessage(
        diagnostics: List<ScriptDiagnostic>
    ): String = diagnostics.toFailureMessage(compilation = false)

    /**
     * Like [toFailureMessage], while the errors carrying an exception are the internal failures of the compiler,
     * reported without its stack traces, and generated names.
     */
    fun toCompilationFailureMessage(
        diagnostics: List<ScriptDiagnostic>
    ): String = diagnostics.toFailureMessage(compilation = true)

    private fun List<ScriptDiagnostic>.toFailureMessage(
        compilation: Boolean
    ): String = (describe(compilation) + listOfNotNull(unterminatedCommentHint())).joinToString(separator = "\n").trimEnd().ifEmpty {
        // e.g. when all the diagnostics of the failure were ignorable
        "error: Failed without any diagnostic message."
    }

    /**
     * The block comment swallowing the rest of the script, see [ScriptSource.unterminatedComment],
     * reported by the compiler as the missing closing tokens only, which might be within the comment.
     */
    private fun unterminatedCommentHint(): String? = source.unterminatedComment?.let { start ->
        buildString {
            appendPosition(start)
            +"info: Unterminated block comment, running to the end of the script.\n"
            appendSourceLines(start, start.copy(column = start.column + 2))
            +"  hint: close the comment with '*/' - the tokens reported as missing might be within it.\n"
        }
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
    private fun List<ScriptDiagnostic>.describe(
        compilation: Boolean = false
    ): List<String> {
        // reported without a location, the classes of the same name, like the companion objects, are told by their order
        val capturingClasses = map { diagnostic ->
            diagnostic.takeIf { it.location == null }?.let { declarationCapturingScript.matchEntire(it.message)?.groupValues?.subList(1, 3) }
        }
        val counts = capturingClasses.filterNotNull().groupingBy { it }.eachCount()
        val indices = HashMap<List<String>, Int>()
        val unresolved = if (any { constantRequired.matches(it.message) }) {
            mapNotNullTo(HashSet()) { diagnostic ->
                diagnostic.location?.takeIf { unresolvedReference.matches(diagnostic.message) }?.let {
                    source.toScriptPosition(it.start.line, it.start.col)
                }
            }
        } else {
            emptySet()
        }
        return mapIndexed { index, diagnostic ->
            val occurrence = capturingClasses[index]?.let { kindAndName ->
                indices.merge(kindAndName, 1, Int::plus)!! - 1 to counts.getValue(kindAndName)
            }
            buildString {
                val exception = diagnostic.exception
                if (
                    compilation && exception != null && diagnostic.severity == ScriptDiagnostic.Severity.ERROR
                    // reported at the declarations of the script instead, see appendBackendCrash
                    && !(diagnostic.location == null && (backendCrash.matches(diagnostic.message.lineSequence().first()) || diagnostic.isBackendCrash()))
                ) {
                    appendCompilerFailure(diagnostic, exception)
                } else {
                    appendDiagnostic(diagnostic, occurrence, unresolved)
                }
            }
        }.distinct()
    }

    /**
     * The [exception] the compiler failed with internally, reported as the [diagnostic],
     * like `While analysing $1.kts:6:15: ...: Exception in expression checkers`,
     * at the position in the script, if its location, or its message, refers to one.
     */
    private fun StringBuilder.appendCompilerFailure(
        diagnostic: ScriptDiagnostic,
        exception: Throwable
    ) {
        val location = diagnostic.location
        location?.let { source.preambleDependencyAt(it.start.line) }?.let { dependency ->
            +"dependency '$dependency': "
        }
        // only the lines of the script, not the generated ones
        val range = if (location != null) {
            source.toScriptLine(location.start.line)?.let { line ->
                val start = ScriptPosition(line, (location.start.col - 1).coerceAtLeast(0))
                val end = location.end?.let {
                    source.toScriptPosition(it.line, it.col)
                }?.takeIf {
                    (it.line > start.line) || (it.line == start.line && it.column >= start.column)
                } ?: start
                start to end
            }
        } else {
            source.generatedLocations().find(diagnostic.message)?.let { source.scriptLineAndColumn(it) }?.let { (line, column) ->
                column?.let { ScriptPosition(line, (it - 1).coerceAtLeast(0)) }
            }?.let { it to it }
        }
        range?.let { appendPosition(it.first) }
        +"error: The compiler failed internally: "
        appendInternalFailure(exception, diagnostic.message) { it.toScriptLocations(listOf(source)) }
        if (range != null) {
            appendSourceLines(range.first, range.second)
            +"  hint: it is a failure of the compiler itself - express this code differently.\n"
        } else {
            +"  hint: it is a failure of the compiler itself - express the script differently.\n"
        }
    }

    /** The failure recognized in the [source] without the compilation reporting it, like the conflicting declarations. */
    fun toSourceFailureMessage(): String = source.unmatchedClosingBrace?.let {
        // the script alone compiled, contrary to the lexer, see [ScriptSource.isWrapped]
        toUnmatchedClosingBraceMessage(it)
    } ?: source.unclosedOpening?.let { opening ->
        buildString {
            appendPosition(opening.start)
            +"error: Syntax error: '${opening.name}' is not closed.\n"
            appendSourceLines(opening.start, opening.end)
        }.trimEnd()
    } ?: source.conflictingDependencyDeclarations.map { declaration ->
        buildString {
            appendPosition(declaration.start)
            +"error: Conflicting declaration of '${declaration.name}', which is also the name of a dependency.\n"
            appendSourceLines(declaration.start, declaration.end)
            +"  hint: '${declaration.name}' is compiled outside of the script body, together with the dependencies - rename it.\n"
        }
    }.joinToString(separator = "\n").trimEnd()

    /** The [references] of the script body to its coroutine scope, see [ScriptSource.coroutineScopeCandidates]. */
    fun toCoroutineScopeMessage(
        references: List<CoroutineScopeReference>
    ): String = references.joinToString(separator = "\n") { scope ->
        val reference = scope.reference
        buildString {
            appendPosition(reference.start)
            +"error: '${reference.name}' refers to the coroutine scope of the script body, instead of the ${scope.kind} declared at line ${scope.line + 1}, "
            +"which is compiled outside of the script body.\n"
            appendSourceLines(reference.start, reference.end)
            +"  hint: rename the ${scope.kind}.\n"
        }
    }.trimEnd()

    /** The [references] of the local functions of the script body to its coroutine scope, see [ScriptSource.coroutineScopeReceiverCandidates]. */
    fun toCoroutineScopeReceiverMessage(
        references: List<ScriptReference>
    ): String = references.joinToString(separator = "\n") { reference ->
        buildString {
            appendPosition(reference.start)
            if (reference.name == "this") {
                +"error: 'this' refers to the coroutine scope of the script body, while in a function of a regular script it refers to the script instance.\n"
            } else {
                +"error: '${reference.name}' is resolved on the coroutine scope of the script body, while in a function of a regular script it is resolved on the script instance.\n"
            }
            appendSourceLines(reference.start, reference.end)
            +"  hint: refer to the coroutine scope as 'this@_script' instead.\n"
        }
    }.trimEnd()

    /** The [references] of the script body to the local declarations instead of the functions, see [ScriptSource.shadowedFunctionCandidates]. */
    fun toShadowedFunctionMessage(
        references: List<ShadowedFunctionReference>
    ): String = references.joinToString(separator = "\n") { shadowed ->
        val reference = shadowed.reference
        val function = shadowed.functionLine + 1
        buildString {
            appendPosition(reference.start)
            val lines = shadowed.lines.map { it + 1 }
            val local = if (lines.size == 1) "the ${shadowed.kind} of the script body declared at line ${lines.single()}"
                else "one of the functions of the script body declared at lines ${lines.joinToString()}"
            +"error: '${reference.name}' refers to $local, "
            +"instead of ${if (shadowed.isFunction) "its overload" else "the function"} declared at line $function, which a regular script calls.\n"
            appendSourceLines(reference.start, reference.end)
            if (shadowed.isFunction) {
                val overloads = if (lines.size == 1) "its overload declared at line ${lines.single()}, which hides"
                    else "its overloads declared at lines ${lines.joinToString()}, which hide"
                +"  hint: '${reference.name}' (line $function) is compiled outside of the script body, unlike $overloads it there - rename one of them.\n"
            } else {
                +"  hint: rename the property or the function.\n"
            }
        }
    }.trimEnd()

    /** The [references] of the declarations moved out of the script body to other functions, see [ScriptSource.hiddenLocalFunctionCandidates]. */
    fun toHiddenLocalFunctionMessage(
        references: List<HiddenLocalFunctionReference>
    ): String = references.joinToString(separator = "\n") { hidden ->
        val reference = hidden.reference
        val declaration = source.movedDeclarationAt(reference.start)
        val kind = hidden.kind
        buildString {
            appendPosition(reference.start)
            +"error: '${reference.name}' does not refer to the $kind of the script body declared at line ${hidden.line + 1}, which is not visible in '$declaration'.\n"
            appendSourceLines(reference.start, reference.end)
            appendNotVisibleHint(declaration, reference.name, hidden.line, source.isMovedInlineFunction(declaration))
        }
    }.trimEnd()

    /** The [references] of the script body calling other functions than the local functions declared later, see [ScriptSource.laterDeclaredFunctionCandidates]. */
    fun toLaterDeclaredFunctionMessage(
        references: List<LaterDeclaredFunctionReference>
    ): String = references.joinToString(separator = "\n") { later ->
        val reference = later.reference
        buildString {
            appendPosition(reference.start)
            +"error: '${reference.name}' does not refer to the function of the script body declared at line ${later.line + 1}, which is not visible before its declaration.\n"
            appendSourceLines(reference.start, reference.end)
            appendNotDeclaredYetHint(reference.name, later.line, "functions", reference.start)
        }
    }.trimEnd()

    /**
     * The [references] of the script body to the dependencies, preceding the local declarations of their names,
     * see [ScriptSource.laterDeclaredDependencyCandidates] and [ScriptSource.laterDeclaredClassCandidates].
     */
    fun toLaterDeclaredDependencyMessage(
        references: List<PrecedingReference>
    ): String = references.joinToString(separator = "\n") { later ->
        val reference = later.reference
        val kind = later.kind
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
        +"error: $UNMATCHED_CLOSING_BRACE\n"
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
        +throwable.javaClass.className()
        val message = try {
            throwable.message
        } catch (e: Throwable) {
            "[message unavailable: ${e.description()}]"
        }
        message?.let {
            +": "
            +it.withoutScriptClassName()
        }
    }

    /**
     * The names of the classes declared in the script qualified by the script class, like `_.Color.BLUE`, as named in the script, like `Color.BLUE`,
     * also without the synthetic names of the classes enclosing a local class, like `_$$$result$1$1$Dog`,
     * and the anonymous objects and the lambdas like the frames, as `<anonymous object>` and `<lambda>`,
     * the classes told by the class loader of the script, unless a constant part of a string literal of the script contains the whole name there,
     * so the text written by the script is kept, like `"_.Color"`, while the text built at runtime to look like such a name, like `"_" + ".Color"`,
     * cannot be told from it, and is rewritten as well.
     * The classes generated by the compiler, like `$WhenMappings`, or by this library, like the one of the dependencies, are kept, as the script does not declare them.
     */
    private fun String.withoutScriptClassName(): String {
        val regex = scriptQualifiedName ?: return this
        val message = this
        val prefixLength = scriptClassName!!.length
        val written by lazy { message.writtenEnds() }
        return buildString {
            var index = 0
            while (true) {
                val match = regex.find(message, index) ?: break
                append(message, index, match.range.first)
                val start = match.range.first
                val nameStart = start + prefixLength
                val nameEnd = match.groups[1]!!.range.last + 1
                val (name, end) = if (match.groupValues[1].contains("$\$Lambda")) {
                    // a lambda compiled with `invokedynamic`, as a hidden class, which cannot be looked up
                    "<lambda>" to match.range.last + 1
                } else {
                    // the text following the class is matched again, as it might name other classes, like `_$Box$foo` of `_$Box$foo$_$Lid`
                    message.scriptClassAt(nameStart, nameEnd)?.let { (declared, classEnd) ->
                        declared.takeUnless { it.isGenerated() }?.className() to classEnd
                    } ?: (null to nameEnd)
                }
                index = end
                if (name == null || written[start] >= maxOf(nameEnd, end)) {
                    append(message, start, end)
                } else {
                    +name
                }
            }
            append(message, index, message.length)
        }
    }

    /**
     * For each position of this text, the furthest end of the constant parts of the string literals of the script found in it,
     * which start at the position or before it, or `-1`, so the text from a position to an end is written by the script,
     * if the end is not further, the occurrences of each one found in linear time.
     */
    private fun String.writtenEnds(): IntArray {
        val ends = IntArray(length + 1) { -1 }
        writtenNames.forEach { written ->
            occurrences(written).forEach { start -> ends[start] = maxOf(ends[start], start + written.length) }
        }
        for (index in 1..length) ends[index] = maxOf(ends[index], ends[index - 1])
        return ends
    }

    /** The starts of the occurrences of the [pattern] in this text, with the Knuth-Morris-Pratt algorithm. */
    private fun String.occurrences(
        pattern: String
    ): List<Int> {
        val prefixes = IntArray(pattern.length)
        var matched = 0
        for (index in 1 until pattern.length) {
            while (matched > 0 && pattern[index] != pattern[matched]) matched = prefixes[matched - 1]
            if (pattern[index] == pattern[matched]) matched++
            prefixes[index] = matched
        }
        val starts = mutableListOf<Int>()
        matched = 0
        for (index in indices) {
            while (matched > 0 && this[index] != pattern[matched]) matched = prefixes[matched - 1]
            if (this[index] == pattern[matched]) matched++
            if (matched == pattern.length) {
                starts += index - pattern.length + 1
                matched = prefixes[matched - 1]
            }
        }
        return starts
    }

    /**
     * The class of the script of the longest binary or canonical name, like `_$Box` of `_$Box$foo`, or `_$Color` of `_.Color.RED`,
     * following the name of the script class at the [start] of this text, up to the [end] of the name, or continued by a backticked name,
     * like `My Box`, and the end of its name, or `null`, if there is no such class.
     */
    private fun String.scriptClassAt(
        start: Int,
        end: Int
    ): Pair<Class<*>, Int>? {
        val loader = classLoader ?: return null
        val limit = minOf(end, start + MAX_CLASS_NAME_LENGTH)
        val separators = (start until limit).filter { this[it] == '.' || this[it] == '$' }
        val ends = separators.drop(1) + listOfNotNull(end.takeIf { it == limit }) + separators.flatMap { separator ->
            source.backtickedClassNames.filter { startsWith(it, separator + 1) }.map { separator + 1 + it.length }
        }
        return ends.distinct().sortedDescending().firstNotNullOfOrNull { classEnd ->
            scriptClass(scriptClassName + substring(start, classEnd).replace('.', '$'), loader, inMessage = true)?.let { it to classEnd }
        }
    }

    /**
     * The class of the script of this binary [name], or `null`, if there is no such class, also remembered,
     * while no further classes named [inMessage] are looked up after too many were not found, like in a long message resembling their names,
     * unlike the classes of the frames.
     */
    private fun scriptClass(
        name: String,
        loader: ClassLoader,
        inMessage: Boolean = false
    ): Class<*>? {
        if (name in scriptClasses) return scriptClasses[name]
        if (inMessage && missingClassCount >= MAX_MISSING_CLASSES) return null
        val type = try {
            Class.forName(name, false, loader)
        } catch (e: Throwable) {
            if (e is VirtualMachineError) throw e
            null
        }
        if (type == null && inMessage) missingClassCount++
        scriptClasses[name] = type
        return type
    }

    /** Whether it is generated by the compiler, like `$WhenMappings`, or by this library, like the class of the dependencies, not declared in the script. */
    private fun Class<*>.isGenerated(): Boolean = try {
        isSynthetic || (simpleName == DEPENDENCIES_CLASS_NAME && declaringClass?.name == scriptClassName)
    } catch (e: Throwable) {
        if (e is VirtualMachineError) throw e
        false
    }

    /**
     * The names of the functions and the property accessors, like `<get-x>`, by the names of their JVM methods, as declared in the Kotlin metadata of the [Class],
     * like `getX`, `getX$main` of an internal one, suffixed with the module name, or the one given with `@JvmName`.
     */
    private fun Class<*>.kotlinMethodNames(): Map<String, String> = kotlinMethodNames.getOrPut(this) {
        try {
            kotlin.declaredMembers.flatMap { member ->
                when (member) {
                    is KProperty<*> -> listOfNotNull(
                        member.javaGetter?.let { it.name to "<get-${member.name}>" },
                        (member as? KMutableProperty<*>)?.javaSetter?.let { it.name to "<set-${member.name}>" }
                    )
                    is KFunction<*> -> listOfNotNull(member.javaMethod?.let { it.name to member.name })
                    else -> emptyList()
                }
            }.toMap()
        } catch (e: Throwable) {
            if (e is VirtualMachineError) throw e
            // like a synthetic class of a lambda, without the Kotlin metadata
            emptyMap()
        }
    }

    /**
     * Whether the frame at the [index] of the [stackTrace] is generated by the compiler, without a counterpart in the script,
     * like a synthetic accessor, `access$getX`, calling a private member, a method of a class calling the default method of its interface,
     * or a bridge of a generic override, like `getX` returning `Object`, calling the method of the same name of the frame above,
     * told by the lines of the bridges, which are the ones of their class, also of the method itself, if it is declared in the same line,
     * then only if the frame below the bridge calls the method by the descriptor of the bridge, and not by its own one, like through the interface.
     */
    private fun isGenerated(
        stackTrace: List<StackTraceElement>,
        index: Int
    ): Boolean {
        val element = stackTrace[index]
        if (index == 0 || element.fileName != source.locationId || element.className == blockClassName) return false
        val methods = element.methods()?.get(element.methodName) ?: return false
        if (methods.all { it.isBridge || (it.isSynthetic && it.name.startsWith("access$")) }) return true
        val bridges = methods.filter { it.isBridge }
        val above = stackTrace[index - 1]
        if (bridges.isEmpty() || above.className != element.className || above.methodName != element.methodName) return false
        if (bridges.none { element.lineNumber in it.lines }) return false
        if (methods.none { !it.isBridge && element.lineNumber in it.lines }) return true
        // declared in the line of its class, so told by the call of the method of the frame below, which contains its line
        val calls = stackTrace.getOrNull(index + 1)?.let { caller ->
            caller.methods()?.get(caller.methodName)?.filter { caller.lineNumber in it.lines }?.flatMap { it.calls[caller.lineNumber].orEmpty() }
        }.orEmpty().filter { it.name == element.methodName }
        val owner = element.className.replace('.', '/')
        val bridgeDescriptors = bridges.mapTo(HashSet()) { it.descriptor }
        return calls.any { it.descriptor in bridgeDescriptors }
                && calls.none { call -> call.owner == owner && methods.any { !it.isBridge && it.descriptor == call.descriptor } }
    }

    /** A method of a class of the script, as compiled. */
    private class Method(
        val name: String,
        val descriptor: String,
        val isBridge: Boolean,
        val isSynthetic: Boolean,
        /** The lines of its code. */
        val lines: Set<Int>,
        /** The methods it calls, by the lines of the calls. */
        val calls: Map<Int, List<Call>>
    )

    /** A call of the method of the [name] and the [descriptor] declared by the [owner], like `C` or `I`, by its internal name. */
    private class Call(
        val owner: String,
        val name: String,
        val descriptor: String
    )

    /** See [methods]. */
    private val methods = HashMap<String, Map<String, List<Method>>?>()

    /**
     * The methods of the class of this frame by their names, read from its bytecode once,
     * or `null`, if it is not a class of the script, or it cannot be read.
     */
    private fun StackTraceElement.methods(): Map<String, List<Method>>? =
        if (className in methods) methods[className] else readMethods(className).also { methods[className] = it }

    private fun readMethods(
        className: String
    ): Map<String, List<Method>>? {
        val loader = classLoader ?: return null
        return try {
            val type = scriptClass(className, loader) ?: return null
            val bytes = type.classLoader?.getResourceAsStream(className.replace('.', '/') + ".class")?.use { it.readBytes() } ?: return null
            val methods = mutableListOf<Method>()
            ClassReader(bytes).accept(object : ClassVisitor(Opcodes.API_VERSION) {
                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?
                ): MethodVisitor {
                    val lines = HashSet<Int>()
                    val calls = HashMap<Int, MutableList<Call>>()
                    var line = -1
                    return object : MethodVisitor(Opcodes.API_VERSION) {
                        override fun visitLineNumber(
                            number: Int,
                            start: Label
                        ) {
                            line = number
                            lines += number
                        }

                        override fun visitMethodInsn(
                            opcode: Int,
                            owner: String,
                            name: String,
                            descriptor: String,
                            isInterface: Boolean
                        ) {
                            calls.getOrPut(line) { mutableListOf() } += Call(owner, name, descriptor)
                        }

                        override fun visitEnd() {
                            methods += Method(
                                name = name,
                                descriptor = descriptor,
                                isBridge = access and Opcodes.ACC_BRIDGE != 0,
                                isSynthetic = access and Opcodes.ACC_SYNTHETIC != 0,
                                lines = lines,
                                calls = calls
                            )
                        }
                    }
                }
            }, ClassReader.SKIP_FRAMES)
            methods.groupBy { it.name }
        } catch (e: Throwable) {
            if (e is VirtualMachineError) throw e
            null
        }
    }

    /**
     * The name of the class in terms of the script, like `Box.Lid` of `_$Box$Lid`, `Dog` of the local class `_$$$result$1$1$Dog`,
     * `<anonymous java.lang.Runnable>` of an object literal, or `<lambda>`, while the name of a class of the script itself is qualified by the script class.
     */
    private fun Class<*>.className(): String = try {
        when {
            // the synthetic name, like `Script$result$1$fail$1`, would not tell anything
            isAnonymousClass -> when {
                superclass?.name in lambdaSuperclasses -> "<lambda>"
                superclass == Any::class.java -> interfaces.firstOrNull()?.let { "<anonymous ${it.className()}>" } ?: "<anonymous object>"
                else -> "<anonymous ${superclass.className()}>"
            }
            // e.g. the classes declared in the script body
            isLocalClass -> simpleName
            scriptClassName == null || !name.startsWith("$scriptClassName$") -> name
            else -> declaringClass?.takeIf { it.name != scriptClassName }?.let { "${it.className()}.$simpleName" } ?: simpleName
        }
    } catch (e: Throwable) {
        if (e is VirtualMachineError) throw e
        // like a malformed name of a class not compiled by Kotlin
        name
    }

    private class Frame(
        val location: String,
        val sourceLine: String?
    )

    private fun StringBuilder.appendFrames(
        stackTrace: List<StackTraceElement>,
        indent: String
    ) {
        val frames = stackTrace.filterIndexed { index, _ -> !isGenerated(stackTrace, index) }.withIndex().mapNotNull { (index, element) ->
            // frames of other scripts, e.g. the one executing this script, are not this script's;
            // synthetic frames of the script block carry no line number
            val scriptLine = if (element.fileName == source.locationId && element.lineNumber > 0) {
                source.toScriptLine(element.lineNumber)
            } else {
                null
            }
            when {
                scriptLine != null -> Frame(
                    location = "${element.functionName()}(script.kts:${scriptLine + 1})",
                    sourceLine = source.scriptLines[scriptLine]
                )
                // like a static initializer of an object declared in the script
                index == 0 && element.fileName == source.locationId -> Frame(
                    location = "${element.functionName()}(script.kts)",
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
     * The name of the function of this script frame, in terms of the script,
     * e.g. `Checker.check`, `<script body>`, `<lambda>` or `<get-x>` of a property accessor,
     * the functions and the accessors told by the Kotlin metadata of their classes, see [kotlinMethodNames].
     */
    private fun StackTraceElement.functionName(): String {
        val owner = when {
            className == blockClassName || className == scriptClassName -> ""
            blockClassName != null && className.startsWith("$blockClassName$") ->
                className.removePrefix("$blockClassName$")
            scriptClassName != null && className.startsWith("$scriptClassName$") ->
                className.removePrefix("$scriptClassName$")
            else -> className
        }
        // the local functions are named after their enclosing functions, like `invokeSuspend$outer$inner`
        val methodParts = methodName.removeSuffix("\$default").split('$')
        val type = owner.split('$').lastOrNull { it.isNotEmpty() }
        // the lambdas are named like `invokeSuspend$lambda$0`, unlike the functions named `lambda`
        val isLambda = methodParts.zipWithNext().any { (name, index) ->
            name == "lambda" && index.isNotEmpty() && index.all { it.isDigit() }
        }
        return when {
            isLambda || (type != null && type.all { it.isDigit() }) -> "<lambda>"
            type == null && className == blockClassName && methodParts == listOf("invokeSuspend") -> "<script body>"
            else -> {
                // the local functions are named after the functions or the accessors, like `getX$inner`, unlike the internal ones, like `getX$main`
                val function = classLoader?.takeIf { className != blockClassName }?.let { loader ->
                    scriptClass(className, loader)?.kotlinMethodNames()?.get(methodName.removeSuffix("\$default"))
                } ?: methodParts.last()
                if (type == null) function else "$type.$function"
            }
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
     *
     * @param capturingOccurrence the index of the diagnostic of the class capturing the script class instance reported without a location,
     *   among the ones of the same kind and name, and their count.
     * @param unresolved the positions of all the unresolved references reported.
     */
    private fun StringBuilder.appendDiagnostic(
        diagnostic: ScriptDiagnostic,
        capturingOccurrence: Pair<Int, Int>?,
        unresolved: Set<ScriptPosition>
    ) {
        val location = diagnostic.location
        location?.let { source.preambleDependencyAt(it.start.line) }?.let { dependency ->
            +"dependency '$dependency': "
            appendSeverityAndMessage(diagnostic)
            return
        }
        if (location == null) {
            val firstLine = diagnostic.message.lineSequence().first()
            val match = backendCrash.matchEntire(firstLine)
            if (match != null || diagnostic.isBackendCrash()) {
                // qualified by the package of the script, or by `<root>`, followed by the script class, unless it is a declaration of a library
                val qualifiedPath = match?.groupValues?.get(2)
                val scriptPath = qualifiedPath?.let { path ->
                    listOf("<root>.", "${source.packageName}.").firstOrNull { path.startsWith(it) }?.let { prefix ->
                        val names = path.removePrefix(prefix).split('.')
                        prefix + names.first() to names.drop(1)
                    }
                }
                appendBackendCrash(
                    diagnostic = diagnostic,
                    message = firstLine.removeSuffix(":"),
                    function = match?.groupValues?.get(1).orEmpty(),
                    scriptClass = scriptPath?.first,
                    path = scriptPath?.second
                )
                return
            }
        }
        val capturing = declarationCapturingScript.matchEntire(diagnostic.message)?.groupValues
        var capturingDeclaration = capturing?.get(2)
        val range = if (location != null) {
            val start = source.toScriptPosition(location.start.line, location.start.col)
            val end = location.end?.let {
                source.toScriptPosition(it.line, it.col)
            }?.takeIf { it >= start } ?: start
            start to end
        } else {
            capturing?.let { (_, kind, name) ->
                capturingOccurrence?.let { (index, count) -> source.movedClassName(kind, name, index, count) }
            }?.let { (declared, start, end) ->
                // like `Factory`, which the compiler calls `Companion`
                capturingDeclaration = declared
                start to end
            }
        }
        range?.let { appendPosition(it.first) }
        appendSeverityAndMessage(
            // more specific than the "Unexpected symbol" of the compiler, covering the brace,
            // unlike e.g. the empty range of `Expecting ')'` of `foo(1}` at the same position
            if (
                range != null && range.first == source.unmatchedClosingBrace && range.second != range.first
                && diagnostic.message.startsWith("Syntax error:")
            ) {
                diagnostic.copy(message = UNMATCHED_CLOSING_BRACE)
            } else {
                diagnostic
            }
        )
        if (range == null) return
        val (start, end) = range
        appendSourceLines(start, end)
        unresolvedReference.matchEntire(diagnostic.message)?.let { match ->
            appendHint(name = match.groupValues[1], position = start)
        }
        if (constantRequired.matches(diagnostic.message)) {
            // e.g. not visible in an object, already hinted at
            source.propertiesReferredIn(start, end, excluded = unresolved).forEach { (name, line) ->
                +"  hint: '$name' (line ${line + 1}) is a property of the script body, which cannot be 'const', like a top-level property of a regular script"
                +" - inline its value, or declare it as a 'const val' in an object, if it is a primitive or a string.\n"
            }
        }
        if (suspensionOutsideCoroutine.matches(diagnostic.message)) {
            // the innermost one, if nested
            source.localInlineFunctionArguments.lastOrNull { start in it }?.let { argument ->
                +"  hint: '${argument.name}' is compiled without 'inline', as it reads the local values of the script body, so its lambdas cannot suspend"
                +" - pass the local values to '${argument.name}' as parameters to keep it inline.\n"
            }
        }
        if (capturing != null) {
            val kind = capturing[1].lowercase()
            val article = if (kind[0] in "aeiou") "an" else "a"
            val alternative = if (kind == "object") "declare it as a class, or " else ""
            +"  hint: '$capturingDeclaration' is compiled outside of the script body, where $article $kind cannot access the dependencies,"
            +" nor the functions of the script - ${alternative}pass them as parameters.\n"
        }
    }

    /**
     * Whether it is a failure of the compiler backend, reported without a location, in the wording not recognized by [backendCrash],
     * like `Backend Internal error: Exception during IR lowering`, told by its exceptions thrown by the backend.
     */
    private fun ScriptDiagnostic.isBackendCrash(): Boolean = location == null && exceptionChain().any { exception ->
        exception.javaClass.name.startsWith(BACKEND_PACKAGE)
                || exception.read(emptyList()) { stackTrace.filterNotNull() }.any { it.className.startsWith(BACKEND_PACKAGE) }
    }

    /** The exception of the diagnostic, followed by its causes. */
    private fun ScriptDiagnostic.exceptionChain(): List<Throwable> = generateSequence(exception) { cause ->
        cause.read(null) { this.cause }?.takeIf { it !== cause }
    }.take(MAX_REPORTED_EXCEPTIONS).toList()

    /**
     * The crash of the compiler backend while generating the [function], within the declaration of this [path] within the [scriptClass],
     * qualified like `<root>._`, if known, explained by the callable references of all the classes moved out of the script body to the members of the script class,
     * as the compiler stops at the first crash, if its root cause is the instance of the script class not available in the declaration moved out of the script body,
     * which has such references, or, if the declaration is not known, if it failed generating a callable reference.
     * Otherwise it is reported by the first line of the [message] and the first paragraph of the message of its root cause,
     * without the intermediate representation of the code, at the innermost declaration of the [path] found in the script, if any.
     */
    private fun StringBuilder.appendBackendCrash(
        diagnostic: ScriptDiagnostic,
        message: String,
        function: String,
        scriptClass: String?,
        path: List<String>?
    ) {
        val chain = diagnostic.exceptionChain()
        val rootCause = chain.lastOrNull()
        val rootCauseMessage = rootCause?.read(null) { this.message }?.substringBefore("\n\n")?.trimEnd()
        val references = source.scriptMemberCallableReferences
        val explained = references.isNotEmpty() && if (path != null && scriptClass != null) {
            // like `Non-mapped local declaration: VALUE_PARAMETER INSTANCE_RECEIVER ... type:<root>._`
            references.any { (reference) -> source.movedDeclarationAt(reference.start) == path.firstOrNull() }
                    && rootCauseMessage?.let { Regex("${Regex.escape(scriptClass)}(?![\\w.$])").containsMatchIn(it) } == true
        } else {
            // like the lowering of a bound callable reference, like `"a"::up`, naming no declaration
            chain.any { exception ->
                exception.read(emptyList()) { stackTrace.filterNotNull() }.any {
                    it.className.startsWith(BACKEND_PACKAGE) && "Reference" in it.className.substringAfterLast('.')
                }
            }
        }
        if (explained) {
            references.forEachIndexed { index, (reference, text) ->
                val start = reference.start
                val end = reference.end
                val name = reference.name
                if (index > 0) +'\n'
                appendPosition(start)
                appendSeverityAndMessage(
                    diagnostic.copy(message = "The compiler crashed generating the callable reference '$text'.", exception = null)
                )
                appendSourceLines(start, end)
                +"  hint: '${source.movedDeclarationAt(start)}' is compiled outside of the script body, where a callable reference to '$name' of the script crashes the compiler"
                +" - replace it with a lambda using '$name'.\n"
            }
            return
        }
        val range = when {
            path == null -> null
            // like the accessor `<get-x>` of the property `x`
            path.isEmpty() -> source.movedDeclarationPath(listOf(accessorFunction.matchEntire(function)?.groupValues?.get(1) ?: function))
            else -> source.movedDeclarationPath(path)
        }
        // the exception without a cause repeats the message, followed by the intermediate representation of the code
        val causeLine = rootCause?.takeIf { chain.size > 1 }?.let { cause ->
            listOfNotNull(cause.javaClass.name, rootCauseMessage).joinToString(": ")
        }
        range?.let { appendPosition(it.first) }
        appendSeverityAndMessage(
            diagnostic.copy(message = listOfNotNull(message, causeLine?.let { "Caused by: $it" }).joinToString("\n"), exception = null)
        )
        range?.let { appendSourceLines(it.first, it.second) }
    }

    /** The hint of the declaration moved out of the script body referring to the [name] declared at the 0-based [line] of the script body. */
    private fun StringBuilder.appendNotVisibleHint(
        declaration: String?,
        name: String,
        line: Int,
        /** Whether the [declaration] is an inline function, which cannot be local, unlike the regular one. */
        isInline: Boolean
    ) {
        +"  hint: '$declaration' is ${if (isInline) "an inline function " else ""}compiled outside of the script body, where '$name' (line ${line + 1}) is not visible"
        +" - pass it as a parameter, or declare it within '$declaration'.\n"
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
        position: ScriptPosition
    ) {
        val (line, kind) = source.bodyDeclarationLine(name, position) ?: return
        val declaration = source.movedDeclarationAt(position)
        if (declaration != null) {
            appendNotVisibleHint(declaration, name, line, source.isMovedInlineFunction(declaration))
        } else if (line > position.line) {
            // which cannot be declared preceding the calling one, see ScriptSource.Parsed.hoisted
            val calledBack = if (kind == FUNCTION) source.callsBack(name, position) else null
            // which would not be visible to it there
            val properties = if (kind == FUNCTION && calledBack == null) source.laterAccessedProperties(name, position) else emptyList()
            when {
                calledBack != null -> {
                    +"  hint: '$name' is declared at line ${line + 1}, and calls '$calledBack' back,"
                    +" while the functions of the script body accessing its local declarations are visible only after their declaration, so they cannot call each other"
                    +" - pass the local declarations they access as parameters instead.\n"
                }
                properties.isNotEmpty() -> {
                    val names = properties.joinToString { "'$it'" }
                    +"  hint: '$name' is declared at line ${line + 1}, and accesses $names declared after this call,"
                    +" while the declarations of the script body are visible only after their declaration"
                    +" - move them all before line ${position.line + 1}, or pass $names as ${if (properties.size == 1) "a parameter" else "parameters"}.\n"
                }
                else -> appendNotDeclaredYetHint(
                    name, line, when (kind) {
                        CLASS -> "classes"
                        FUNCTION -> "functions"
                        else -> "properties"
                    }, position
                )
            }
        }
    }

    /** The hint of the reference at the [position] to the [name] declared later, at the 0-based [line], among these [declarations], like `functions`. */
    private fun StringBuilder.appendNotDeclaredYetHint(
        name: String,
        line: Int,
        declarations: String,
        position: ScriptPosition
    ) {
        +"  hint: '$name' is declared at line ${line + 1}, while the $declarations of the script body are visible only after their declaration"
        +" - move it before line ${position.line + 1}.\n"
    }

}

/**
 * The failure message of the [throwable] failing the compilation internally, rather than the error of the script,
 * e.g. of the compiler, or of this library preparing the compilation,
 * referring to the script instead of the compiled [sources], see [toScriptLocations].
 */
internal fun internalFailureMessage(
    throwable: Throwable,
    sources: Collection<ScriptSource> = emptyList()
): String = buildString {
    +"error: The script could not be compiled because of an internal failure: "
    appendInternalFailure(throwable) { it.toScriptLocations(sources) }
}.trimEnd()

/**
 * This message, with the locations in the compiled [sources], like `$1.kts:6:15`, `$1.kts:6`, or `(6,15) in /$1.kts`,
 * replaced by the ones in the script, like `script.kts:2:15`, or just `script.kts`, if not in a line of the script.
 */
internal fun String.toScriptLocations(
    sources: Collection<ScriptSource>
): String = sources.fold(this) { message, source ->
    source.generatedLocations().replace(message) { match ->
        source.scriptLineAndColumn(match)?.let { (line, column) ->
            "script.kts:${line + 1}" + (column?.let { ":${it.coerceAtLeast(1)}" } ?: "")
        } ?: "script.kts"
    }
}

/** The locations in the [ScriptSource.text], see [toScriptLocations]. */
private fun ScriptSource.generatedLocations(): Regex {
    val name = Regex.escape(locationId)
    return Regex("\\((\\d+),(\\d+)\\) in /?$name|$name(?::(\\d+)(?::(\\d+))?)?")
}

private const val UNMATCHED_CLOSING_BRACE = "Syntax error: Unexpected '}' without matching '{'."

/**
 * The 0-based line of the script, and the 1-based column, if any, of the location matched by the [generatedLocations],
 * or `null`, if it is not in a line of the script.
 */
private fun ScriptSource.scriptLineAndColumn(
    match: MatchResult
): Pair<Int, Int?>? {
    val line = (match.groups[1] ?: match.groups[3])?.value?.toIntOrNull()?.let { toScriptLine(it) } ?: return null
    return line to (match.groups[2] ?: match.groups[4])?.value?.toIntOrNull()
}

/**
 * The [throwable] and its causes, following the [header], if any, without their stack traces, which are internal,
 * and without the ones just wrapping their causes, like the copies made by the coroutine stack trace recovery,
 * or described by the [header] already, with their messages bounded, and rewritten by the [rewrite], e.g. to replace the generated names.
 */
private fun StringBuilder.appendInternalFailure(
    throwable: Throwable,
    header: String? = null,
    rewrite: (String) -> String
) {
    var reported = 0
    fun appendMessage(message: String) {
        val rewritten = rewrite(message).trimEnd()
        val bounded = if (rewritten.length > MAX_INTERNAL_FAILURE_MESSAGE_LENGTH) {
            rewritten.take(MAX_INTERNAL_FAILURE_MESSAGE_LENGTH) + "... [TRUNCATED]"
        } else {
            rewritten
        }
        bounded.lines().joinTo(this, "\n  ")
        +'\n'
        reported++
    }
    header?.let { appendMessage(it) }
    val visited = Collections.newSetFromMap<Throwable>(IdentityHashMap())
    var current: Throwable? = throwable
    while (current != null && visited.add(current)) {
        val cause = current.read(null) { cause }
        val isWrapper = cause != null && cause !in visited && current.wraps(cause)
        if (!isWrapper && (header == null || !current.isDescribedBy(header))) {
            if (reported == MAX_INTERNAL_FAILURE_CAUSES) {
                +"  [FURTHER CAUSES OMITTED]\n"
                return
            }
            if (reported > 0) +"  Caused by: "
            +current.javaClass.name
            val message = current.read(null) { message }
            if (message != null) {
                +": "
                appendMessage(message)
            } else {
                +'\n'
                reported++
            }
        }
        current = cause
    }
}

/**
 * Whether this exception just wraps the [cause], like `RuntimeException(cause)`,
 * or its copy made by the coroutine stack trace recovery,
 * or `While analysing $1.kts:6:15: ` followed by the description of the cause.
 */
private fun Throwable.wraps(
    cause: Throwable
): Boolean {
    val message = read(null) { message }
    if (javaClass == cause.javaClass && message == cause.read(null) { this.message }) return true
    return message != null && cause.isDescribedBy(message)
}

/** Whether the [message] is this exception's own, or its description, or ends with the description including its message. */
private fun Throwable.isDescribedBy(
    message: String
): Boolean {
    val ownMessage = read(null) { this.message }
    val description = description()
    return message == ownMessage
            || message == description
            || message == read(null) { toString() }
            // the description of an exception without a message is just its class name, which the message might only mention
            || (ownMessage != null && message.endsWith(": $description"))
}

/** The class name and the message, unless it cannot be read, as the exception might override it. */
private fun Throwable.description(): String = listOfNotNull(
    javaClass.name,
    read(null) { message }
).joinToString(": ")

/** Like in [StackTraceElement.toString], e.g. `Native Method`, instead of the negative line number. */
private val StackTraceElement.source: String get() = when {
    isNativeMethod -> "Native Method"
    fileName == null -> "Unknown Source"
    lineNumber >= 0 -> "$fileName:$lineNumber"
    else -> fileName
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
