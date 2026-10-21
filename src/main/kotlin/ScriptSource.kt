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
import org.jetbrains.kotlin.CoreEnvironmentDeprecation
import org.jetbrains.kotlin.cli.extensionsStorage
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.psi.PsiComment
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiErrorElement
import org.jetbrains.kotlin.com.intellij.psi.PsiWhiteSpace
import org.jetbrains.kotlin.com.intellij.psi.tree.IElementType
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.lexer.KotlinLexer
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtAnnotation
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtArrayAccessExpression
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCallableReferenceExpression
import org.jetbrains.kotlin.psi.KtCatchClause
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassBody
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtConstantExpression
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtDeclarationWithBody
import org.jetbrains.kotlin.psi.KtDestructuringDeclaration
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtForExpression
import org.jetbrains.kotlin.psi.KtLabelReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtPrefixExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPropertyDelegate
import org.jetbrains.kotlin.psi.KtPropertyAccessor
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtOperationReferenceExpression
import org.jetbrains.kotlin.psi.KtTypeAlias
import org.jetbrains.kotlin.psi.KtUnaryExpression
import org.jetbrains.kotlin.psi.KtUserType
import org.jetbrains.kotlin.psi.KtValueArgumentName
import org.jetbrains.kotlin.psi.KtWhenExpression
import org.jetbrains.kotlin.psi.psiUtil.allChildren
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.jetbrains.kotlin.psi.psiUtil.endOffset
import org.jetbrains.kotlin.psi.psiUtil.getReceiverExpression
import org.jetbrains.kotlin.psi.psiUtil.forEachDescendantOfType
import org.jetbrains.kotlin.psi.psiUtil.parents
import org.jetbrains.kotlin.psi.psiUtil.startOffset
import org.jetbrains.kotlin.psi.psiUtil.startOffsetSkippingComments
import java.util.IdentityHashMap
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.script.experimental.api.SourceCode

/**
 * A position in the original script, both the [line] and the [column] are 0-based.
 */
internal data class ScriptPosition(
    val line: Int,
    val column: Int
)

/** The reference to the [name] in the script, from the [start] to the [end], exclusive. */
internal class ScriptReference(
    val name: String,
    val start: ScriptPosition,
    val end: ScriptPosition
) {

    operator fun contains(
        position: ScriptPosition
    ): Boolean = (position.line > start.line || (position.line == start.line && position.column >= start.column))
            && (position.line < end.line || (position.line == end.line && position.column < end.column))

}

/**
 * The [reference] of the script body to a dependency, preceding the declaration of the local property,
 * or the local function, if [isFunction], of the same name, at the 0-based [line].
 */
internal class LaterDeclaredReference(
    val reference: ScriptReference,
    val line: Int,
    val isFunction: Boolean,
    /** Whether it refers to the declaration as a function, like `::foo`, which might be resolved to a property instead. */
    val isCallableReference: Boolean = false,
    /** Whether it is an operator, or a convention, like `[]` of `get`, sharing its position with other ones, like `set`. */
    val isOperator: Boolean = false
) {
    val kind: String get() = if (isFunction) "function" else "property"
}

/** The deprecation message of the dependencies, see [ScriptSource.laterDeclaredDependencyCandidates]. */
internal const val LATER_DECLARED_DEPENDENCY = "xemantic-kotlin-script: later declared dependency"

/** The deprecation message of the copies of the local functions, see [ScriptSource.laterDeclaredDependencyCandidates]. */
internal const val LATER_DECLARED_FUNCTION = "xemantic-kotlin-script: later declared function"

/** The deprecation message of the copies of the local functions, see [ScriptSource.hiddenLocalFunctionCandidates]. */
internal const val HIDDEN_LOCAL_FUNCTION = "xemantic-kotlin-script: hidden local function"

/** The deprecation message of the copy of the local declaration of this [name], see [ScriptSource.hiddenLocalFunctionCandidates]. */
internal fun hiddenLocalFunctionMessage(
    name: String
): String = "$HIDDEN_LOCAL_FUNCTION: $name;"

/** The deprecation message of the copies of the moved functions, see [ScriptSource.shadowedFunctionCandidates]. */
internal const val SHADOWED_FUNCTION = "xemantic-kotlin-script: shadowed function"

/*
 * The names the script body resolves on its receiver, the coroutine scope, or on the nested ones, like in `launch { }`,
 * instead of the dependencies, or the declarations moved out of the script body, of these names, as the receivers take precedence, like `size` in `list.run { size }`:
 * the members of `CoroutineScope` and `Any`, the extensions of `CoroutineScope` imported by default from `kotlinx.coroutines`,
 * and the extensions of any receiver imported by default from `kotlin`.
 * The functions are resolved instead of the dependencies called like them only.
 */

internal val coroutineScopeProperties = setOf("coroutineContext", "isActive", "javaClass")

internal val coroutineScopeFunctions = setOf(
    "toString", "hashCode", "equals",
    "launch", "async", "cancel", "ensureActive", "plus", "newCoroutineContext",
    "let", "run", "also", "apply", "takeIf", "takeUnless", "to", "runCatching"
)

/** The opt-in message of the moved declarations, see [ScriptSource.coroutineScopeCandidates]. */
internal const val COROUTINE_SCOPE_ORIGINAL = "xemantic-kotlin-script: coroutine scope original"

/** The opt-in message of the copies of the moved functions, see [ScriptSource.coroutineScopeCandidates]. */
internal const val COROUTINE_SCOPE_FUNCTION = "xemantic-kotlin-script: coroutine scope function"

/** The opt-in message of the moved functions, see [ScriptSource.shadowedFunctionCandidates]. */
internal const val SHADOWED_ORIGINAL = "xemantic-kotlin-script: shadowed original"

/** The opt-in annotation marking the local copies of the declarations, reporting this [message] where they are used. */
private fun optInMarker(
    message: String
): String = "`_OptIn_${message.substringAfter(": ").replace(' ', '_')}`"

/** A dependency, as declared in the preamble of the [ScriptSource]. */
internal class PreambleDependency(
    val name: String,
    /** The declaration of the property of the dependency, like `val x: T = _dependency_x as T`. */
    val accessor: String,
    /** Whether it can be called like a function, so a local function of the same name shadows it. */
    val isCallable: Boolean = false
)

/** The class of the outer receiver of the script body, declaring the shadowed dependencies, see [ScriptSource]. */
internal const val DEPENDENCIES_CLASS_NAME = "_ScriptDependencies"

private const val DEPENDENCIES_CLASS = "`$DEPENDENCIES_CLASS_NAME`"

/** The line starting the script block in the [ScriptSource.text]. */
internal const val SCRIPT_BLOCK_START = "_script($DEPENDENCIES_CLASS()) { {"

/** The line ending the script block in the [ScriptSource.text]. */
internal const val SCRIPT_BLOCK_END = "} }"

/**
 * The script turned into a block, which can be run in the coroutine of the caller:
 *
 * ```
 * <file annotations, package and imports>
 * <the dependency accessors, unless shadowed by the local declarations of the script body>
 * class _ScriptDependencies {
 * <the accessors of the shadowed dependencies>
 * }
 * <declarations which cannot be local>
 * _script(_ScriptDependencies()) { {
 * <the rest of the script>
 * } }
 * ```
 *
 * The script refers to the dependencies as the members of the script,
 * resolved after the members of the receivers, like `size` in `list.run { size }`.
 * A dependency shadowed by a local declaration of the script body is not declared there,
 * leaving the references of the declarations moved out of the script body unresolved,
 * as in the script they refer to the local declaration,
 * while the script body refers to it as the member of the outer receiver of the script block,
 * the inner one being the coroutine scope of the caller.
 *
 * Declarations are moved by blanking them out with spaces in one copy of their lines,
 * and everything else in the other, so the columns of the script are retained,
 * while each line of the [text] maps back to the line of the script.
 */
