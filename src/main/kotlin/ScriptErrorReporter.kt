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

/** The most causes of an internal failure reported, as they tell little about the script. */
private const val MAX_INTERNAL_FAILURE_CAUSES = 8

/** The longest message of an internal failure reported, as it might carry a dump of the bytecode, or of the source. */
private const val MAX_INTERNAL_FAILURE_MESSAGE_LENGTH = 500

/** The error of the unresolved reference, with its name. */
internal val unresolvedReference = Regex("Unresolved reference '(.+)'\\.")

/** Reported without a location, e.g. for an object accessing the dependencies, which are the members of the script class. */
private val declarationCapturingScript = Regex("(Object|Enum class|Enum entry|Interface|Nested class) '(.+)' captures the script class instance\\..*")

/**
 * The first line of the failure of the compiler backend, reported without a location, followed by the intermediate representation of the code,
 * with the name of the generated function, if it is one, and the path of the declaration containing it within the script class, if any, like `T.I`.
 */
private val backendCrash = Regex("Exception while generating code for (?:.* fun (\\S+) .*|.*) declared in (.+):")

/** A part of a binary or canonical class name, like `$Lid` of `_$Box$Lid`, possibly empty, like in `_$$$result$1$1$Dog`. */
private val classNamePart = Regex("([.$])([\\p{L}\\p{N}_]*)")

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
 */
