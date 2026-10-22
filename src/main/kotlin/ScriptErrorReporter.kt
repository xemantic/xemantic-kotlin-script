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

/** The compile-time constant required, like an annotation argument, or the default value of an annotation parameter, reported differently for the members. */
private val constantRequired = Regex(".* must be a compile-time constant\\.|Only 'const val' can be used in constant expressions\\.")

/** Reported without a location, e.g. for an object accessing the dependencies, which are the members of the script class. */
private val declarationCapturingScript = Regex("(Object|Enum class|Enum entry|Interface|Nested class) '(.+)' captures the script class instance\\..*")

/**
 * The first line of the failure of the compiler backend, reported without a location, followed by the intermediate representation of the code,
 * with the name of the generated function, if it is one, and the path of the declaration containing it within the script class, if any, like `T.I`.
 */
private val backendCrash = Regex("Exception while generating code for (?:.* fun (\\S+) .*|.*) declared in (.+):")

/** A part of a binary or canonical class name, like `$Lid` of `_$Box$Lid`, possibly empty, like in `_$$$result$1$1$Dog`. */
private val classNamePart = Regex("([.$])([\\p{L}\\p{N}_]*)")

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
    private fun List<ScriptDiagnostic>.describe(): List<String> {
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
            buildString { appendDiagnostic(diagnostic, occurrence, unresolved) }
        }.distinct()
    }

    /** The failure recognized in the [source] before compiling it. */
    fun toSourceFailureMessage(): String = source.unmatchedClosingBrace?.let {
        // the brace would close the script block, misplacing all the errors
        toUnmatchedClosingBraceMessage(it)
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
        references: List<LaterDeclaredReference>
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

    /** The [references] of the script body to the local properties instead of the functions, see [ScriptSource.shadowedFunctionCandidates]. */
    fun toShadowedFunctionMessage(
        references: List<Triple<ScriptReference, Int, Int>>
    ): String = references.joinToString(separator = "\n") { (reference, property, function) ->
        buildString {
            appendPosition(reference.start)
            +"error: '${reference.name}' refers to the property of the script body declared at line ${property + 1}, "
            +"instead of the function declared at line ${function + 1}, which a regular script calls.\n"
            appendSourceLines(reference.start, reference.end)
            +"  hint: rename the property or the function.\n"
        }
    }.trimEnd()

    /** The [references] of the declarations moved out of the script body to other functions, see [ScriptSource.hiddenLocalFunctionCandidates]. */
    fun toHiddenLocalFunctionMessage(
        references: List<LaterDeclaredReference>
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

    /** The [references] of the script body to the dependencies, preceding the local properties of their names, see [ScriptSource.laterDeclaredDependencyCandidates]. */
    fun toLaterDeclaredDependencyMessage(
        references: List<LaterDeclaredReference>
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
        +throwable.javaClass.className()
        val message = try {
            throwable.message
        } catch (e: Throwable) {
            val description = listOfNotNull(e.javaClass.name, e.read(null) { message }).joinToString(": ")
            "[message unavailable: $description]"
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
        appendSeverityAndMessage(diagnostic)
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
        position: ScriptPosition
    ) {
        val (line, kind) = source.bodyDeclarationLine(name, position) ?: return
        val declaration = source.movedDeclarationAt(position)
        if (declaration != null) {
            appendNotVisibleHint(declaration, name, line)
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