internal class ScriptSource(
    source: String,
    dependencies: List<PreambleDependency> = emptyList(),
    /**
     * Whether it is the source of the compilation confirming the [laterDeclaredDependencyCandidates]
     * and the [hiddenLocalFunctionCandidates], with the declarations they might refer to deprecated, with a warning, as the declarations deprecated with an error are less preferred by the overload resolution,
     * which does not change how the references are resolved, unlike omitting them,
     * while the declarations the candidates should refer to are declared as their deprecated copies,
     * and the suppressions by the script are blanked out.
     */
    private val confirming: Boolean = false,
    /**
     * Whether the confirming compilation declares the copies of the moved functions preceding the [shadowedFunctionCandidates],
     * recognizing whether they apply, instead of deprecating the moved functions themselves,
     * recognizing whether the candidates refer to them already.
     */
    private val shadowedFunctionCopies: Boolean = false,
    /**
     * Whether the confirming compilation declares the copies of the moved functions preceding the [coroutineScopeCandidates],
     * recognizing whether they apply, in a compilation of its own, as they precede the local properties shadowing them,
     * unlike the copies of [shadowedFunctionCopies].
     */
    private val coroutineScopeCopies: Boolean = false
) : SourceCode {

    val scriptLines: List<String>

    override val text: String

    override val name: String? = null

    override val locationId: String

    /** The first `}` closing the script block, which would otherwise end the script early. */
    val unmatchedClosingBrace: ScriptPosition?

    /**
     * Whether the script ends with a declaration moved out of the script body, so it returns `Unit`,
     * while the block returns `null` instead, as `Unit` might be shadowed by a declaration or a dependency of the script.
     */
    val returnsUnit: Boolean

    /** The properties moved out of the script body, which have the names of the dependencies, conflicting with their accessors. */
    val conflictingDependencyDeclarations: List<ScriptReference>

    /**
     * The calls of the script body to the local properties, which have the names of the functions moved out of the script body,
     * by the 0-based lines of the properties and the functions, while in the script they call the functions, if they apply.
     * They are confirmed by compiling the script with the functions marked with an opt-in, see [confirming],
     * reporting the calls referring to them already, and the remaining ones by compiling it with their copies,
     * declared as the local functions preceding the calls, which they refer to, if the functions apply, see [shadowedFunctionCopies].
     */
    val shadowedFunctionCandidates: List<Triple<ScriptReference, Int, Int>>

    /**
     * The references of the script body to the declarations moved out of it, named like the members or the extensions
     * of its coroutine scope receiver, like `cancel`, by the 0-based lines of the declarations, which might be resolved
     * to the coroutine scope instead, while in the script they refer to the declarations.
     * They are confirmed by compiling the script with the declarations marked with an opt-in, see [confirming],
     * reporting the ones not referring to them, the properties, and the functions, which apply, recognized by their copies,
     * see [coroutineScopeCopies], while the ones within the lambdas are not reported, as they might have the receivers.
     */
    val coroutineScopeCandidates: List<LaterDeclaredReference>

    /**
     * The references of the script body which might refer to the dependencies, preceding the declarations
     * of the local properties of the same names, like `x` in `fun show() = x` followed by `val x = 2`,
     * or of the local functions, when called, while in the script they refer to these declarations.
     * Recognized by the names only, they might refer e.g. to the members of the receivers instead,
     * so they are confirmed by compiling the script with these dependencies deprecated, see [confirming],
     * where the receiver of the script body declares also the deprecated copies of the signatures of these local functions,
     * which the calls refer to, if the local functions would be called in the script.
     */
    val laterDeclaredDependencyCandidates: List<LaterDeclaredReference>

    /**
     * The calls of the declarations moved out of the script body named like the local functions of the script body,
     * by the 0-based lines of the first ones, which are not visible there, so they might be resolved to other functions,
     * like `check` of the standard library, while in the script they refer to the local functions.
     * They are confirmed by compiling the script with the deprecated copies of the local functions, see [confirming],
     * as the members of the script, which the calls refer to, unless they refer e.g. to the inherited members.
     */
    val hiddenLocalFunctionCandidates: List<LaterDeclaredReference>

    private val lineStarts: List<Int>

    /** For each line of the [text], the line of the script, or `null` for the lines of the block and the preamble. */
    private val lineMapping: List<Int?>

    /** The names of the dependencies, by the 0-based lines of the [text] declaring their accessors. */
    private val preambleDependencies = mutableMapOf<Int, String>()

    private val movedDeclarations: List<Declaration>

    private val bodyDeclarations: List<Declaration>

    init {
        // dropped like by kotlinc, which would otherwise report it as a syntax error, counting it in the columns of the first line
        val script = source.removePrefix("\uFEFF")
        val lineBounds = lineBounds(script)
        lineStarts = lineBounds.map { it.first }
        scriptLines = lineBounds.map { script.substring(it.first, it.last + 1) }
        // the parser recognizes only `\n` as a line terminator, replacing each character keeps the offsets
        val structure = ScriptStructure(
            script.replace('\r', '\n'),
            dependencies.mapTo(HashSet()) { it.name },
            confirming,
            shadowedFunctionCopies,
            coroutineScopeCopies
        )
        unmatchedClosingBrace = structure.unmatchedClosingBrace?.let { position(it) }
        movedDeclarations = structure.movedDeclarations
        returnsUnit = structure.endsWithMovedDeclaration
        conflictingDependencyDeclarations = structure.conflictingDependencyDeclarations.map { (name, range) ->
            ScriptReference(name, position(range.first), position(range.last + 1))
        }
        fun Reference.toScriptReference() = ScriptReference(name, position(offset), position(offset + length))
        fun Triple<Reference, Int, Boolean>.toLaterDeclaredReference() = LaterDeclaredReference(
            first.toScriptReference(), position(second).line, third, first.isCallableReference, first.isOperator
        )
        laterDeclaredDependencyCandidates = structure.laterDeclaredDependencyCandidates.map { it.toLaterDeclaredReference() }
        hiddenLocalFunctionCandidates = structure.hiddenLocalFunctionCandidates.map { it.toLaterDeclaredReference() }
        shadowedFunctionCandidates = structure.shadowedFunctionCandidates.map { (reference, property, function) ->
            Triple(reference.toScriptReference(), position(property).line, position(function).line)
        }
        coroutineScopeCandidates = structure.coroutineScopeCandidates.map { it.toLaterDeclaredReference() }
        bodyDeclarations = structure.bodyDeclarations
        val deprecatedDependencies = if (confirming) {
            laterDeclaredDependencyCandidates.mapTo(HashSet()) { it.reference.name }
        } else {
            emptySet()
        }

        val lines = mutableListOf<String>()
        val mapping = mutableListOf<Int?>()
        // the confirming compilation reports the deprecations, even if suppressed by the script
        val unsuppressed = BooleanArray(script.length)
        if (confirming) structure.suppressions.forEach { unsuppressed.fill(true, it.first, it.last + 1) }
        fun addLines(
            range: IntRange,
            keep: (offset: Int) -> Boolean
        ) = range.forEach { line ->
            val bounds = lineBounds[line]
            lines += buildString {
                bounds.forEach { offset ->
                    +(if (keep(offset) && !unsuppressed[offset]) script[offset] else ' ')
                }
            }
            mapping += line
        }
        fun lineOf(offset: Int) = lineOf(lineStarts, offset)
        val excluded = BooleanArray(script.length)
        movedDeclarations.forEach { excluded.fill(true, it.start, it.end) }
        structure.blankedRanges.forEach { excluded.fill(true, it.first, it.last + 1) }
        val movedExcluded = BooleanArray(script.length)
        // the comment runs to the end of the script, like in a regular script
        structure.unterminatedComment?.let {
            excluded.fill(true, it, script.length)
            movedExcluded.fill(true, it, script.length)
        }
        structure.movedBlankedRanges.forEach { movedExcluded.fill(true, it.first, it.last + 1) }

        val headerEnd = structure.headerEnd
        if (headerEnd > 0) {
            addLines(0..lineOf(headerEnd - 1)) { it < headerEnd }
        }
        fun addAccessor(
            dependency: PreambleDependency
        ) {
            preambleDependencies[lines.size] = dependency.name
            lines += if (dependency.name in deprecatedDependencies) {
                "@kotlin.Deprecated(\"$LATER_DECLARED_DEPENDENCY\") ${dependency.accessor}"
            } else {
                dependency.accessor
            }
            mapping += null
        }
        if (confirming) {
            // named in the message, as the operators of the same position refer to different functions, like `get` and `set`
            structure.hiddenLocalFunctionCopies.forEach { (name, declaration) ->
                lines += "@kotlin.Deprecated(\"${hiddenLocalFunctionMessage(name)}\") $declaration"
                mapping += null
            }
            // the deprecation of the local functions, like the copies in the script block, is not reported, unlike their opt-in
            listOf(LATER_DECLARED_FUNCTION, SHADOWED_FUNCTION, SHADOWED_ORIGINAL, COROUTINE_SCOPE_ORIGINAL, COROUTINE_SCOPE_FUNCTION).forEach { message ->
                lines += "@kotlin.RequiresOptIn(\"$message\", level = RequiresOptIn.Level.WARNING) annotation class ${optInMarker(message)}"
                mapping += null
            }
        }
        val (shadowed, visible) = dependencies.partition {
            it.name in structure.localProperties || (it.isCallable && it.name in structure.localFunctions)
        }
        visible.forEach { addAccessor(it) }
        lines += "class $DEPENDENCIES_CLASS {"
        mapping += null
        // the members of the outer receiver of the script body would be called instead of the moved functions
        shadowed.filterNot { it.isCallable && it.name in structure.movedFunctions }.forEach { addAccessor(it) }
        lines += "}"
        mapping += null
        val markedOriginals = confirming && !shadowedFunctionCopies && !coroutineScopeCopies
        val deprecatedFunctions = if (markedOriginals) {
            shadowedFunctionCandidates.mapTo(HashSet()) { it.first.name }
        } else {
            emptySet()
        }
        val coroutineScopeDeclarations = if (markedOriginals) {
            coroutineScopeCandidates.mapTo(HashSet()) { it.reference.name }
        } else {
            emptySet()
        }
        movedDeclarations.forEach { declaration ->
            // opt-in markers, which can mark the same declaration, unlike the deprecations
            if (declaration.kind == DeclarationKind.FUNCTION && declaration.name in deprecatedFunctions) {
                lines += "@${optInMarker(SHADOWED_ORIGINAL)}"
                mapping += null
            }
            if (declaration.name in coroutineScopeDeclarations) {
                lines += "@${optInMarker(COROUTINE_SCOPE_ORIGINAL)}"
                mapping += null
            }
            addLines(lineOf(declaration.start)..lineOf(declaration.end - 1)) {
                it in declaration.start until declaration.end && !movedExcluded[it]
            }
        }
        lines += SCRIPT_BLOCK_START
        mapping += null
        // the copies preceding the statements, where the local classes declared before them are visible
        val blockCopies = structure.blockCopies.entries.groupBy({ lineOf(it.key) }) { it.value }
        (lineOf(headerEnd)..lineBounds.lastIndex).forEach { line ->
            blockCopies[line]?.flatten()?.forEach { (messages, declaration) ->
                lines += messages.joinToString(separator = " ", postfix = " ") { "@${optInMarker(it)}" } + declaration
                mapping += null
            }
            addLines(line..line) { offset ->
                offset >= headerEnd && !excluded[offset]
            }
        }
        if (returnsUnit) {
            // the block would return the value of the statement before the declaration, unlike the script
            lines += "null"
            mapping += null
        }
        lines += SCRIPT_BLOCK_END
        mapping += null

        text = lines.joinToString(separator = "\n")
        lineMapping = mapping
        // unique, as the frames of the script are recognized by it, also among the frames of other scripts
        @OptIn(ExperimentalAtomicApi::class)
        locationId = "\$${sourceIdSeq.incrementAndFetch()}.kts"
    }

    /** The end of the last non-blank line, where the errors of the script block are reported. */
    val endPosition: ScriptPosition = scriptLines.indexOfLast { it.isNotBlank() }.let { line ->
        if (line == -1) ScriptPosition(0, 0) else ScriptPosition(line, scriptLines[line].length)
    }

    /**
     * Maps the 1-based [line] and [column] of the [text] to the position in the script.
     */
    fun toScriptPosition(
        line: Int,
        column: Int
    ): ScriptPosition {
        val scriptLine = toScriptLine(line) ?: return endPosition
        return ScriptPosition(scriptLine, (column - 1).coerceAtLeast(0))
    }

    /**
     * Maps the 1-based [line] of the [text] to the 0-based line of the script,
     * or `null`, if it is a line of the script block.
     */
    fun toScriptLine(
        line: Int
    ): Int? = lineMapping.getOrNull(line - 1)

    /**
     * The name of the dependency, whose accessor is declared at the 1-based [line] of the [text],
     * or `null`, if it is not a line of the preamble.
     */
    fun preambleDependencyAt(
        line: Int
    ): String? = preambleDependencies[line - 1]

    /**
     * The name of the declaration moved out of the script body, which contains the [position].
     */
    fun movedDeclarationAt(
        position: ScriptPosition
    ): String? {
        val offset = lineStarts[position.line] + position.column
        return movedDeclarations.find { offset in it.start until it.end }?.name
    }

    /**
     * The start and the end of the name of the declaration moved out of the script body,
     * or of the class nested in it, as the compiler names it without the enclosing classes.
     */
    fun movedDeclarationName(
        name: String
    ): Pair<ScriptPosition, ScriptPosition>? = (
            movedDeclarations.find { it.name == name }?.nameRange
                ?: movedDeclarations.firstNotNullOfOrNull { it.nestedNameRanges[name] }
    )?.let {
        position(it.first) to position(it.last + 1)
    }

    /**
     * The 0-based line of the declaration of the [name] in the script body, and its kind.
     */
    fun bodyDeclarationLine(
        name: String,
        kind: DeclarationKind? = null
    ): Pair<Int, DeclarationKind>? = bodyDeclarations.find {
        (it.name == name || name in it.destructuredNames) && (kind == null || it.kind == kind)
    }?.let {
        position(it.start).line to it.kind
    }

    private fun position(
        offset: Int
    ): ScriptPosition {
        val line = lineOf(lineStarts, offset)
        return ScriptPosition(line, offset - lineStarts[line])
    }

}