internal class ScriptErrorReporter(
    private val source: ScriptSource,
    private val scriptClassName: String? = null,
    private val blockClassName: String? = null
) {

    /**
     * The name of a class qualified by the script class, like `_.Color` of `Enum.valueOf`, or `_$Box$Lid` of `ClassCastException`,
     * and of the default string representation, as long as the binary name continues, not preceded by a part of another name,
     * matching the parts with a single character class, as a repeated group would overflow the stack on a long message.
     */
    private val scriptQualifiedName = scriptClassName?.let {
        // also the element class of an array, like `[L_$Circle;`
        Regex("(?:(?<=\\[L)|(?<![\\p{L}\\p{N}_.$]))${Regex.escape(it)}([.$][\\p{L}\\p{N}_.$]*)")
    }

    /** The names qualified by the script class written in the string literals of the script, like `_.Color`, which are kept. */
    private val writtenQualifiedNames by lazy {
        source.stringLiterals.flatMapTo(HashSet()) { literal ->
            scriptQualifiedName?.findAll(literal)?.mapNotNull { it.toDeclaredClassName()?.first }.orEmpty()
        }
    }

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
        return mapIndexed { index, diagnostic ->
            val occurrence = capturingClasses[index]?.let { kindAndName ->
                indices.merge(kindAndName, 1, Int::plus)!! - 1 to counts.getValue(kindAndName)
            }
            buildString {
                val exception = diagnostic.exception
                if (
                    compilation && exception != null && diagnostic.severity == ScriptDiagnostic.Severity.ERROR
                    // reported at the declarations of the script instead, see appendBackendCrash
                    && !(diagnostic.location == null && backendCrash.matches(diagnostic.message.lineSequence().first()))
                ) {
                    appendCompilerFailure(diagnostic, exception)
                } else {
                    appendDiagnostic(diagnostic, occurrence)
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
            appendNotVisibleHint(declaration, reference.name, hidden.line)
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
     * but not the names written in the string literals of the script.
     * The text built at runtime to look like such a name, like `"_" + ".Color"`, cannot be told from it, and is rewritten as well.
     */
    private fun String.withoutScriptClassName(): String = scriptQualifiedName?.replace(this) { match ->
        match.toDeclaredClassName()?.takeIf { (qualified) -> qualified !in writtenQualifiedNames }?.second ?: match.value
    } ?: this

    /**
     * The name qualified by the script class up to the classes declared in the script, like `_.Color` of `_.Color.BLUE`,
     * and the name as in the script, like `Color.BLUE`, or `null`, if it does not name a class declared in the script.
     * A canonical name, separated by dots, starts with the declared class, while a binary name might start with the synthetic names,
     * like `$$result$1$1$make`, which might be named like a class of the script as well, so the last sequence of the declared classes is taken.
     */
    private fun MatchResult.toDeclaredClassName(): Pair<String, String>? {
        val parts = classNamePart.findAll(groupValues[1]).map { it.groupValues[1] to it.groupValues[2] }.toList()
        val classNames = source.classNames
        val enclosingClassNames = source.enclosingClassNames
        val first = if (parts.first().first == ".") {
            0.takeIf { parts.first().second in classNames } ?: return null
        } else {
            val last = parts.indexOfLast { (_, name) -> name in classNames }.takeIf { it != -1 } ?: return null
            // unlike e.g. a function named like a class, enclosing a local class
            generateSequence(last) { index -> (index - 1).takeIf { it >= 0 && parts[it].second in enclosingClassNames } }.last().takeIf { start ->
                // the synthetic names are separated by dollars only
                parts.subList(1, start + 1).none { (separator) -> separator == "." }
            } ?: return null
        }
        var last = first
        while (parts[last].second in enclosingClassNames && parts.getOrNull(last + 1)?.second in classNames) last++
        fun List<Pair<String, String>>.join() = joinToString("") { (separator, name) -> separator + name }
        return scriptClassName + parts.take(last + 1).join() to
                parts.subList(first, last + 1).joinToString(".") { it.second } + parts.drop(last + 1).join()
    }

    private fun Class<*>.className(): String = when {
        // the synthetic name, like `Script$result$1$fail$1`, would not tell anything
        isAnonymousClass -> "<anonymous ${superclass.className()}>"
        // e.g. the classes declared in the script body
        isLocalClass -> simpleName
        scriptClassName != null && name.startsWith("$scriptClassName$") ->
            name.removePrefix("$scriptClassName$").replace('$', '.')
        else -> name
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
                    location = "${element.functionName(scriptLine)}(script.kts:${scriptLine + 1})",
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
     * the accessors told by the 0-based [scriptLine] of the frame, if any.
     */
    private fun StackTraceElement.functionName(
        scriptLine: Int? = null
    ): String {
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
                // the local functions of an accessor are named after it, like `getX$inner`
                val function = scriptLine?.takeIf { methodParts.size == 1 }?.let { line ->
                    this@ScriptErrorReporter.source.accessorName(methodParts.single(), owner, line)
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
     */
    private fun StringBuilder.appendDiagnostic(
        diagnostic: ScriptDiagnostic,
        capturingOccurrence: Pair<Int, Int>?
    ) {
        val location = diagnostic.location
        location?.let { source.preambleDependencyAt(it.start.line) }?.let { dependency ->
            +"dependency '$dependency': "
            appendSeverityAndMessage(diagnostic)
            return
        }
        if (location == null) {
            val firstLine = diagnostic.message.lineSequence().first()
            backendCrash.matchEntire(firstLine)?.let { match ->
                // qualified by the package of the script, or by `<root>`, followed by the script class
                val qualifiedPath = match.groupValues[2]
                val path = listOf("<root>.", "${source.packageName}.").firstOrNull { qualifiedPath.startsWith(it) }?.let {
                    qualifiedPath.removePrefix(it).split('.').drop(1)
                }.orEmpty()
                appendBackendCrash(diagnostic, firstLine.removeSuffix(":"), match.groupValues[1], path)
                return
            }
        }
        val capturing = declarationCapturingScript.matchEntire(diagnostic.message)?.groupValues
        var capturingDeclaration = capturing?.get(2)
        val range = if (location != null) {
            val start = source.toScriptPosition(location.start.line, location.start.col)
            val end = location.end?.let {
                source.toScriptPosition(it.line, it.col)
            }?.takeIf {
                (it.line > start.line) || (it.line == start.line && it.column >= start.column)
            } ?: start
            start to end
        } else {
            capturingDeclaration?.let { name ->
                capturingOccurrence?.let { (index, count) -> source.movedClassName(name, index, count) }
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
            +"  hint: '$capturingDeclaration' is compiled outside of the script body, where $article $kind cannot access the dependencies,"
            +" nor the functions of the script - ${alternative}pass them as parameters.\n"
        }
    }

    /**
     * The crash of the compiler backend while generating the [function] within the declaration of this [path], if any,
     * explained by the callable references of all the classes moved out of the script body to the members of the script class,
     * if the declaration moved out of the script body containing the code has any,
     * as the compiler stops at the first crash, otherwise by the first line of the [message] and of its root cause,
     * without the intermediate representation of the code, at the innermost declaration of the [path] found in the script.
     */
    private fun StringBuilder.appendBackendCrash(
        diagnostic: ScriptDiagnostic,
        message: String,
        function: String,
        path: List<String>
    ) {
        val references = source.scriptMemberCallableReferences
        if (references.any { (reference) -> source.movedDeclarationAt(reference.start) == path.firstOrNull() }) {
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
        val range = source.movedDeclarationPath(path.ifEmpty { listOf(function) })
        val rootCause = generateSequence(diagnostic.exception) { cause ->
            cause.read(null) { this.cause }?.takeIf { it !== cause }
        }.take(MAX_REPORTED_EXCEPTIONS).lastOrNull()
        val causeLine = rootCause?.let { cause ->
            listOfNotNull(cause.javaClass.name, cause.read(null) { this.message }?.lineSequence()?.first()).joinToString(": ")
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
        line: Int
    ) {
        +"  hint: '$declaration' is compiled outside of the script body, where '$name' (line ${line + 1}) is not visible"
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
        position: ScriptPosition,
        preferFunction: Boolean = false
    ) {
        val (line, kind) = (if (preferFunction) source.bodyDeclarationLine(name, DeclarationKind.FUNCTION) else null)
            ?: source.bodyDeclarationLine(name) ?: return
        val declaration = source.movedDeclarationAt(position)
        if (declaration != null) {
            appendNotVisibleHint(declaration, name, line)
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
