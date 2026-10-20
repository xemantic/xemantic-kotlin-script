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
import org.jetbrains.kotlin.com.intellij.psi.PsiWhiteSpace
import org.jetbrains.kotlin.com.intellij.psi.tree.IElementType
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.lexer.KotlinLexer
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtAnnotation
import org.jetbrains.kotlin.psi.KtAnnotationEntry
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
import org.jetbrains.kotlin.psi.KtPropertyAccessor
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtTypeAlias
import org.jetbrains.kotlin.psi.KtUserType
import org.jetbrains.kotlin.psi.KtValueArgumentName
import org.jetbrains.kotlin.psi.KtWhenExpression
import org.jetbrains.kotlin.psi.psiUtil.allChildren
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.jetbrains.kotlin.psi.psiUtil.endOffset
import org.jetbrains.kotlin.psi.psiUtil.getReceiverExpression
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
    val isFunction: Boolean
)

/** The deprecation message of the dependencies, see [ScriptSource.laterDeclaredDependencyCandidates]. */
internal const val LATER_DECLARED_DEPENDENCY = "xemantic-kotlin-script: later declared dependency"

/** The deprecation message of the copies of the local functions, see [ScriptSource.laterDeclaredDependencyCandidates]. */
internal const val LATER_DECLARED_FUNCTION = "xemantic-kotlin-script: later declared function"