@OptIn(ExperimentalAtomicApi::class)
private val sourceIdSeq = AtomicLong(0)

/** The index of the line containing the [offset], given the sorted [lineStarts]. */
private fun lineOf(
    lineStarts: List<Int>,
    offset: Int
): Int = lineStarts.binarySearch(offset).let { if (it >= 0) it else -it - 2 }

/** The range of the characters of each line, excluding the line terminator. */
private fun lineBounds(
    script: String
): List<IntRange> = buildList {
    var start = 0
    var index = 0
    while (index < script.length) {
        when (script[index]) {
            '\n' -> {
                add(start until index)
                start = index + 1
            }
            '\r' -> {
                add(start until index)
                if (script.getOrNull(index + 1) == '\n') index++
                start = index + 1
            }
        }
        index++
    }
    add(start until script.length)
}

private class Declaration(
    val name: String?,
    val kind: DeclarationKind,
    val start: Int,
    val end: Int,
    val nameRange: IntRange?,
    /** The names of a destructuring declaration, like `val (a, b) = pair`. */
    val destructuredNames: List<String> = emptyList(),
    /** The ranges of the names of the classes nested in the declaration, by these names, the first one of each name. */
    val nestedNameRanges: Map<String, IntRange> = emptyMap()
)

internal enum class DeclarationKind {
    CLASS,
    INTERFACE,
    OBJECT,
    TYPE_ALIAS,
    FUNCTION,
    PROPERTY
}

private val visibilityModifiers = setOf(
    KtTokens.PUBLIC_KEYWORD, KtTokens.PRIVATE_KEYWORD, KtTokens.INTERNAL_KEYWORD, KtTokens.PROTECTED_KEYWORD
)

/**
 * The modifiers of top-level functions and properties, which are not applicable to local ones,
 * while the declarations moved out of the script body are the members of the script class, where `const` is not allowed either.
 */
private val memberOnlyModifiers = setOf(KtTokens.OPEN_KEYWORD, KtTokens.FINAL_KEYWORD, KtTokens.CONST_KEYWORD)

/** The modifiers of classes, which cannot be local. */
private val nonLocalClassModifiers = listOf(
    KtTokens.ENUM_KEYWORD, KtTokens.SEALED_KEYWORD, KtTokens.ANNOTATION_KEYWORD, KtTokens.VALUE_KEYWORD, KtTokens.INLINE_KEYWORD
)

/** The annotations, which are not applicable to local declarations. */
private val memberOnlyAnnotations = setOf("Volatile", "Transient", "JvmField", "JvmName", "JvmOverloads")

private fun KtDeclaration.memberOnlyAnnotationEntries(): List<KtAnnotationEntry> = annotationEntries.filter {
    it.useSiteTarget != null || it.shortName?.asString() in memberOnlyAnnotations
}

/**
 * The declaration of a function of the same signature, which is never called, like `fun f(s: String): Nothing = kotlin.TODO()`,
 * without the default values of the parameters, which might refer to the local declarations.
 */
private fun KtNamedFunction.signature(): String? = if (
    typeParameters.any { it.nameIdentifier == null } || valueParameters.any { it.typeReference == null || it.name == null }
) {
    // e.g. of a script with a syntax error
    null
} else buildString {
    listOf(KtTokens.SUSPEND_KEYWORD, KtTokens.INFIX_KEYWORD, KtTokens.OPERATOR_KEYWORD).filter { hasModifier(it) }.forEach {
        +it.value
        +" "
    }
    +"fun "
    if (typeParameters.isNotEmpty()) {
        +typeParameters.joinToString(prefix = "<", postfix = "> ") { parameter ->
            // as written, as the quoted name is not recognized by the `where` constraints
            parameter.nameIdentifier?.text + (parameter.extendsBound?.let { ": ${it.singleLineText()}" } ?: "")
        }
    }
    receiverTypeReference?.let {
        +"("
        +it.singleLineText()
        +")."
    }
    +"`$name`("
    +valueParameters.joinToString { parameter ->
        (if (parameter.isVarArg) "vararg " else "") + "`${parameter.name}`: ${parameter.typeReference?.singleLineText()}" +
                (if (parameter.hasDefaultValue()) " = kotlin.TODO()" else "")
    }
    // keeping the type of the call, which might be an argument of another one
    +"): ${typeReference?.singleLineText() ?: "Nothing"}"
    typeConstraintList?.let {
        +" where "
        +it.singleLineText()
    }
    +" = kotlin.TODO()"
}

/**
 * The function type of the property, which can be called, like `(String) -> Any?` of `val log = { m: String -> ... }`,
 * declared, or of its lambda with the typed parameters, or `null`, if it is not known.
 */
private fun KtProperty.functionType(
    /** The function of the script body of this name, like `rec` of `val print = ::rec`. */
    localFunction: (String) -> KtNamedFunction?,
    /** The type of the type alias of the script of this name, like `(Any?) -> Unit` of `typealias Printer = (Any?) -> Unit`. */
    typeAlias: (String) -> String?
): String? {
    // whatever it is, like a class with the invoke operator, as the compiler resolves the calls,
    // except the type alias of the script, which the calls are not resolved to the same way as to its function type
    typeReference?.let { type ->
        val text = type.singleLineText()
        return typeAlias(text.trim()) ?: text
    }
    fun List<KtParameter>.functionType(
        receiver: String? = null
    ): String? = if (any { it.typeReference == null || it.destructuringDeclaration != null }) null else {
        (receiver?.let { "$it." } ?: "") + joinToString(prefix = "(", postfix = ") -> Any?") { it.typeReference!!.singleLineText() }
    }
    return when (val initializer = initializer) {
        is KtLambdaExpression -> initializer.valueParameters.functionType()
        // like `fun(m: Any?) { ... }`
        is KtNamedFunction -> initializer.valueParameters.functionType(initializer.receiverTypeReference?.let { "(${it.singleLineText()})" })
        // like `::rec`
        is KtCallableReferenceExpression -> if (initializer.receiverExpression == null) {
            localFunction(initializer.callableReference.getReferencedName())
                ?.takeIf { it.receiverTypeReference == null && it.typeParameters.isEmpty() }
                ?.valueParameters?.functionType()
        } else {
            null
        }
        else -> null
    }
}

/** The text without the comments and the line breaks, as each declaration of the preamble is a single line. */
private fun PsiElement.singleLineText(): String = buildString {
    collectDescendantsOfType<PsiElement> { it.firstChild == null }.forEach { leaf ->
        +(if (leaf is PsiComment || leaf is PsiWhiteSpace) " " else leaf.text)
    }
}

/** Whether the expression is a literal, which can be evaluated at any time. */
private fun KtExpression?.isConstant(): Boolean = when (this) {
    is KtConstantExpression -> true
    is KtStringTemplateExpression -> !hasInterpolation()
    is KtPrefixExpression -> (operationToken == KtTokens.MINUS || operationToken == KtTokens.PLUS) && baseExpression.isConstant()
    is KtParenthesizedExpression -> expression.isConstant()
    else -> false
}

/**
 * Parses the scripts, without compiling them.
 *
 * The environment of the compiler is never disposed, which keeps its application environment,
 * shared with the scripting host, for the lifetime of the class loader.
 */
@OptIn(ExperimentalCompilerApi::class, CoreEnvironmentDeprecation::class, CompilerConfiguration.Internals::class)
private val psiFactory: KtPsiFactory by lazy {
    val environment = KotlinCoreEnvironment.createForProduction(
        Disposer.newDisposable(),
        CompilerConfiguration().apply {
            extensionsStorage = CompilerPluginRegistrar.ExtensionStorage()
        },
        EnvironmentConfigFiles.JVM_CONFIG_FILES
    )
    KtPsiFactory(environment.project, markGenerated = false)
}

/**
 * A reference by name, like `foo` in `foo()` or `Foo` in `x: Foo`.
 *
 * @param isQualified whether it has a receiver, like `size` in `list.size`,
 *   so it refers to a member, or an extension.
 * @param isBound whether the name is declared in the element the references are collected from,
 *   like a parameter, a local variable or a member, so it does not refer to a top-level declaration.
 */
private class Reference(
    val name: String,
    val offset: Int,
    val length: Int,
    val isQualified: Boolean,
    val isBound: Boolean,
    /** Whether it is called, like `foo` in `foo()`, or `foo` in `::foo`, or `until` in `0 until 20`, so it might refer to a function. */
    val isCall: Boolean,
    /** Whether it is a callable reference, like `foo` in `::foo`. */
    val isCallableReference: Boolean,
    /**
     * Whether it is within a declaration, which can access the members of the script, when moved out of the script body,
     * unlike e.g. an object, an enum class, an interface, or a nested class.
     */
    val canAccessScriptMembers: Boolean,
    /** Whether it is an operator, or a convention, like `+`, referring to the operator functions only, like `plus`. */
    val isOperator: Boolean = false,
    /** Whether it is within a lambda, which might have a receiver, so its resolution is not known without compiling. */
    val isInLambda: Boolean = false
) {

    /** Whether it might refer to one of the local declarations of the script body of these [names]. */
    fun refersToLocal(
        names: Set<String>
    ): Boolean = !isQualified && !isBound && name in names

}

/**
 * The references by name, in the order of the script, without the labels and the names of the arguments,
 * bound by the declarations within the [scope], exclusive.
 */
private fun PsiElement.references(
    scope: PsiElement,
    declaredNames: DeclaredNames
): List<Reference> = collectDescendantsOfType<KtSimpleNameExpression> {
    it !is KtLabelReferenceExpression && it.parent !is KtValueArgumentName
            && !(it is KtOperationReferenceExpression && it.getReferencedNameElementType() != KtTokens.IDENTIFIER)
}.map { expression ->
    val callableReference = expression.parent as? KtCallableReferenceExpression
    val name = expression.getReferencedName()
    Reference(
        name = name,
        offset = expression.startOffset,
        length = expression.textLength,
        isQualified = expression.getReceiverExpression() != null || (
                callableReference?.callableReference == expression && callableReference.receiverExpression != null
        ),
        isBound = isBound(expression, name, scope, declaredNames),
        // also referred to as a function, like `::println`, or called as infix, like `0 until 20`
        isCall = (expression.parent as? KtCallExpression)?.calleeExpression == expression
                || callableReference?.callableReference == expression
                || (expression is KtOperationReferenceExpression && expression.getReferencedNameElementType() == KtTokens.IDENTIFIER),
        isCallableReference = callableReference?.callableReference == expression,
        canAccessScriptMembers = canAccessScriptMembers(expression, scope),
        isInLambda = isInLambda(expression, scope)
    )
} + operatorReferences(scope)

/** The names of the operator functions called by the operators, like `plus` of `+`. */
private val operatorNames = mapOf(
    KtTokens.PLUS to listOf("plus"), KtTokens.MINUS to listOf("minus"), KtTokens.MUL to listOf("times"),
    KtTokens.DIV to listOf("div"), KtTokens.PERC to listOf("rem"), KtTokens.RANGE to listOf("rangeTo"),
    KtTokens.RANGE_UNTIL to listOf("rangeUntil"), KtTokens.IN_KEYWORD to listOf("contains"), KtTokens.NOT_IN to listOf("contains"),
    KtTokens.LT to listOf("compareTo"), KtTokens.GT to listOf("compareTo"),
    KtTokens.LTEQ to listOf("compareTo"), KtTokens.GTEQ to listOf("compareTo"),
    KtTokens.PLUSEQ to listOf("plusAssign", "plus"), KtTokens.MINUSEQ to listOf("minusAssign", "minus"),
    KtTokens.MULTEQ to listOf("timesAssign", "times"), KtTokens.DIVEQ to listOf("divAssign", "div"),
    KtTokens.PERCEQ to listOf("remAssign", "rem"), KtTokens.PLUSPLUS to listOf("inc"), KtTokens.MINUSMINUS to listOf("dec")
)

/** The names of the operator functions called by the prefix operators, like `unaryMinus` of `-`. */
private val prefixOperatorNames = mapOf(
    KtTokens.MINUS to listOf("unaryMinus"), KtTokens.PLUS to listOf("unaryPlus"), KtTokens.EXCL to listOf("not"),
    KtTokens.PLUSPLUS to listOf("inc"), KtTokens.MINUSMINUS to listOf("dec")
)

/**
 * The references to the operator functions called by the operators and the conventions,
 * like `plus` of `a + b`, `get` of `a[i]`, `iterator` of `for (x in a)`, `component1` of `val (a, b) = p`, or `invoke` of `f()()`,
 * which are qualified, by the operands, so they might refer to the extension functions only,
 * at the positions where the compiler reports the calls, like the whole array access expression,
 * which might contain the references of the operands, and other operators, like `set` and `get`,
 * so they are confirmed by the names of the functions as well, see [LaterDeclaredReference.isOperator].
 */
private fun PsiElement.operatorReferences(
    scope: PsiElement
): List<Reference> = buildList {
    fun add(
        names: List<String>,
        element: PsiElement,
        offset: Int = element.startOffset,
        length: Int = element.textLength
    ) {
        val canAccessScriptMembers = canAccessScriptMembers(element, scope)
        names.forEach { name ->
            add(
                Reference(
                    name = name,
                    offset = offset,
                    length = length,
                    isQualified = true,
                    isBound = false,
                    isCall = true,
                    isCallableReference = false,
                    canAccessScriptMembers = canAccessScriptMembers,
                    isOperator = true
                )
            )
        }
    }
    forEachDescendantOfType<KtElement> { element ->
        when (element) {
            is KtOperationReferenceExpression -> {
                val token = element.getReferencedNameElementType()
                if (token != KtTokens.IDENTIFIER) {
                    (if (element.parent is KtPrefixExpression) prefixOperatorNames[token] else operatorNames[token])?.let { add(it, element) }
                }
            }
            // the whole expression, where the compiler reports the call
            is KtArrayAccessExpression -> {
                val parent = element.parent
                val names = when {
                    parent is KtBinaryExpression && parent.left == element && parent.operationToken == KtTokens.EQ -> listOf("set")
                    parent is KtBinaryExpression && parent.left == element && parent.operationToken in KtTokens.AUGMENTED_ASSIGNMENTS ->
                        listOf("get", "set")
                    parent is KtUnaryExpression && (parent.operationToken == KtTokens.PLUSPLUS || parent.operationToken == KtTokens.MINUSMINUS) ->
                        listOf("get", "set")
                    else -> listOf("get")
                }
                add(names, element)
            }
            is KtForExpression -> element.loopRange?.let { add(listOf("iterator", "hasNext", "next"), element, it.startOffset, it.textLength) }
            is KtDestructuringDeclaration -> element.entries.forEachIndexed { index, entry ->
                add(listOf("component${index + 1}"), element, entry.startOffset, entry.textLength)
            }
            is KtCallExpression -> if (element.calleeExpression !is KtSimpleNameExpression && element.calleeExpression != null) {
                val arguments = element.valueArgumentList ?: element.lambdaArguments.firstOrNull() ?: element
                add(listOf("invoke"), element, arguments.startOffset, arguments.textLength)
            }
            is KtPropertyDelegate -> add(listOf("getValue", "setValue", "provideDelegate"), element)
            else -> {}
        }
    }
}

/** Whether the [expression] is within a lambda, which might have a receiver, within the [scope], exclusive. */
private fun isInLambda(
    expression: PsiElement,
    scope: PsiElement
): Boolean = expression.parents.takeWhile { it !== scope }.any { it is KtLambdaExpression }

/** See [Reference.canAccessScriptMembers]. */
private fun canAccessScriptMembers(
    expression: PsiElement,
    scope: PsiElement
): Boolean {
    var element = expression.parent
    while (element != null && element !== scope) {
        if (element is KtClassOrObject && !(element is KtObjectDeclaration && element.isObjectLiteral())) {
            if (element is KtObjectDeclaration || (element is KtClass && (element.isEnum() || element.isInterface()))) return false
            val outer = element.parents.takeWhile { it !== scope }.any {
                it is KtClassOrObject && !(it is KtObjectDeclaration && it.isObjectLiteral())
            }
            if (outer && !element.hasModifier(KtTokens.INNER_KEYWORD)) return false
        }
        element = element.parent
    }
    return true
}

/**
 * Whether the [name] referred to by the [expression] is declared in the enclosing elements within the [scope], exclusive,
 * recognized by the names only, without the declarations inherited from the supertypes.
 */