/** The deprecation message of the copies of the local functions, see [ScriptSource.hiddenLocalFunctionCandidates]. */
internal const val HIDDEN_LOCAL_FUNCTION = "xemantic-kotlin-script: hidden local function"

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
    script: String,
    dependencies: List<PreambleDependency> = emptyList(),
    /**
     * Whether it is the source of the compilation confirming the [laterDeclaredDependencyCandidates]
     * and the [hiddenLocalFunctionCandidates], with the declarations they might refer to deprecated with an error,
     * which does not change how the references are resolved, unlike omitting them,
     * while the declarations the candidates should refer to are declared as their deprecated copies,
     * and the suppressions of the deprecation errors by the script are blanked out.
     */
    confirming: Boolean = false
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
    val hiddenLocalFunctionCandidates: List<Pair<ScriptReference, Int>>

    private val lineStarts: List<Int>

    /** For each line of the [text], the line of the script, or `null` for the lines of the block and the preamble. */
    private val lineMapping: List<Int?>

    /** The names of the dependencies, by the 0-based lines of the [text] declaring their accessors. */
    private val preambleDependencies = mutableMapOf<Int, String>()

    private val movedDeclarations: List<Declaration>

    private val bodyDeclarations: List<Declaration>

    init {
        val lineBounds = lineBounds(script)
        lineStarts = lineBounds.map { it.first }
        scriptLines = lineBounds.map { script.substring(it.first, it.last + 1) }
        // the parser recognizes only `\n` as a line terminator, replacing each character keeps the offsets
        val structure = ScriptStructure(script.replace('\r', '\n'), dependencies.mapTo(HashSet()) { it.name })
        unmatchedClosingBrace = structure.unmatchedClosingBrace?.let { position(it) }
        movedDeclarations = structure.movedDeclarations
        returnsUnit = structure.endsWithMovedDeclaration
        conflictingDependencyDeclarations = structure.conflictingDependencyDeclarations.map { (name, range) ->
            ScriptReference(name, position(range.first), position(range.last + 1))
        }
        laterDeclaredDependencyCandidates = structure.laterDeclaredDependencyCandidates.map { (reference, declaration, isFunction) ->
            LaterDeclaredReference(
                ScriptReference(reference.name, position(reference.offset), position(reference.offset + reference.length)),
                position(declaration).line,
                isFunction
            )
        }
        hiddenLocalFunctionCandidates = structure.hiddenLocalFunctionCandidates.map { (reference, declaration) ->
            ScriptReference(reference.name, position(reference.offset), position(reference.offset + reference.length)) to
                    position(declaration).line
        }
        bodyDeclarations = structure.bodyDeclarations
        val deprecatedDependencies = if (confirming) {
            laterDeclaredDependencyCandidates.mapTo(HashSet()) { it.reference.name }
        } else {
            emptySet()
        }

        val lines = mutableListOf<String>()
        val mapping = mutableListOf<Int?>()
        // the confirming compilation reports the deprecation errors, even if suppressed by the script
        val unsuppressed = BooleanArray(script.length)
        if (confirming) structure.deprecationErrorSuppressions.forEach { unsuppressed.fill(true, it.first, it.last + 1) }
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

        val headerEnd = structure.headerEnd
        if (headerEnd > 0) {
            addLines(0..lineOf(headerEnd - 1)) { it < headerEnd }
        }
        fun addAccessor(
            dependency: PreambleDependency
        ) {
            preambleDependencies[lines.size] = dependency.name
            lines += if (dependency.name in deprecatedDependencies) {
                // the qualified `kotlin.DeprecationLevel.ERROR` crashes the compiler
                "@kotlin.Deprecated(\"$LATER_DECLARED_DEPENDENCY\", level = DeprecationLevel.ERROR) ${dependency.accessor}"
            } else {
                dependency.accessor
            }
            mapping += null
        }
        fun addCopies(
            names: Set<String>,
            message: String
        ) = names.flatMap { structure.localFunctionSignatures[it].orEmpty() }.forEach {
            lines += "@kotlin.Deprecated(\"$message\", level = DeprecationLevel.ERROR) $it"
            mapping += null
        }
        if (confirming) {
            addCopies(hiddenLocalFunctionCandidates.mapTo(HashSet()) { it.first.name }, HIDDEN_LOCAL_FUNCTION)
        }
        val (shadowed, visible) = dependencies.partition {
            it.name in structure.localProperties || (it.isCallable && it.name in structure.localFunctions)
        }
        visible.forEach { addAccessor(it) }
        lines += "class $DEPENDENCIES_CLASS {"
        mapping += null
        // the members of the outer receiver of the script body would be called instead of the moved functions
        shadowed.filterNot { it.isCallable && it.name in structure.movedFunctions }.forEach { addAccessor(it) }
        if (confirming) {
            addCopies(
                laterDeclaredDependencyCandidates.filter { it.isFunction }.mapTo(HashSet()) { it.reference.name },
                LATER_DECLARED_FUNCTION
            )
        }
        lines += "}"
        mapping += null
        movedDeclarations.forEach { declaration ->
            addLines(lineOf(declaration.start)..lineOf(declaration.end - 1)) {
                it in declaration.start until declaration.end
            }
        }
        lines += SCRIPT_BLOCK_START
        mapping += null
        addLines(lineOf(headerEnd)..lineBounds.lastIndex) { offset ->
            offset >= headerEnd && !excluded[offset]
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
private fun KtNamedFunction.signature(): String = buildString {
    if (hasModifier(KtTokens.SUSPEND_KEYWORD)) +"suspend "
    +"fun "
    if (typeParameters.isNotEmpty()) {
        +typeParameters.joinToString(prefix = "<", postfix = "> ") { parameter ->
            "`${parameter.name}`" + (parameter.extendsBound?.let { ": ${it.text}" } ?: "")
        }
    }
    +"`$name`("
    +valueParameters.joinToString { parameter ->
        (if (parameter.isVarArg) "vararg " else "") + "`${parameter.name}`: ${parameter.typeReference?.text}" +
                (if (parameter.hasDefaultValue()) " = kotlin.TODO()" else "")
    }
    +"): Nothing = kotlin.TODO()"
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
    /** Whether it is called, like `foo` in `foo()`, so it might refer to a function. */
    val isCall: Boolean
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
        isCall = (expression.parent as? KtCallExpression)?.calleeExpression == expression
    )
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
    dependencyNames: Set<String>
) {

    val headerEnd: Int

    val unmatchedClosingBrace: Int? = unmatchedClosingBrace(script)

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

    /** The signatures of the local functions, by their names, see [ScriptSource.confirming]. */
    val localFunctionSignatures: Map<String, List<String>>

    /** See [ScriptSource.hiddenLocalFunctionCandidates], with the offsets of the first local functions. */
    val hiddenLocalFunctionCandidates: List<Pair<Reference, Int>>

    /** The ranges of the `DEPRECATION_ERROR` names suppressed by the script, see [ScriptSource.laterDeclaredDependencyCandidates]. */
    val deprecationErrorSuppressions: List<IntRange>

    /** See [ScriptSource.laterDeclaredDependencyCandidates], with the offsets of the declarations, and whether they are functions. */
    val laterDeclaredDependencyCandidates: List<Triple<Reference, Int, Boolean>>

    /** The names of the [ScriptSource.conflictingDependencyDeclarations], and their ranges. */
    val conflictingDependencyDeclarations: List<Pair<String, IntRange>>

    /** The modifiers which are not applicable to local declarations. */
    val blankedRanges = mutableListOf<IntRange>()

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
        localProperties = declarations.filter { it.kind == DeclarationKind.PROPERTY && !it.requiresMove }
            .flatMapTo(HashSet()) { listOfNotNull(it.name) + it.destructuredNames }
        val moved = selectMovedDeclarations(declarations, bodyReferences, localProperties)
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
        localFunctionSignatures = local.filter { it.name != null }.groupBy({ it.name!! }) { function ->
            (function.psi as KtNamedFunction).signature()
        }
        val firstLocalFunctions = local.filter { it.name != null }.groupBy { it.name!! }.mapValues { (_, functions) ->
            functions.first().line()
        }
        hiddenLocalFunctionCandidates = moved.flatMap { declaration ->
            declaration.references.filter { it.isCall && it.refersToLocal(localFunctions) }.map {
                it to firstLocalFunctions.getValue(it.name)
            }
        }
        deprecationErrorSuppressions = file.collectDescendantsOfType<KtAnnotationEntry> {
            it.shortName?.asString() == "Suppress"
        }.flatMap { entry ->
            entry.collectDescendantsOfType<KtStringTemplateExpression> { it.text == "\"DEPRECATION_ERROR\"" }
        }.map { it.startOffset + 1 until it.endOffset - 1 }
        laterDeclaredDependencyCandidates = statements.withIndex().flatMap { (index, statement) ->
            if (declarationsByPsi[statement] in moved) return@flatMap emptyList()
            statementReferences[index].mapNotNull { reference ->
                dependencyProperties[reference.name]?.takeIf { (declarationIndex) ->
                    declarationIndex > index && reference.refersToLocal(localProperties)
                            // calling the function of the script body
                            && !(reference.isCall && (reference.name in localFunctions || reference.name in movedFunctions))
                }?.let { (_, property) -> Triple(reference, property.line(), false) }
                    ?: dependencyFunctions[reference.name]?.takeIf { (declarationIndex) ->
                        declarationIndex > index && reference.isCall && reference.refersToLocal(localFunctions)
                    }?.let { (_, function) -> Triple(reference, function.line(), true) }
            }
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
        // unlike in the script, the local property in the script block has no accessors,
        // so `get` and `set` starting a line, which is not indented, are the statements following it
        val accessors = (declaration as? KtProperty)?.accessors.orEmpty().takeWhile {
            !isOnNewLine(it.startOffset) || columnOf(it.startOffset) > columnOf(declaration.startOffsetSkippingComments)
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
            !isQualified || it.isExtension
        }
        val references = declarations.associateWith { declaration ->
            buildSet {
                declaration.references.forEach { addAll(it.referenced()) }
                if (declaration.isSealed) addAll(subtypesByName[declaration.name].orEmpty())
                remove(declaration)
            }
        }
        val referrers = mutableMapOf<ParsedDeclaration, MutableList<ParsedDeclaration>>()
        references.forEach { (declaration, referenced) ->
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
            // moving a declaration, which is not accessing, moves only the ones, which are not accessing either
            if (declaration !in moved && declaration !in accessing) {
                val queue = ArrayDeque(listOf(declaration))
                moved += declaration
                while (queue.isNotEmpty()) {
                    references.getValue(queue.removeFirst()).forEach {
                        if (moved.add(it)) queue += it
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