private fun isBound(
    expression: PsiElement,
    name: String,
    scope: PsiElement,
    declaredNames: DeclaredNames
): Boolean {
    var child = expression
    var parent = expression.parent
    // the plain parameters of the primary constructor are not visible in the functions of the class
    var inFunction = false
    while (parent != null && parent !== scope) {
        val declares = when (parent) {
            is KtDeclarationWithBody -> parent.valueParameters.any { it.declares(name) }
            is KtBlockExpression -> declaredNames.isDeclaredBefore(parent, child, name)
            is KtForExpression -> child === parent.body && parent.loopParameter?.declares(name) == true
            is KtCatchClause -> child === parent.catchBody && parent.catchParameter?.name == name
            is KtWhenExpression -> child !== parent.subjectVariable && parent.subjectVariable?.name == name
            is KtClassBody -> declaredNames.isDeclared(parent, name)
            is KtClassOrObject -> parent.primaryConstructorParameters.any {
                it.name == name && (it.hasValOrVar() || !inFunction)
            }
            else -> false
        }
        if (declares) return true
        if (parent is KtNamedFunction || parent is KtPropertyAccessor) inFunction = true
        child = parent
        parent = parent.parent
    }
    return false
}

private fun PsiElement.declares(
    name: String
): Boolean = name in declaredNames()

private fun PsiElement.declaredNames(): List<String> = when (this) {
    is KtParameter -> listOfNotNull(name) + destructuringDeclaration?.declaredNames().orEmpty()
    is KtDestructuringDeclaration -> entries.mapNotNull { it.name }
    is KtNamedDeclaration -> listOfNotNull(name)
    else -> emptyList()
}

/**
 * The names declared in the blocks and the class bodies, collected once for each of them,
 * as otherwise every reference would scan all the declarations of its enclosing ones.
 */
private class DeclaredNames {

    /** The index of the first statement declaring each name, by the blocks. */
    private val blockNames = IdentityHashMap<KtBlockExpression, Map<String, Int>>()

    /** The index of each statement in its block. */
    private val statementIndices = IdentityHashMap<PsiElement, Int>()

    private val classBodyNames = IdentityHashMap<KtClassBody, Set<String>>()

    /** Whether the [name] is declared in the [block] by a statement preceding the [child] of the block. */
    fun isDeclaredBefore(
        block: KtBlockExpression,
        child: PsiElement,
        name: String
    ): Boolean {
        val names = blockNames.getOrPut(block) {
            buildMap {
                block.statements.forEachIndexed { index, statement ->
                    statementIndices[statement] = index
                    statement.declaredNames().forEach { putIfAbsent(it, index) }
                }
            }
        }
        val declaration = names[name] ?: return false
        // a child which is not a statement, e.g. of an erroneous script, sees all the statements
        return declaration < (statementIndices[child] ?: Int.MAX_VALUE)
    }

    fun isDeclared(
        classBody: KtClassBody,
        name: String
    ): Boolean = name in classBodyNames.getOrPut(classBody) {
        classBody.declarations.flatMapTo(HashSet()) { it.declaredNames() }
    }

}

/**
 * The top-level structure of the script, recognized with the Kotlin parser:
 * the header of file annotations, package and imports, and the declarations
 * which cannot be local to the script block, see [ScriptSource].
 */
private class ScriptStructure(
    private val script: String,
    dependencyNames: Set<String>,
    /** Whether to compute the copies of the declarations, see [ScriptSource.confirming]. */
    confirming: Boolean,
    /** See [ScriptSource.shadowedFunctionCopies]. */
    shadowedFunctionCopies: Boolean,
    /** See [ScriptSource.coroutineScopeCopies]. */
    coroutineScopeCopies: Boolean
) {

    val headerEnd: Int

    val unmatchedClosingBrace: Int? = unmatchedClosingBrace(script)

    /** The start of the block comment ending the script unterminated, which would swallow the closing of the script block. */
    val unterminatedComment: Int? = unterminatedComment(script)

    val movedDeclarations: List<Declaration>

    val bodyDeclarations: List<Declaration>

    /** Whether the last statement of the script is a declaration moved out of the script body. */
    val endsWithMovedDeclaration: Boolean

    /** The names of the properties of the script body, which are local to the script block. */
    val localProperties: Set<String>

    /** The names of the functions of the script body, which are local to the script block. */
    val localFunctions: Set<String>

    /** The names of the functions moved out of the script body. */
    val movedFunctions: Set<String>

    /** See [ScriptSource.hiddenLocalFunctionCandidates], with the offsets of the first local declarations, and whether they are functions. */
    val hiddenLocalFunctionCandidates: List<Triple<Reference, Int, Boolean>>

    /** The deprecated copies of the local declarations, see [ScriptSource.hiddenLocalFunctionCandidates]. */
    val hiddenLocalFunctionCopies: List<Pair<String, String>>

    /** The ranges of the `@Suppress` annotations of the script, which might suppress the deprecations, see [ScriptSource.confirming]. */
    val suppressions: List<IntRange>

    /** See [ScriptSource.shadowedFunctionCandidates], with the offsets of the properties and the functions. */
    val shadowedFunctionCandidates: List<Triple<Reference, Int, Int>>

    /** See [ScriptSource.coroutineScopeCandidates], with the offsets of the declarations, and whether they are functions. */
    val coroutineScopeCandidates: List<Triple<Reference, Int, Boolean>>


    /**
     * The deprecated copies of the local functions, see [ScriptSource.laterDeclaredDependencyCandidates],
     * and of the moved functions, see [ScriptSource.shadowedFunctionCandidates],
     * with the messages of their opt-in markers, by the offsets of the statements they precede.
     */
    val blockCopies: Map<Int, List<Pair<List<String>, String>>>

    /** See [ScriptSource.laterDeclaredDependencyCandidates], with the offsets of the declarations, and whether they are functions. */
    val laterDeclaredDependencyCandidates: List<Triple<Reference, Int, Boolean>>

    /** The names of the [ScriptSource.conflictingDependencyDeclarations], and their ranges. */
    val conflictingDependencyDeclarations: List<Pair<String, IntRange>>

    /** The modifiers which are not applicable to local declarations. */
    val blankedRanges = mutableListOf<IntRange>()

    /** The modifiers of the moved declarations, which are not applicable to the members of the script class, like `const`. */
    val movedBlankedRanges = mutableListOf<IntRange>()

    private val declaredNames = DeclaredNames()

    init {
        val file = psiFactory.createFile("script.kts", script)
        headerEnd = listOfNotNull(
            file.firstChild?.takeIf { it.node.elementType == KtTokens.SHEBANG_COMMENT },
            // like `@file:JvmName("x")`, while the parser takes there also a stray `@` of the script body
            file.fileAnnotationList?.children?.lastOrNull {
                (it as? KtAnnotationEntry)?.useSiteTarget != null || (it as? KtAnnotation)?.useSiteTarget != null
            },
            file.packageDirective,
            file.importList
        ).filter { it.textLength > 0 }.maxOfOrNull { it.endOffset } ?: 0
        val block = file.script?.blockExpression
        val statements = block?.statements.orEmpty()
        val declarations = statements.mapNotNull { parseDeclaration(it) }
        val declarationsByPsi = declarations.associateBy { it.psi }
        val statementReferences = statements.map { statement ->
            declarationsByPsi[statement]?.references ?: statement.references(scope = statement.parent, declaredNames)
        }
        val bodyReferences = statementReferences.flatten()
        // only the properties which require it are moved
        val properties = declarations.filter { it.kind == DeclarationKind.PROPERTY && !it.requiresMove }
        val propertyNames = properties.flatMap { listOfNotNull(it.name) + it.destructuredNames }
        val propertyNameSet = propertyNames.toSet()
        val propertyNameCounts = propertyNames.groupingBy { it }.eachCount()
        // initialized with a literal, so they can be initialized at any time, moved with the declarations referring to them
        val constants = properties.filter { property ->
            val psi = property.psi as? KtProperty
            psi != null && !psi.isVar && psi.delegate == null && psi.initializer.isConstant() && property.accessors.isEmpty()
                    && property.name !in dependencyNames && property.name !in coroutineScopeProperties
                    && propertyNameCounts[property.name] == 1
        }
        val selected = selectMovedDeclarations(declarations, bodyReferences, propertyNameSet)
        // referred to by the moved declarations, which can access the members of the script, unlike e.g. an object
        val referredConstants = selected.flatMapTo(HashSet()) { declaration ->
            declaration.references.filter { it.canAccessScriptMembers && it.refersToLocal(propertyNameSet) }.map { it.name }
        }
        val movedConstants = constants.filter { it.name in referredConstants }
        val moved = (selected + movedConstants).sortedBy { it.start }.toCollection(LinkedHashSet())
        // `const` is not applicable to the members of the script class
        movedConstants.forEach { constant ->
            constant.psi.modifierList?.getModifier(KtTokens.CONST_KEYWORD)?.let {
                movedBlankedRanges += it.startOffset until it.endOffset
            }
        }
        localProperties = propertyNameSet - movedConstants.mapTo(HashSet()) { it.name!! }
        movedDeclarations = moved.map { it.toDeclaration(withNestedNames = true) }
        endsWithMovedDeclaration = moved.any { it.psi == statements.lastOrNull() }
        // unlike an extension function, which cannot be called like the dependency
        val (local, movedOut) = declarations.filter { it.kind == DeclarationKind.FUNCTION && !it.isExtension }.partition { it !in moved }
        localFunctions = local.mapNotNullTo(HashSet()) { it.name }
        movedFunctions = movedOut.mapNotNullTo(HashSet()) { it.name }
        // the first local property, and the first local function, of each name of a dependency, by the index of its statement
        val dependencyProperties = mutableMapOf<String, Pair<Int, ParsedDeclaration>>()
        val dependencyFunctions = mutableMapOf<String, Pair<Int, ParsedDeclaration>>()
        statements.forEachIndexed { index, statement ->
            val declaration = declarationsByPsi[statement]?.takeIf { it !in moved } ?: return@forEachIndexed
            when {
                declaration.kind == DeclarationKind.PROPERTY -> listOfNotNull(declaration.name) + declaration.destructuredNames
                declaration in local -> listOfNotNull(declaration.name)
                else -> emptyList()
            }.filter { it in dependencyNames }.forEach {
                val declarations = if (declaration.kind == DeclarationKind.PROPERTY) dependencyProperties else dependencyFunctions
                declarations.putIfAbsent(it, index to declaration)
            }
        }
        fun ParsedDeclaration.line() = nameRange?.first ?: start
        fun ParsedDeclaration.signature() = (psi as KtNamedFunction).signature()
        val localClasses = declarations.filter { it.kind == DeclarationKind.CLASS && it !in moved && it.name != null }
        /** Whether the function refers to the types of these [names], like the local classes, which might not be visible to its copy. */
        fun ParsedDeclaration.refersToTypes(
            names: Set<String>
        ): Boolean = (psi as KtNamedFunction).let { function ->
            listOfNotNull(
                function.receiverTypeReference, function.valueParameterList, function.typeParameterList, function.typeConstraintList,
                function.typeReference
            )
                .any { part -> part.collectDescendantsOfType<KtUserType>().any { it.referencedName in names } }
        }
        val localClassNames = localClasses.mapTo(HashSet()) { it.name!! }
        // the local functions, the extension ones, and the properties, which might be called, by their names
        val localExtensions = declarations.filter {
            it.kind == DeclarationKind.FUNCTION && it.isExtension && it !in moved && it.name != null
        }.groupBy { it.name!! }
        val localFunctionsByName = local.filter { it.name != null }.groupBy { it.name!! }
        val typeAliases = declarations.mapNotNull { declaration ->
            (declaration.psi as? KtTypeAlias)?.takeIf { it.typeParameters.isEmpty() }?.let { alias ->
                alias.getTypeReference()?.let { alias.name!! to it.singleLineText() }
            }
        }.toMap()
        // also the destructured ones, like `E` of `val (E, F) = 2.0 to 3.0`
        val localPropertyDeclarations = declarations.filter { it.kind == DeclarationKind.PROPERTY && it !in moved }
        val localPropertiesByName = buildMap<String, MutableList<ParsedDeclaration>> {
            localPropertyDeclarations.forEach { property ->
                (listOfNotNull(property.name) + property.destructuredNames).forEach { getOrPut(it) { mutableListOf() } += property }
            }
        }
        // the local properties of the known types, which might be called, unless they refer to the local classes
        val callableProperties = localPropertyDeclarations.filter { it.name != null }.mapNotNull { property ->
            (property.psi as? KtProperty)?.takeIf { declaration ->
                declaration.typeReference?.collectDescendantsOfType<KtUserType>()?.none { it.referencedName in localClassNames } ?: true
            }?.functionType(
                localFunction = { name -> localFunctionsByName[name]?.singleOrNull()?.psi as? KtNamedFunction },
                typeAlias = { name -> typeAliases[name] }
            )?.let { property to it }
        }.groupBy { it.first.name!! }
        hiddenLocalFunctionCandidates = moved.flatMap { declaration ->
            declaration.references.filter { !it.isBound }.mapNotNull { reference ->
                val name = reference.name
                when {
                    reference.isCall && reference.refersToLocal(localFunctions) -> localFunctionsByName.getValue(name).first() to true
                    // like `listOf(1, 2).sum()`
                    reference.isCall && localExtensions[name]?.any { !reference.isOperator || it.isOperator } == true ->
                        localExtensions.getValue(name).first() to true
                    // also with a receiver, like `" a ".trim()` of `val trim: String.() -> String`
                    reference.isCall && name in callableProperties -> callableProperties.getValue(name).first().first to false
                    // like `E`, resolved to `kotlin.math.E` instead
                    !reference.isCall && reference.refersToLocal(localProperties) && name in localPropertiesByName ->
                        localPropertiesByName.getValue(name).first() to false
                    else -> null
                }?.let { (local, isFunction) -> Triple(reference, local.line(), isFunction) }
            }
        }
        hiddenLocalFunctionCopies = if (!confirming) emptyList() else {
            hiddenLocalFunctionCandidates.mapTo(LinkedHashSet()) { it.first.name }.flatMap { name ->
                // the moved declarations cannot refer to the values of the local classes
                ((localFunctionsByName[name].orEmpty() + localExtensions[name].orEmpty())
                    .filterNot { it.refersToTypes(localClassNames) }
                    .mapNotNull { it.signature() } +
                        (callableProperties[name]?.map { (_, type) -> "val `$name`: $type get() = kotlin.TODO()" }
                            ?: listOfNotNull(if (name in localPropertiesByName) "val `$name`: Nothing get() = kotlin.TODO()" else null))
                ).map { name to it }
            }
        }
        // also the aliases, like `typealias Q = Suppress` or `import kotlin.Suppress as Q`
        val suppressNames = setOf("Suppress") +
                file.collectDescendantsOfType<KtTypeAlias> {
                    it.getTypeReference()?.text?.removePrefix("kotlin.") == "Suppress"
                }.mapNotNull { it.name } +
                file.importDirectives.filter { it.importedFqName?.asString() == "kotlin.Suppress" }.mapNotNull { it.aliasName }
        fun KtAnnotationEntry.isSuppress() = shortName?.asString() in suppressNames
        suppressions = if (!confirming) emptyList() else {
            file.collectDescendantsOfType<KtAnnotationEntry> { it.isSuppress() }.map { entry ->
                // like `@[Suppress("X")]`, which would be invalid without the entries
                val annotation = entry.parent as? KtAnnotation
                if (annotation != null && annotation.entries.all { it.isSuppress() }) annotation else entry
            }.distinct().map { it.startOffset until it.endOffset }
        }
        // the first local property of each name of a moved function, by the index of its statement
        val movedFunctionsByName = movedOut.filter { it.name != null }.groupBy { it.name!! }
        val shadowingProperties = mutableMapOf<String, Pair<Int, ParsedDeclaration>>()
        statements.forEachIndexed { index, statement ->
            declarationsByPsi[statement]?.takeIf { it.kind == DeclarationKind.PROPERTY && it !in moved }?.let { property ->
                (listOfNotNull(property.name) + property.destructuredNames)
                    .filter { it in movedFunctionsByName }
                    .forEach { shadowingProperties.putIfAbsent(it, index to property) }
            }
        }
        val shadowedFunctionCandidatesByStatement = statements.indices.associateWith { index ->
            if (declarationsByPsi[statements[index]] in moved) return@associateWith emptyList()
            statementReferences[index].mapNotNull { reference ->
                shadowingProperties[reference.name]?.takeIf { (propertyIndex) ->
                    propertyIndex < index && reference.isCall && reference.refersToLocal(localProperties)
                }?.let { (_, property) ->
                    Triple(reference, property.line(), movedFunctionsByName.getValue(reference.name).first().line())
                }
            }
        }
        shadowedFunctionCandidates = shadowedFunctionCandidatesByStatement.values.flatten()
        // unlike the extension properties, which do not apply without their receivers
        val coroutineScopeDeclarations = moved.filter {
            (it.kind == DeclarationKind.FUNCTION || (it.kind == DeclarationKind.PROPERTY && (it.psi as? KtProperty)?.receiverTypeReference == null))
                    && it.name != null && (it.name in coroutineScopeProperties || it.name in coroutineScopeFunctions)
        }.groupBy { it.name!! }
        val coroutineScopeCandidatesByStatement = statements.indices.associateWith { index ->
            if (declarationsByPsi[statements[index]] in moved) return@associateWith emptyList()
            statementReferences[index].filter { reference ->
                !reference.isQualified && !reference.isBound && reference.name in coroutineScopeDeclarations
                        // like in `coroutineScope { launch { } }`, where the nested coroutine scope is resolved also in the script
                        && !reference.isInLambda
                        // refers to the local property shadowing the function, see shadowedFunctionCandidates
                        && shadowingProperties[reference.name]?.let { (propertyIndex) -> propertyIndex < index } != true
            }.map {
                val declaration = coroutineScopeDeclarations.getValue(it.name).first()
                Triple(it, declaration.line(), declaration.kind == DeclarationKind.FUNCTION)
            }
        }
        coroutineScopeCandidates = coroutineScopeCandidatesByStatement.values.flatten()
        val laterDeclaredCandidatesByStatement = statements.indices.associateWith { index ->
            if (declarationsByPsi[statements[index]] in moved) return@associateWith emptyList()
            statementReferences[index].mapNotNull { reference ->
                dependencyProperties[reference.name]?.takeIf { (declarationIndex) ->
                    // also when called, as the function of the same name might not apply
                    declarationIndex > index && reference.refersToLocal(localProperties)
                }?.let { (_, property) -> Triple(reference, property.line(), false) }
                    ?: dependencyFunctions[reference.name]?.takeIf { (declarationIndex) ->
                        declarationIndex > index && reference.isCall && reference.refersToLocal(localFunctions)
                    }?.let { (_, function) -> Triple(reference, function.line(), true) }
            }
        }
        laterDeclaredDependencyCandidates = laterDeclaredCandidatesByStatement.values.flatten()
        val statementIndices = statements.withIndex().associate { (index, statement) -> statement to index }
        blockCopies = if (!confirming) emptyMap() else buildMap<Int, MutableList<Pair<String, String>>> {
            /** The copies of the functions, by their names, each preceding the first statement of the candidates of its name. */
            fun addCopies(
                candidatesByStatement: Map<Int, List<Triple<Reference, Int, *>>>,
                functions: Map<String, List<ParsedDeclaration>>,
                message: String
            ) = candidatesByStatement.entries.sortedBy { it.key }.flatMap { (index, candidates) ->
                candidates.map { it.first.name to index }
            }.distinctBy { it.first }.forEach { (name, index) ->
                /** The start of the statement, including the comments preceding it, which the copies should not be inserted into. */
                fun leadingStart(
                    statementIndex: Int
                ): Int {
                    var start = statements[statementIndex].startOffset
                    var element = statements[statementIndex].prevSibling
                    while (element is PsiWhiteSpace || element is PsiComment) {
                        if (element is PsiComment) start = element.startOffset
                        element = element.prevSibling
                    }
                    return start
                }
                // preceding the first statement sharing the line, so the inserted line does not split another statement
                var first = index
                while (first > 0 && '\n' !in script.substring(statements[first - 1].endOffset, leadingStart(first))) first--
                val invisibleClasses = localClasses.filter { statementIndices.getValue(it.psi) >= first }.mapTo(HashSet()) { it.name!! }
                getOrPut(leadingStart(first)) { mutableListOf() } += functions[name].orEmpty()
                    .filterNot { it.refersToTypes(invisibleClasses) }
                    .mapNotNull { it.signature() }
                    .map { message to it }
            }
            addCopies(
                laterDeclaredCandidatesByStatement.mapValues { (_, candidates) -> candidates.filter { it.third } },
                localFunctionsByName,
                LATER_DECLARED_FUNCTION
            )
            if (shadowedFunctionCopies) addCopies(shadowedFunctionCandidatesByStatement, movedFunctionsByName, SHADOWED_FUNCTION)
            if (coroutineScopeCopies) {
                // also the extension ones, which might not apply, like `fun Robot.launch()` to `launch { }`
                addCopies(
                    coroutineScopeCandidatesByStatement.mapValues { (_, candidates) -> candidates.filter { it.third } },
                    moved.filter { it.kind == DeclarationKind.FUNCTION && it.name != null }.groupBy { it.name!! },
                    COROUTINE_SCOPE_FUNCTION
                )
            }
        }.let { copies ->
            // the same signature once, preceding its first use, as declaring it twice would make the calls ambiguous
            copies.entries.sortedBy { it.key }
                .flatMap { (offset, declarations) -> declarations.map { (message, declaration) -> Triple(offset, message, declaration) } }
                .groupBy { it.third }
                .map { (declaration, uses) -> uses.first().first to (uses.map { it.second }.distinct() to declaration) }
                .groupBy({ it.first }) { it.second }
        }
        // unlike an extension property, which does not conflict with the accessor of the dependency
        conflictingDependencyDeclarations = moved.filter {
            it.kind == DeclarationKind.PROPERTY && (it.psi as? KtProperty)?.receiverTypeReference == null && it.name in dependencyNames
        }.map { it.name!! to it.nameRange!! }
        bodyDeclarations = declarations
            .filter {
                it !in moved && (it.kind == DeclarationKind.PROPERTY || it.kind == DeclarationKind.FUNCTION || it.kind == DeclarationKind.CLASS)
            }
            .map { it.toDeclaration() }
        declarations.filter { it !in moved }.forEach { declaration ->
            declaration.psi.modifierList?.node?.getChildren(null).orEmpty()
                .filter {
                    it.elementType in visibilityModifiers || (it.elementType in memberOnlyModifiers
                            && (declaration.kind == DeclarationKind.FUNCTION || declaration.kind == DeclarationKind.PROPERTY))
                }
                .forEach { blankedRanges += it.startOffset until it.startOffset + it.textLength }
            if (declaration.kind == DeclarationKind.PROPERTY) {
                declaration.psi.memberOnlyAnnotationEntries().forEach { blankedRanges += it.startOffset until it.endOffset }
                declaration.accessors.forEach { blankedRanges += it.startOffset until it.endOffset }
            }
        }
    }

    private class ParsedDeclaration(
        val psi: KtDeclaration,
        val end: Int,
        val kind: DeclarationKind,
        val name: String?,
        val nameRange: IntRange?,
        val requiresMove: Boolean,
        /** The accessors of the property, which are its part. */
        val accessors: List<KtPropertyAccessor>,
        val destructuredNames: List<String>,
        val isSealed: Boolean,
        /** Whether it is a function with a receiver, like `fun Int.double()`. */
        val isExtension: Boolean,
        /** Whether it is an operator function, like `operator fun Point.plus(other: Point)`. */
        val isOperator: Boolean,
        /** The names of the supertypes, like `Shape` in `class Circle(val r: Int) : Shape`. */
        val supertypeNames: List<String>,
        val references: List<Reference>
    ) {
        /** Without the comments preceding the declaration, which are left in the script body. */
        val start = psi.startOffsetSkippingComments
        fun toDeclaration(
            withNestedNames: Boolean = false
        ) = Declaration(
            name = name,
            kind = kind,
            start = start,
            end = end,
            nameRange = nameRange,
            destructuredNames = destructuredNames,
            nestedNameRanges = if (withNestedNames && psi is KtClassOrObject) {
                buildMap {
                    psi.collectDescendantsOfType<KtClassOrObject> { it !== psi }.forEach { nested ->
                        nested.nameIdentifier?.let { putIfAbsent(nested.name!!, it.startOffset until it.endOffset) }
                        // as the compiler names it in its diagnostics, also when named
                        if (nested is KtObjectDeclaration && nested.isCompanion()) {
                            (nested.nameIdentifier ?: nested.modifierList?.getModifier(KtTokens.COMPANION_KEYWORD))?.let {
                                putIfAbsent("Companion", it.startOffset until it.endOffset)
                            }
                        }
                    }
                }
            } else {
                emptyMap()
            }
        )
    }

    private fun parseDeclaration(
        statement: KtExpression
    ): ParsedDeclaration? {
        val declaration = statement as? KtDeclaration ?: return null
        val kind = when (declaration) {
            is KtClass -> if (declaration.isInterface()) DeclarationKind.INTERFACE else DeclarationKind.CLASS
            is KtObjectDeclaration -> if (declaration.isObjectLiteral()) return null else DeclarationKind.OBJECT
            is KtTypeAlias -> DeclarationKind.TYPE_ALIAS
            is KtNamedFunction -> DeclarationKind.FUNCTION
            is KtProperty, is KtDestructuringDeclaration -> DeclarationKind.PROPERTY
            else -> return null
        }
        val nameIdentifier = (declaration as? KtNamedDeclaration)?.nameIdentifier
        // e.g. an anonymous function, which is an expression
        if (nameIdentifier == null && declaration !is KtDestructuringDeclaration) return null
        // like in the script, also the accessors starting a line, which is not indented more than the property, are its part,
        // moved or blanked out with it, as the local property in the script block has none, so they would be the statements following it,
        // unless they do nothing, like `get()`, or do not parse, like `get(3)` or `get("/") { }`, being rather the calls following it
        val accessors = (declaration as? KtProperty)?.accessors.orEmpty().let { parsed ->
            fun KtPropertyAccessor.isUnindented() = isOnNewLine(startOffset)
                    && columnOf(startOffset) <= columnOf(declaration.startOffsetSkippingComments)
            val errorOffsets = if (parsed.any { it.isUnindented() }) {
                declaration.collectDescendantsOfType<PsiErrorElement>().map { it.startOffset }
            } else {
                emptyList()
            }
            val wellFormed = parsed.takeWhile { accessor ->
                !accessor.isUnindented() || errorOffsets.none { it >= accessor.startOffset }
            }
            // a bare `set` preceding a getter with a body is still the part of the property
            wellFormed.take(wellFormed.indexOfLast { !it.isUnindented() || it.hasBody() || it.modifierList != null } + 1)
        }
        val end = (declaration as? KtProperty)?.accessors?.getOrNull(accessors.size)?.let { firstOmitted ->
            declaration.allChildren.toList().last {
                it.endOffset <= firstOmitted.startOffset && it !is PsiWhiteSpace && it !is PsiComment
            }.endOffset
        } ?: declaration.endOffset
        val hasMemberOnlyAnnotation = declaration.memberOnlyAnnotationEntries().isNotEmpty()
        val requiresMove = when (declaration) {
            is KtClass -> declaration.isInterface()
                    || nonLocalClassModifiers.any { declaration.hasModifier(it) }
                    || declaration.declarations.any { it is KtClassOrObject || it is KtTypeAlias }
            is KtObjectDeclaration,
            is KtTypeAlias -> true
            is KtNamedFunction -> declaration.hasModifier(KtTokens.INLINE_KEYWORD) || hasMemberOnlyAnnotation
            // otherwise its initializer would run when the script class is constructed, before the script body,
            // so instead its annotations, and its accessors without a body, like `private set`, are blanked out,
            // unless the order does not matter
            is KtProperty -> declaration.receiverTypeReference != null
                    || accessors.any { it.hasBody() }
                    || (hasMemberOnlyAnnotation && declaration.initializer.isConstant())
            else -> false
        }
        return ParsedDeclaration(
            psi = declaration,
            end = end,
            kind = kind,
            // without the backticks, like the references
            name = (declaration as? KtNamedDeclaration)?.name,
            nameRange = nameIdentifier?.let { it.startOffset until it.endOffset },
            requiresMove = requiresMove,
            accessors = accessors,
            destructuredNames = (declaration as? KtDestructuringDeclaration)?.entries.orEmpty()
                .mapNotNull { it.name }
                .filter { it != "_" },
            isSealed = declaration.hasModifier(KtTokens.SEALED_KEYWORD),
            isExtension = declaration is KtNamedFunction && declaration.receiverTypeReference != null,
            isOperator = declaration is KtNamedFunction && declaration.hasModifier(KtTokens.OPERATOR_KEYWORD),
            supertypeNames = (declaration as? KtClassOrObject)?.superTypeListEntries.orEmpty().mapNotNull {
                (it.typeReference?.typeElement as? KtUserType)?.referencedName
            },
            references = declaration.references(scope = declaration.parent, declaredNames)
        )
    }

    /** Whether the [offset] is preceded only by whitespaces in its line. */
    private fun isOnNewLine(
        offset: Int
    ): Boolean = script.lastIndexOf('\n', offset - 1).let { lineEnd ->
        (lineEnd + 1 until offset).all { script[it].isWhitespace() }
    }

    private fun columnOf(
        offset: Int
    ): Int = offset - (script.lastIndexOf('\n', offset - 1) + 1)

    /**
     * The declarations which cannot be local, together with the types and functions they refer to,
     * and the subtypes of moved sealed types, which cannot be local either.
     *
     * Local functions and classes are not visible before their declaration, so the ones referred to earlier are moved as well.
     *
     * The references are recognized by the names only, which might be the names of members as well,
     * therefore a function is not moved if it, or the declarations it refers to, access the local properties of the script body.
     */
    private fun selectMovedDeclarations(
        declarations: List<ParsedDeclaration>,
        bodyReferences: List<Reference>,
        localProperties: Set<String>
    ): Set<ParsedDeclaration> {
        val named = declarations.filter { it.name != null && it.kind != DeclarationKind.PROPERTY }
        val namedByName = named.groupBy { it.name!! }
        val subtypesByName = mutableMapOf<String, MutableList<ParsedDeclaration>>()
        named.filter { it.kind != DeclarationKind.FUNCTION }.forEach { declaration ->
            declaration.supertypeNames.forEach { subtypesByName.getOrPut(it) { mutableListOf() } += declaration }
        }
        // a qualified reference, like `list.size`, cannot refer to a declaration of the script, unless it is an extension
        fun Reference.referenced(): List<ParsedDeclaration> = namedByName[name].orEmpty().filter {
            (!isQualified || it.isExtension) && (!isOperator || it.isOperator)
        }
        // the operator functions called on the result of `iterator`, like by a for loop
        val iteratorOperators = named.filter { it.isOperator && it.isExtension && (it.name == "hasNext" || it.name == "next") }
        // not by the operators, which refer to the operator functions by their names only, whatever the types of the operands,
        // so an operator function accessing the local properties would make all the declarations using such an operator accessing,
        // while the ones calling it indeed are reported, see ScriptSource.hiddenLocalFunctionCandidates
        val plainReferences = declarations.associateWith { declaration ->
            buildSet {
                declaration.references.filter { !it.isOperator }.forEach { addAll(it.referenced()) }
                if (declaration.isSealed) addAll(subtypesByName[declaration.name].orEmpty())
                remove(declaration)
            }
        }
        // the operator functions called on the result of the `iterator` extension, like by a for loop
        val references = declarations.associateWith { declaration ->
            buildSet {
                addAll(plainReferences.getValue(declaration))
                declaration.references.filter { it.isOperator }.forEach { addAll(it.referenced()) }
                if (declaration.isOperator && declaration.isExtension && declaration.name == "iterator") addAll(iteratorOperators)
                remove(declaration)
            }
        }
        val referrers = mutableMapOf<ParsedDeclaration, MutableList<ParsedDeclaration>>()
        plainReferences.forEach { (declaration, referenced) ->
            referenced.forEach { referrers.getOrPut(it) { mutableListOf() } += declaration }
        }
        val required = declarations.filter { it.requiresMove }.toSet()
        val accessingLocalProperties = declarations.filter { declaration ->
            declaration.references.any { it.refersToLocal(localProperties) }
        }.toSet()
        /** The declarations not [moved] yet, which access the local properties, or refer to such ones not moved yet. */
        fun accessingLocalProperties(
            moved: Set<ParsedDeclaration>
        ): Set<ParsedDeclaration> {
            val accessing = accessingLocalProperties.filterTo(mutableSetOf()) { it !in moved }
            val queue = ArrayDeque(accessing)
            while (queue.isNotEmpty()) {
                referrers[queue.removeFirst()].orEmpty().forEach {
                    if (it !in moved && accessing.add(it)) queue += it
                }
            }
            return accessing
        }
        val moved = required.toMutableSet()
        var accessing = accessingLocalProperties(moved)
        val queue = ArrayDeque(moved)
        while (queue.isNotEmpty()) {
            references.getValue(queue.removeFirst()).forEach { declaration ->
                if (declaration !in moved && (declaration.kind != DeclarationKind.FUNCTION || declaration !in accessing)) {
                    moved += declaration
                    queue += declaration
                    // moving a declaration, which is not accessing, cannot change the accessing ones,
                    // as they refer to the local properties only through the accessing ones
                    if (declaration in accessing) {
                        accessing = accessingLocalProperties(moved)
                        // the functions rejected before might not be accessing anymore
                        queue.clear()
                        queue += moved
                    }
                }
            }
        }
        val firstReferences = mutableMapOf<ParsedDeclaration, Int>()
        bodyReferences.forEach { reference ->
            reference.referenced().forEach { firstReferences.putIfAbsent(it, reference.offset) }
        }
        declarations.filter { declaration ->
            (declaration.kind == DeclarationKind.FUNCTION || declaration.kind == DeclarationKind.CLASS)
                    && (firstReferences[declaration] ?: Int.MAX_VALUE) < declaration.start
        }.forEach { declaration ->
            // moving a declaration, which is not accessing, moves only the ones, which are not accessing either,
            // while its overloads, which are accessing, would stay in the script body, hiding it there
            if (declaration !in moved && declaration !in accessing && namedByName.getValue(declaration.name!!).none {
                    it.isExtension == declaration.isExtension && it in accessing
                }) {
                val queue = ArrayDeque(listOf(declaration))
                moved += declaration
                while (queue.isNotEmpty()) {
                    references.getValue(queue.removeFirst()).forEach {
                        // referred to by an operator, while accessing the local properties, see the referrers
                        if ((it.kind != DeclarationKind.FUNCTION || it !in accessing) && moved.add(it)) queue += it
                    }
                }
            }
        }
        return moved.sortedBy { it.start }.toCollection(LinkedHashSet())
    }

}

private val openingTokens = setOf(
    KtTokens.LBRACE, KtTokens.LPAR, KtTokens.LBRACKET,
    KtTokens.LONG_TEMPLATE_ENTRY_START, KtTokens.OPEN_QUOTE
)

/** The opening tokens closed by each closing token. */
private val openingOf = mapOf<IElementType, IElementType>(
    KtTokens.RBRACE to KtTokens.LBRACE,
    KtTokens.RPAR to KtTokens.LPAR,
    KtTokens.RBRACKET to KtTokens.LBRACKET,
    KtTokens.LONG_TEMPLATE_ENTRY_END to KtTokens.LONG_TEMPLATE_ENTRY_START,
    KtTokens.CLOSING_QUOTE to KtTokens.OPEN_QUOTE
)

/**
 * The opening tokens which a closing token cannot close past, as the parser ends a block or a string with them,
 * e.g. the `}` in `foo(1 }` closes the block, while `)` in `{ foo) }` does not.
 */
private val enclosingOf = mapOf<IElementType, Set<IElementType>>(
    KtTokens.RBRACE to setOf(KtTokens.LONG_TEMPLATE_ENTRY_START, KtTokens.OPEN_QUOTE),
    KtTokens.RPAR to setOf(KtTokens.LBRACE, KtTokens.LONG_TEMPLATE_ENTRY_START, KtTokens.OPEN_QUOTE),
    KtTokens.RBRACKET to setOf(KtTokens.LBRACE, KtTokens.LONG_TEMPLATE_ENTRY_START, KtTokens.OPEN_QUOTE),
    KtTokens.LONG_TEMPLATE_ENTRY_END to setOf(KtTokens.OPEN_QUOTE),
    KtTokens.CLOSING_QUOTE to emptySet()
)

/**
 * The offset of the first `}` closing the script block, which the script is wrapped in,
 * recognized with the lexer, as the parser of the script alone recovers from it differently.
 */
private fun unmatchedClosingBrace(
    script: String
): Int? {
    val lexer = KotlinLexer()
    lexer.start(script)
    val opened = mutableListOf<IElementType>()
    while (true) {
        val type = lexer.tokenType ?: return null
        if (type in openingTokens) opened += type
        // ends the unterminated single-line string instead of the closing quote
        if (type == KtTokens.DANGLING_NEWLINE) {
            val quote = opened.lastIndexOf(KtTokens.OPEN_QUOTE)
            if (quote >= 0) opened.subList(quote, opened.size).clear()
        }
        openingOf[type]?.let { closed ->
            val opening = opened.lastIndexOf(closed)
            val enclosing = opened.indexOfLast { it in enclosingOf.getValue(type) }
            if (opening > enclosing) {
                opened.subList(opening, opened.size).clear()
            } else if (type == KtTokens.RBRACE && enclosing == -1) {
                return lexer.tokenStart
            }
        }
        lexer.advance()
    }
}

/**
 * The offset of the block comment, or the KDoc, ending the [script] unterminated, also when it closes only a nested comment,
 * recognized as the comment token extending also over a line appended to the script.
 */
private fun unterminatedComment(
    script: String
): Int? {
    val lexer = KotlinLexer()
    lexer.start(script)
    var last: Int? = null
    while (lexer.tokenType != null) {
        last = lexer.tokenStart.takeIf { lexer.tokenType == KtTokens.BLOCK_COMMENT || lexer.tokenType == KtTokens.DOC_COMMENT }
        lexer.advance()
    }
    if (last == null) return null
    lexer.start(script.substring(last) + "\n")
    return last.takeIf { lexer.tokenEnd == lexer.bufferEnd }
}
