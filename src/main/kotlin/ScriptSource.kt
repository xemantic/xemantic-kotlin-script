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
import org.jetbrains.kotlin.com.intellij.psi.impl.source.tree.LeafPsiElement
import org.jetbrains.kotlin.com.intellij.psi.tree.IElementType
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.lexer.KotlinLexer
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtAnnotation
import org.jetbrains.kotlin.psi.KtAnonymousInitializer
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
import org.jetbrains.kotlin.psi.KtConstructor
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtDeclarationWithBody
import org.jetbrains.kotlin.psi.KtDestructuringDeclaration
import org.jetbrains.kotlin.psi.KtDestructuringDeclarationEntry
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtForExpression
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtLabelReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtOperationExpression
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtPrefixExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPropertyDelegate
import org.jetbrains.kotlin.psi.KtPrimaryConstructor
import org.jetbrains.kotlin.psi.KtPropertyAccessor
import org.jetbrains.kotlin.psi.KtPsiUtil
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtScriptInitializer
import org.jetbrains.kotlin.psi.KtSecondaryConstructor
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtOperationReferenceExpression
import org.jetbrains.kotlin.psi.KtTypeAlias
import org.jetbrains.kotlin.psi.KtTypeParameter
import org.jetbrains.kotlin.psi.KtUnaryExpression
import org.jetbrains.kotlin.psi.KtUserType
import org.jetbrains.kotlin.psi.KtValueArgumentName
import org.jetbrains.kotlin.psi.KtAnnotatedExpression
import org.jetbrains.kotlin.psi.KtLabeledExpression
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.KtValueArgumentList
import org.jetbrains.kotlin.psi.KtWhenExpression
import org.jetbrains.kotlin.psi.psiUtil.allChildren
import org.jetbrains.kotlin.psi.psiUtil.anyDescendantOfType
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.jetbrains.kotlin.psi.psiUtil.endOffset
import org.jetbrains.kotlin.psi.psiUtil.getReceiverExpression
import org.jetbrains.kotlin.psi.psiUtil.forEachDescendantOfType
import org.jetbrains.kotlin.psi.psiUtil.parents
import org.jetbrains.kotlin.psi.psiUtil.startOffset
import org.jetbrains.kotlin.psi.psiUtil.startOffsetSkippingComments
import java.util.IdentityHashMap
import java.util.PriorityQueue
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.script.experimental.api.ScriptDiagnostic
import kotlin.script.experimental.api.SourceCode

/**
 * A position in the original script, both the [line] and the [column] are 0-based.
 */
internal data class ScriptPosition(
    val line: Int,
    val column: Int
) : Comparable<ScriptPosition> {

    override fun compareTo(
        other: ScriptPosition
    ): Int = compareValuesBy(this, other, { it.line }, { it.column })

}

/** The reference to the [name] in the script, from the [start] to the [end], exclusive. */
internal class ScriptReference(
    val name: String,
    val start: ScriptPosition,
    val end: ScriptPosition
) {

    operator fun contains(
        position: ScriptPosition
    ): Boolean = position >= start && position < end

}

/**
 * The [reference] of the script, which might refer to another declaration than in a regular script,
 * a candidate confirmed by the confirming compilations, see [ScriptSource.confirming].
 */
internal interface CandidateReference {
    val reference: ScriptReference
}

/**
 * The [reference] of the script body to a dependency, preceding the declaration of the local property,
 * or the local function, if [isFunction], of the same name, at the 0-based [line], see [ScriptSource.laterDeclaredDependencyCandidates].
 */
internal class LaterDeclaredDependencyReference(
    override val reference: ScriptReference,
    override val line: Int,
    override val isFunction: Boolean,
    /** Whether it refers to the declaration as a function, like `::foo`, which might be resolved to a property instead. */
    val isCallableReference: Boolean,
    /** The calls of the scope functions, within the lambdas of which it is, like `run` of `run { f(null) }`, see [LaterDeclaredClassReference.scopeCalls]. */
    val scopeCalls: List<ScriptReference> = emptyList()
) : LocalDeclarationReference, PrecedingReference {

    override val kind: String get() = super<LocalDeclarationReference>.kind

}

/**
 * The [reference] of a declaration moved out of the script body to the local property,
 * or the local function, if [isFunction], of the script body, declared at the 0-based [line],
 * which is not visible there, see [ScriptSource.hiddenLocalFunctionCandidates].
 */
internal data class HiddenLocalFunctionReference(
    override val reference: ScriptReference,
    /** Of the first local declaration of the name, or of the one the confirming compilation resolves the reference to, see [localFunctionLine]. */
    val line: Int,
    override val isFunction: Boolean,
    /** Whether it is an operator, or a convention, like `[]` of `get`, sharing its position with other ones, like `set`. */
    val isOperator: Boolean,
    /** The references of its name within it, the warnings at which are of them, see [Reference.inner]. */
    val inner: List<ScriptReference>,
    /** The callee of the delegate expression, where the compiler reports the delegate convention, see [Reference.delegateCallee]. */
    val delegateCallee: ScriptReference?,
    /** The name of the value called by the `invoke` operator, see [Reference.invokedValue]. */
    val invokedValue: String?
) : LocalDeclarationReference, CandidateReference

/**
 * The [reference] of the script body to the property, or the function, if [isFunction], moved out of it, declared at the 0-based [line],
 * which might be resolved to the coroutine scope of the script body instead, see [ScriptSource.coroutineScopeCandidates].
 */
internal class CoroutineScopeReference(
    override val reference: ScriptReference,
    val line: Int,
    override val isFunction: Boolean,
    /** Whether it is within a lambda, which might have a receiver. */
    val isInLambda: Boolean,
    /**
     * The position of the operator of the assignment to the moved `var`, like `=` of `isActive = "b"`, or `++` of `isActive++`, if it is assigned,
     * where the compiler reports the errors of the assignment to the property of the coroutine scope instead, like `Assignment type mismatch`.
     */
    val assignment: ScriptPosition? = null
) : LocalDeclarationReference, CandidateReference

/** The [reference] of the script body preceding the local declaration of this [kind], and of its name, declared at the 0-based [line]. */
internal interface PrecedingReference : CandidateReference {
    val line: Int
    val kind: String
}

/**
 * The [reference] of the script body calling the dependency instead of a constructor of the local class declared at the 0-based [line],
 * within the lambdas of the [scopeCalls], like `run` of `run { Foo(2) }`, see [ScriptSource.laterDeclaredClassCandidates].
 */
internal class LaterDeclaredClassReference(
    override val reference: ScriptReference,
    override val line: Int,
    val scopeCalls: List<ScriptReference>
) : PrecedingReference {

    override val kind: String get() = "class"

}

/**
 * The [reference] of the script body calling a function, preceding the declaration of the local function of its name at the 0-based [line],
 * which is not visible there, so it might call another function, like `print` of the standard library, see [ScriptSource.laterDeclaredFunctionCandidates].
 */
internal data class LaterDeclaredFunctionReference(
    override val reference: ScriptReference,
    /** Of the first local function of the name declared later, or of the one the confirming compilation resolves the call to. */
    val line: Int,
    /**
     * Whether it is within a lambda, a class, or an extension function, and confirmed by the copies of the local functions declared as the local functions preceding it,
     * which take precedence over the members of the receivers, unlike the functions of the script in a regular script,
     * so it is confirmed only if it fails to compile otherwise, as then no member of the receivers applies.
     */
    val isConfirmedOnlyWhenFailing: Boolean,
    /**
     * The positions of its arguments, or of the indices of the array access, or of the right operand of the binary operator,
     * where the compiler reports the errors of the function called instead, like `Argument type mismatch`, explained by it.
     */
    val arguments: List<ScriptPosition>,
    /** The position of the indices of the array access, like `[0]` of `a[0]`, where the compiler reports the missing operator, like `No 'get' operator method`. */
    val indices: ScriptPosition?
) : CandidateReference

/** A reference to a local declaration of the script body, a function, if [isFunction], or a property. */
internal interface LocalDeclarationReference {
    val isFunction: Boolean
    val kind: String get() = if (isFunction) "function" else "property"
}

/**
 * The [reference] of the script body to the local property, or to one of the local functions, if [isFunction], at the 0-based [lines],
 * instead of the function moved out of the script body at the 0-based [functionLine], see [ScriptSource.shadowedFunctionCandidates].
 */
internal data class ShadowedFunctionReference(
    override val reference: ScriptReference,
    val lines: List<Int>,
    override val isFunction: Boolean,
    val functionLine: Int,
    /**
     * Whether it calls the local function within a declaration of an inferred type, like `fun h() = show(1)`, or within a statement,
     * while the overloads are of both inferred and declared types, as a regular script prefers the ones of inferred types there,
     * see [ScriptSource.overloadingFunctionCopies], unlike within the declarations of declared types, like `fun h(): String = show(1)`,
     * or among the overloads of the same kind of types, see [ScriptSource.shadowedFunctionCopies].
     */
    val isInInferredType: Boolean = false
) : LocalDeclarationReference, CandidateReference

/** The deprecation message of the dependencies, see [ScriptSource.laterDeclaredDependencyCandidates]. */
internal const val LATER_DECLARED_DEPENDENCY = "xemantic-kotlin-script: later declared dependency"

/** The deprecation message of the copies of the local functions, see [ScriptSource.laterDeclaredDependencyCandidates]. */
internal const val LATER_DECLARED_FUNCTION = "xemantic-kotlin-script: later declared function"

/** The opt-in message of the copies of the local functions, see [ScriptSource.laterDeclaredFunctionCandidates]. */
internal const val LATER_DECLARED_LOCAL_FUNCTION = "xemantic-kotlin-script: later declared local function"

/**
 * The opt-in message of the copy of the local function of this [name], see [ScriptSource.laterDeclaredFunctionCandidates],
 * with its 0-based [line], if known, telling the overloads apart, while containing the message of any function of the name.
 */
internal fun laterDeclaredFunctionMessage(
    name: String,
    line: Int? = null
): String = localFunctionMessage(LATER_DECLARED_LOCAL_FUNCTION, name, isOperator = false, line)

/** The opt-in message of the copies of the constructors of the local classes, see [ScriptSource.laterDeclaredClassCandidates]. */
internal const val LATER_DECLARED_CLASS = "xemantic-kotlin-script: later declared class"

/** The opt-in message of the copies of the scope functions of the script instance, see [ScriptSource.laterDeclaredClassCandidates]. */
internal const val SCRIPT_RECEIVER = "xemantic-kotlin-script: script receiver"

/** The deprecation message of the copies of the local functions, see [ScriptSource.hiddenLocalFunctionCandidates]. */
internal const val HIDDEN_LOCAL_FUNCTION = "xemantic-kotlin-script: hidden local function"

/**
 * The deprecation message of the copy of the local declaration of this [name], see [ScriptSource.hiddenLocalFunctionCandidates],
 * marked, if it [isOperator], which the delegate conventions reported at the calls of the same name might call, like `by getValue(cfg)`,
 * and with its 0-based [line], if known, telling the overloads apart, while containing the message of any declaration of the name.
 */
internal fun hiddenLocalFunctionMessage(
    name: String,
    isOperator: Boolean = false,
    line: Int? = null
): String = localFunctionMessage(HIDDEN_LOCAL_FUNCTION, name, isOperator, line)

/** The message of the copy of the local declaration of this [name], starting with this [prefix], see [hiddenLocalFunctionMessage]. */
private fun localFunctionMessage(
    prefix: String,
    name: String,
    isOperator: Boolean,
    line: Int?
): String = "$prefix: $name;" + (if (isOperator) " operator;" else "") + (line?.let { " line $it;" } ?: "")

private val localFunctionLineRegex = Regex(
    "(?:${Regex.escape(HIDDEN_LOCAL_FUNCTION)}|${Regex.escape(LATER_DECLARED_LOCAL_FUNCTION)}): .*?;(?: operator;)? line (\\d+);"
)

/**
 * The 0-based line of the local declaration of the deprecation, or opt-in, [message] of its copy, if known,
 * see [hiddenLocalFunctionMessage] and [laterDeclaredFunctionMessage].
 */
internal fun localFunctionLine(
    message: String
): Int? = localFunctionLineRegex.find(message)?.groupValues?.get(1)?.toInt()

/**
 * The string literal of this [value], escaping the characters ending it, or starting an escape, or a template,
 * which a backticked name can contain, like `` `say "hi"` `` or `` `$x` ``.
 */
private fun stringLiteral(
    value: String
): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$") + "\""

/** The deprecation message of the copies of the moved functions, see [ScriptSource.shadowedFunctionCandidates]. */
internal const val SHADOWED_FUNCTION = "xemantic-kotlin-script: shadowed function"

/** The opt-in message of the moved function, or of its copy, declared at the [offset] of the script, see [ScriptSource.shadowedFunctionLine]. */
private fun shadowedFunctionMessage(
    offset: Int
): String = "$SHADOWED_FUNCTION at $offset;"

/** The offset in the message of [shadowedFunctionMessage]. */
private val shadowedFunctionOffset = Regex("${Regex.escape(SHADOWED_FUNCTION)} at (\\d+);")

/**
 * The opt-in message of the local functions, one of which the [ScriptSource.shadowedFunctionCandidates] call, telling which one,
 * declared at the offset of the script, see [ScriptSource.shadowingFunctionLine].
 */
internal const val SHADOWING_FUNCTION = "xemantic-kotlin-script: shadowing function"

/** The opt-in message of the local function declared at the [offset] of the script, see [SHADOWING_FUNCTION]. */
private fun shadowingFunctionMessage(
    offset: Int
): String = "$SHADOWING_FUNCTION at $offset;"

/** The offset in the message of [shadowingFunctionMessage]. */
private val shadowingFunctionOffset = Regex("${Regex.escape(SHADOWING_FUNCTION)} at (\\d+);")

/** The first of the letters replacing the names of the local functions overloading the moved ones, see [ScriptSource.overloadingFunctionCopies]. */
private const val RENAMED_CHARS = '\u4E00'

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

/** The opt-in message of the copies of the moved declarations, see [ScriptSource.coroutineScopeCandidates]. */
internal const val COROUTINE_SCOPE_FUNCTION = "xemantic-kotlin-script: coroutine scope function"

/** The opt-in message of the moved functions, see [ScriptSource.shadowedFunctionCandidates]. */
internal const val SHADOWED_ORIGINAL = "xemantic-kotlin-script: shadowed original"

/** The opt-in annotation marking the local copies of the declarations, reporting this [message] where they are used. */
private fun optInMarker(
    message: String
): String = "`_OptIn_${message.substringAfter(": ").replace(nonIdentifierChars, "_")}`"

private val nonIdentifierChars = Regex("[^A-Za-z0-9_]")

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

/** The type, which the types of the local properties are not, probing them, see [ScriptSource.propertyTypeProbes]. */
private const val TYPE_PROBE_CLASS = "_ScriptTypeProbe"

/** The type of the local property, as the compiler names it in the message of the error of its probe, see [ScriptSource.propertyTypeProbes]. */
private val probedType = Regex("expected '$TYPE_PROBE_CLASS', actual '(.*)'\\.?$")

/** The type of the coroutine scope of the script body, declaring the copies of the moved declarations, see [ScriptSource.coroutineScopeCopies]. */
private const val BODY_SCOPE_CLASS = "`_ScriptBodyScope`"

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
 * A callable dependency shadowed by a local function only is declared there nevertheless, see [calledInsteadDependencies].
 *
 * Declarations are moved by blanking them out with spaces in one copy of their lines,
 * and everything else in the other, so the columns of the script are retained,
 * while each line of the [text] maps back to the line of the script.
 *
 * The sources of the compilations of the same script, like the confirming ones, are derived from the script parsed once, see [Parsed].
 */
internal class ScriptSource private constructor(
    script: String,
    dependencies: List<PreambleDependency>,
    /** The structure of the script, not kept, as the compilation cache keeps the source. */
    structure: ScriptStructure,
    /**
     * Whether it is the source of the compilation confirming the [laterDeclaredDependencyCandidates]
     * and the [hiddenLocalFunctionCandidates], with the declarations they might refer to deprecated, with a warning, as the declarations deprecated with an error are less preferred by the overload resolution,
     * which does not change how the references are resolved, unlike omitting them,
     * while the declarations the candidates should refer to are declared as their deprecated copies,
     * and the suppressions by the script are blanked out.
     */
    private val confirming: Boolean,
    /**
     * Whether the confirming compilation declares the copies of the moved functions preceding the [shadowedFunctionCandidates],
     * recognizing whether they apply, instead of deprecating the moved functions themselves,
     * recognizing whether the candidates refer to them already, each marked with its line.
     */
    private val shadowedFunctionCopies: Boolean,
    /**
     * Whether the confirming compilation renames the local functions overloading the functions moved out of the script body,
     * which the [shadowedFunctionCandidates] within the declarations of inferred types call, see [ShadowedFunctionReference.isInInferredType],
     * declaring their copies next to the moved functions, each marked with its line instead,
     * so the candidates are resolved among all of them, like among the members of the script class in the script,
     * as the compiler prefers the ones of inferred types there, unlike among the local functions, see CLAUDE.md.
     */
    private val overloadingFunctionCopies: Boolean,
    /**
     * Whether the confirming compilation declares the copies of the moved declarations of the [coroutineScopeCandidates]
     * as the members of a subtype of `CoroutineScope`, which the script body is given as its receiver instead,
     * so the candidates refer to them, if they are resolved to this receiver, where the copies apply,
     * and like the members of the script class in a regular script, they are preferred to its extensions, like `launch`,
     * while the receivers of the lambdas, like `coroutineScope { launch { } }`, take precedence, as in a regular script.
     * It also declares the copies of the constructors of the local classes, see [laterDeclaredClassCandidates].
     */
    private val coroutineScopeCopies: Boolean,
    /**
     * Whether it is the source of the compilation probing the types of the [untypedProperties], known only to the compiler,
     * each named in the error of assigning the property to a variable of another type, following its declaration, see [propertyTypes].
     */
    private val propertyTypeProbes: Boolean,
    /**
     * The types of the [untypedProperties], as named by their probes, see [propertyTypeProbes],
     * which the confirming compilation declares their copies of, checking whether they are the same types, see [confirmedPropertyTypes].
     */
    propertyTypes: Map<String, String>,
    /** The local functions declared preceding the statements calling them, see [Parsed.hoisted]. */
    private val hoisting: Hoisting?
) : SourceCode {

    /** The source of the [script] parsed on its own, see [Parsed]. */
    constructor(
        script: String,
        dependencies: List<PreambleDependency> = emptyList(),
        confirming: Boolean = false,
        shadowedFunctionCopies: Boolean = false,
        overloadingFunctionCopies: Boolean = false,
        coroutineScopeCopies: Boolean = false,
        propertyTypeProbes: Boolean = false,
        propertyTypes: Map<String, String> = emptyMap()
    ) : this(
        script, dependencies, scriptStructure(script, dependencies),
        confirming, shadowedFunctionCopies, overloadingFunctionCopies, coroutineScopeCopies, propertyTypeProbes, propertyTypes,
        hoisting = null
    )

    /**
     * The [script] parsed once, giving the sources of its compilations, the regular one, and the ones confirming its candidates,
     * which differ only by the declarations they add or mark, see [ScriptSource.confirming].
     * Once the sources are given, it is not needed anymore, while they do not keep it.
     */
    class Parsed private constructor(
        private val script: String,
        private val dependencies: List<PreambleDependency>,
        private val structure: ScriptStructure,
        private val hoisting: Hoisting?
    ) {

        constructor(
            script: String,
            dependencies: List<PreambleDependency> = emptyList()
        ) : this(script, dependencies, scriptStructure(script, dependencies), hoisting = null)

        /**
         * The script with the local functions declared preceding the statements calling them, see [ScriptStructure.hoisting],
         * which the [unresolved] references of the compilation of its [source] call, with their names,
         * or `null`, if they cannot be, or if the hoisting might change how a candidate of the [source] is resolved,
         * other than the calls of the hoisted functions, see [ScriptSource.candidates].
         */
        fun hoisted(
            source: ScriptSource,
            unresolved: Set<Pair<ScriptPosition, String>>
        ): Parsed? {
            if (hoisting != null) return null
            val hoisting = structure.hoisting(unresolved.associate { (position, name) -> source.offset(position) to name }) ?: return null
            if (source.candidates.any { candidate ->
                    val offset = source.offset(candidate.reference.start)
                    offset in hoisting.region && !(candidate is LaterDeclaredFunctionReference && offset in hoisting.references)
                }) return null
            return Parsed(script, dependencies, structure, hoisting)
        }

        /** The source of the compilation, see the parameters of [ScriptSource]. */
        fun source(
            confirming: Boolean = false,
            shadowedFunctionCopies: Boolean = false,
            overloadingFunctionCopies: Boolean = false,
            coroutineScopeCopies: Boolean = false,
            propertyTypeProbes: Boolean = false,
            propertyTypes: Map<String, String> = emptyMap()
        ): ScriptSource = ScriptSource(
            script, dependencies, structure,
            confirming, shadowedFunctionCopies, overloadingFunctionCopies, coroutineScopeCopies, propertyTypeProbes, propertyTypes,
            hoisting
        )

    }

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
     * or to the local functions overloading them, which hide them there, while in the script they call the moved functions,
     * if they apply, or if they are more specific than the local overloads.
     * They are confirmed by compiling the script with the functions marked with an opt-in, see [confirming],
     * not reporting the calls referring to them already, and the remaining ones by compiling it with their copies,
     * declared as the local functions preceding the calls, which they refer to, if the functions apply, see [shadowedFunctionCopies].
     */
    val shadowedFunctionCandidates: List<ShadowedFunctionReference>

    /**
     * The references of the script body to the declarations moved out of it, named like the members or the extensions
     * of its coroutine scope receiver, like `cancel`, by the 0-based lines of the declarations, which might be resolved
     * to the coroutine scope instead, while in the script they refer to the declarations.
     * They are confirmed by compiling the script with the declarations marked with an opt-in, see [confirming],
     * reporting the ones not referring to them, the properties, and the functions, which apply, recognized by their copies,
     * see [coroutineScopeCopies], which also tell whether the references within the lambdas, like the properties there,
     * are resolved to the coroutine scope of the script body, rather than to the receivers of the lambdas.
     */
    val coroutineScopeCandidates: List<CoroutineScopeReference>

    /**
     * The references of the script body which might refer to the dependencies, preceding the declarations
     * of the local properties of the same names, like `x` in `fun show() = x` followed by `val x = 2`,
     * or of the local functions, when called, while in the script they refer to these declarations.
     * Recognized by the names only, they might refer e.g. to the members of the receivers instead,
     * so they are confirmed by compiling the script with these dependencies deprecated, see [confirming],
     * where the receiver of the script body declares also the deprecated copies of the signatures of these local functions,
     * which the calls refer to, if the local functions would be called in the script.
     */
    val laterDeclaredDependencyCandidates: List<LaterDeclaredDependencyReference>

    /**
     * The calls of the script body which might call the dependencies, preceding the declarations of the local classes of the same names,
     * like `Foo(2)` followed by `class Foo(val v: Int)`, while in the script they call the constructors, which take precedence there, if they apply.
     * They are confirmed by compiling the script with the copies of the constructors, marked with an opt-in, declared as the local functions preceding the calls,
     * see [coroutineScopeCopies], which the calls refer to, if the constructors apply, and with these dependencies deprecated, see [confirming],
     * which the calls refer to without the copies, unless they refer e.g. to the members of the receivers, which take precedence in the script, unlike the local copies.
     * The calls within the lambdas of the receiver, which is the script instance in the script, like `run { Foo(2) }`, call the dependency, its member, there,
     * so they are not reported, if the coroutine scope of the script body declares the copies of these scope functions, see [coroutineScopeCopies],
     * which the calls enclosing them refer to, unless e.g. a receiver of an enclosing class or lambda takes precedence, like `buildString { run { Foo(2) } }`.
     */
    val laterDeclaredClassCandidates: List<LaterDeclaredClassReference>

    /**
     * The calls of the script body preceding the declarations of the local functions of their names, unlike of the dependencies,
     * like `print("x")` followed by `fun print(x: Any)`, which call other functions, like `print` of the standard library,
     * or fail, like `V(1) + V(2)` resolved to `plus` of the coroutine scope of the script body,
     * while in the script they call the local functions, if they apply.
     * They are confirmed by compiling the script with the copies of the local functions, marked with an opt-in, see [confirming],
     * declared as the members of the script, like the functions of a regular script, the members of the receivers taking precedence over them,
     * or, if they refer to the local classes, which the members would not see, as the local functions preceding the calls,
     * which take precedence over the members of the receivers, unlike in the script, see [LaterDeclaredFunctionReference.isConfirmedOnlyWhenFailing].
     */
    val laterDeclaredFunctionCandidates: List<LaterDeclaredFunctionReference>

    /**
     * The calls of the declarations moved out of the script body named like the local functions of the script body,
     * by the 0-based lines of the first ones, which are not visible there, so they might be resolved to other functions,
     * like `check` of the standard library, while in the script they refer to the local functions.
     * They are confirmed by compiling the script with the deprecated copies of the local functions, see [confirming],
     * as the members of the script, which the calls refer to, unless they refer e.g. to the inherited members.
     */
    val hiddenLocalFunctionCandidates: List<HiddenLocalFunctionReference>

    /**
     * The names of the callable dependencies visible to the declarations moved out of the script body, even though local functions have their names,
     * so the moved declarations refer to them as values, like `map(log)`, and call them, when the local functions do not apply, as in the script,
     * while the [hiddenLocalFunctionCandidates] calling the local functions in the script call the dependencies instead,
     * or fail, when the dependencies do not apply, rather than being unresolved.
     */
    val calledInsteadDependencies: Set<String>

    /**
     * The [hiddenLocalFunctionCandidates] calling the local properties, whose types are known only to the compiler, like `val print = printer()`,
     * which might not be callable, like `delay(1)` of `val delay = 10L * 2`, so they are confirmed only with their copies of these types,
     * probed by another compilation, see [propertyTypeProbes], and confirmed to be the same by the confirming one, see [confirmedPropertyTypes].
     */
    val untypedPropertyCalls: Set<HiddenLocalFunctionReference>

    /** The names of the local properties, which the [untypedPropertyCalls] call. */
    val untypedProperties: Set<String>

    /** All the candidates, which the confirming compilations confirm, see [confirming]. */
    val candidates: List<CandidateReference>
        get() = laterDeclaredDependencyCandidates + laterDeclaredClassCandidates + laterDeclaredFunctionCandidates + hiddenLocalFunctionCandidates +
                shadowedFunctionCandidates + coroutineScopeCandidates

    /** The names of the [untypedProperties], by the 0-based lines of the [text] probing, or checking, their types, see [propertyTypeProbes]. */
    private val propertyTypeLines = mutableMapOf<Int, String>()

    /** The names of the [untypedProperties], which the confirming compilation declares the copies of, see [propertyTypes]. */
    val copiedProperties: Set<String>

    /**
     * The callable references of the classes moved out of the script body to the moved functions and properties, or to the dependencies,
     * like `::log`, or to the moved extensions, like `String::up`, which are the members of the script class, crashing the compiler backend,
     * from the whole callable reference to the end of the name, recognized by the names only, without the ones bound in the classes,
     * with their texts in a single line.
     */
    val scriptMemberCallableReferences: List<Pair<ScriptReference, String>>

    /** The package of the script, like `foo.bar`, or an empty string, if it has none. */
    val packageName: String

    private val lineStarts: List<Int>

    /** For each line of the [text], the line of the script, or `null` for the lines of the block and the preamble. */
    private val lineMapping: List<Int?>

    /** The names of the dependencies, by the 0-based lines of the [text] declaring their accessors. */
    private val preambleDependencies = mutableMapOf<Int, String>()

    private val movedDeclarations: List<Declaration>

    private val bodyDeclarations: List<Declaration>

    /** See [ScriptStructure.localReferences]. */
    private val localReferences: Map<String, Set<String>>

    init {
        val lineBounds = lineBounds(script)
        lineStarts = lineBounds.map { it.first }
        scriptLines = lineBounds.map { script.substring(it.first, it.last + 1) }
        unmatchedClosingBrace = structure.unmatchedClosingBrace?.let { position(it) }
        movedDeclarations = structure.movedDeclarations
        returnsUnit = structure.endsWithMovedDeclaration || hoisting?.endsWithHoistedFunction == true
        packageName = structure.packageName
        fun IntRange.toScriptReference(
            name: String = script.substring(first, last + 1)
        ) = ScriptReference(name, position(first), position(last + 1))
        conflictingDependencyDeclarations = structure.conflictingDependencyDeclarations.map { (name, range) ->
            range.toScriptReference(name)
        }
        fun Reference.toScriptReference() = (offset until offset + length).toScriptReference(name)
        laterDeclaredDependencyCandidates = structure.laterDeclaredDependencyCandidates.map { candidate ->
            LaterDeclaredDependencyReference(
                candidate.reference.toScriptReference(),
                position(candidate.offset).line,
                candidate.isFunction,
                candidate.reference.isCallableReference,
                candidate.scopeCalls.map { it.toScriptReference() }
            )
        }
        laterDeclaredClassCandidates = structure.laterDeclaredClassCandidates.map { candidate ->
            LaterDeclaredClassReference(
                candidate.reference.toScriptReference(),
                position(candidate.classOffset).line,
                candidate.scopeCalls.map { it.toScriptReference() }
            )
        }
        // calling the hoisted functions, as in the script
        laterDeclaredFunctionCandidates = structure.laterDeclaredFunctionCandidates.filter {
            hoisting == null || it.reference.offset !in hoisting.references
        }.map { candidate ->
            LaterDeclaredFunctionReference(
                candidate.reference.toScriptReference(),
                position(candidate.offset).line,
                candidate.isConfirmedOnlyWhenFailing,
                candidate.arguments.map { position(it) },
                candidate.indices?.let { position(it) }
            )
        }
        hiddenLocalFunctionCandidates = structure.hiddenLocalFunctionCandidates.map { (reference, offset, isFunction) ->
            HiddenLocalFunctionReference(
                reference.toScriptReference(), position(offset).line, isFunction, reference.isOperator,
                reference.inner.map { it.toScriptReference(reference.name) },
                reference.delegateCallee?.toScriptReference(reference.name),
                reference.invokedValue
            )
        }
        untypedPropertyCalls = structure.hiddenLocalFunctionCandidates.zip(hiddenLocalFunctionCandidates)
            .filter { (candidate) -> candidate.first in structure.untypedPropertyCalls }
            .mapTo(HashSet()) { it.second }
        untypedProperties = structure.untypedProperties
        val copiedPropertyTypes = structure.copiedPropertyTypes(propertyTypes)
        copiedProperties = copiedPropertyTypes.keys
        shadowedFunctionCandidates = structure.shadowedFunctionCandidates.map { candidate ->
            ShadowedFunctionReference(
                candidate.reference.toScriptReference(),
                candidate.locals.map { position(it).line },
                candidate.isFunction,
                position(candidate.function).line,
                candidate.isInInferredType
            )
        }
        coroutineScopeCandidates = structure.coroutineScopeCandidates.map { (reference, offset, isFunction, assignment) ->
            CoroutineScopeReference(reference.toScriptReference(), position(offset).line, isFunction, reference.isInLambda, assignment?.let { position(it) })
        }
        scriptMemberCallableReferences = structure.scriptMemberCallableReferences.map { reference ->
            // like `String::up` split across lines
            reference.second.toScriptReference(reference.first) to script.substring(reference.second).split(lineBreak).joinToString("")
        }
        bodyDeclarations = structure.bodyDeclarations
        localReferences = structure.localReferences
        val deprecatedDependencies = if (confirming) {
            (laterDeclaredDependencyCandidates + laterDeclaredClassCandidates).mapTo(HashSet()) { it.reference.name }
        } else {
            emptySet()
        }

        val lines = mutableListOf<String>()
        val mapping = mutableListOf<Int?>()
        // the confirming compilation reports the deprecations, even if suppressed by the script
        val unsuppressed = BooleanArray(script.length)
        if (confirming || propertyTypeProbes) structure.suppressions.forEach { unsuppressed.fill(true, it.first, it.last + 1) }
        val renamed = if (overloadingFunctionCopies && structure.renamedRanges.isNotEmpty()) {
            CharArray(script.length).also { renamed -> structure.renamedRanges.forEach { (range, char) -> renamed.fill(char, range.first, range.last + 1) } }
        } else {
            null
        }
        fun addLines(
            range: IntRange,
            keep: (offset: Int) -> Boolean
        ) = range.forEach { line ->
            val bounds = lineBounds[line]
            lines += buildString {
                bounds.forEach { offset ->
                    +(if (keep(offset) && !unsuppressed[offset]) (renamed?.get(offset)?.takeIf { it != '\u0000' } ?: script[offset]) else ' ')
                }
            }
            mapping += line
        }
        fun lineOf(offset: Int) = lineOf(lineStarts, offset)
        val excluded = BooleanArray(script.length)
        movedDeclarations.forEach { excluded.fill(true, it.start, it.end) }
        structure.blankedRanges.forEach { excluded.fill(true, it.first, it.last + 1) }
        val hoisted = BooleanArray(script.length)
        hoisting?.functions?.values?.flatten()?.forEach { hoisted.fill(true, it.first, it.last + 1) }
        val hoistedByLine = hoisting?.functions?.mapKeys { lineOf(it.key) }.orEmpty()
        val movedExcluded = BooleanArray(script.length)
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
                "@kotlin.Deprecated(${stringLiteral(LATER_DECLARED_DEPENDENCY)}) ${dependency.accessor}"
            } else {
                dependency.accessor
            }
            mapping += null
        }
        // only the first confirming compilation confirms the laterDeclaredFunctionCandidates,
        // while their copies would take precedence over the copies of the other ones, like the members of the coroutine scope of the script body
        val confirmsLaterDeclaredFunctions = confirming && !shadowedFunctionCopies && !overloadingFunctionCopies && !coroutineScopeCopies
        val blockCopies = if (confirming) {
            structure.blockCopies(shadowedFunctionCopies, coroutineScopeCopies, withLaterDeclaredFunctions = confirmsLaterDeclaredFunctions)
        } else {
            emptyMap()
        }
        // the members of the script copying the local declarations, see MemberCopy
        val memberCopies = mutableListOf<MemberCopy>()
        if (confirming) {
            // named in the message, as the operators of the same position refer to different functions, like `get` and `set`,
            // unlike the copies of the local functions overloading the moved ones, see overloadingFunctionCopies
            if (!overloadingFunctionCopies) structure.hiddenLocalFunctionCopies(copiedPropertyTypes).forEach { copy ->
                val message = hiddenLocalFunctionMessage(copy.name, copy.isOperator, copy.offset?.let { position(it).line })
                memberCopies += MemberCopy(
                    copy.offset ?: Int.MAX_VALUE,
                    "@kotlin.Deprecated(${stringLiteral(message)}) ${copy.declaration}",
                    // the type of the copy of the property might be an invalid one, see confirmedPropertyTypes
                    copy.name.takeIf { it in copiedProperties }
                )
            }
            // the members of the script, as in a regular script, see laterDeclaredFunctionCandidates
            if (confirmsLaterDeclaredFunctions) structure.laterDeclaredFunctionMemberCopies.forEach { (offset, message, declaration) ->
                lines += "@kotlin.RequiresOptIn(${stringLiteral(message)}, level = RequiresOptIn.Level.WARNING) annotation class ${optInMarker(message)}"
                mapping += null
                memberCopies += MemberCopy(offset, "@${optInMarker(message)} $declaration")
            }
            // resolved together with the moved functions they overload, as in the script, see shadowedFunctionCopies
            if (overloadingFunctionCopies) structure.overloadingCopies.forEach { (offset, copy) ->
                memberCopies += MemberCopy(offset, copy)
            }
            // the deprecation of the local functions, like the copies in the script block, is not reported, unlike their opt-in
            val blockCopyMessages = blockCopies.values.flatten().flatMap { it.first }
            (listOf(LATER_DECLARED_FUNCTION, SHADOWED_FUNCTION, SHADOWED_ORIGINAL, COROUTINE_SCOPE_ORIGINAL, COROUTINE_SCOPE_FUNCTION, SCRIPT_RECEIVER) + blockCopyMessages).distinct().forEach { message ->
                lines += "@kotlin.RequiresOptIn(${stringLiteral(message)}, level = RequiresOptIn.Level.WARNING) annotation class ${optInMarker(message)}"
                mapping += null
            }
        }
        if (propertyTypeProbes && untypedProperties.isNotEmpty()) {
            lines += "class $TYPE_PROBE_CLASS"
            mapping += null
        }
        // the types probed, or checked, whether the ones of the copies are the same, see propertyTypeProbes
        val checkedPropertyTypes = if (confirming && !overloadingFunctionCopies && !propertyTypeProbes) copiedPropertyTypes else emptyMap()
        structure.propertyTypeCheckFunctions(checkedPropertyTypes).forEach { (name, declaration) ->
            memberCopies += MemberCopy(Int.MAX_VALUE, declaration, name)
        }
        memberCopies.sortBy { it.offset }
        var memberCopyIndex = 0
        /** Adds the [memberCopies] of the originals preceding this [offset]. */
        fun addMemberCopies(
            offset: Int
        ) {
            while (memberCopyIndex < memberCopies.size && memberCopies[memberCopyIndex].offset <= offset) {
                val copy = memberCopies[memberCopyIndex++]
                if (copy.propertyTypeName != null) propertyTypeLines[lines.size] = copy.propertyTypeName
                lines += copy.declaration
                mapping += null
            }
        }
        // a callable one is visible to the moved declarations referring to it as a value, like `map(log)`, as in the script,
        // while their calls, which in the script call the local function, if it applies, are confirmed, see hiddenLocalFunctionCandidates
        val (shadowed, visible) = dependencies.partition {
            it.name in structure.localProperties || (it.isCallable && it.name in structure.unconfirmedLocalFunctions)
        }
        calledInsteadDependencies = visible.filter { it.isCallable && it.name in structure.localFunctions }.mapTo(HashSet()) { it.name }
        visible.forEach { addAccessor(it) }
        lines += "class $DEPENDENCIES_CLASS {"
        mapping += null
        // the members of the outer receiver of the script body would be called instead of the moved functions
        shadowed.filterNot { it.isCallable && it.name in structure.movedFunctions }.forEach { addAccessor(it) }
        lines += "}"
        mapping += null
        val markedOriginals = confirming && !shadowedFunctionCopies && !overloadingFunctionCopies && !coroutineScopeCopies
        val deprecatedFunctions = if (markedOriginals) {
            shadowedFunctionCandidates.mapTo(HashSet()) { it.reference.name }
        } else {
            emptySet()
        }
        val coroutineScopeDeclarations = if (markedOriginals) {
            coroutineScopeCandidates.mapTo(HashSet()) { it.reference.name }
        } else {
            emptySet()
        }
        movedDeclarations.forEach { declaration ->
            addMemberCopies(declaration.start)
            // opt-in markers, which can mark the same declaration, unlike the deprecations
            if (declaration.kind == DeclarationKind.FUNCTION && declaration.name in deprecatedFunctions) {
                lines += "@${optInMarker(SHADOWED_ORIGINAL)}"
                mapping += null
            }
            // each marked, as the message tells which one is called
            if (overloadingFunctionCopies && declaration.kind == DeclarationKind.FUNCTION && declaration.name in structure.overloadedFunctions) {
                val message = shadowedFunctionMessage(declaration.nameRange?.first ?: declaration.start)
                lines += "@kotlin.RequiresOptIn(${stringLiteral(message)}, level = RequiresOptIn.Level.WARNING) annotation class ${optInMarker(message)}"
                lines += "@${optInMarker(message)}"
                mapping += null
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
        addMemberCopies(Int.MAX_VALUE)
        // following the moved type aliases, which are not visible before their declarations
        if (coroutineScopeCopies) {
            lines += "abstract class $BODY_SCOPE_CLASS : kotlinx.coroutines.CoroutineScope {"
            lines += structure.coroutineScopeMembers.map { "@${optInMarker(COROUTINE_SCOPE_FUNCTION)} $it" }
            lines += structure.scopeFunctionCopies.map { "@${optInMarker(SCRIPT_RECEIVER)} $it" }
            lines += "}"
            repeat(structure.coroutineScopeMembers.size + structure.scopeFunctionCopies.size + 2) { mapping += null }
        }
        // each marked on the line preceding it, telling which one is called, see SHADOWING_FUNCTION
        val shadowingMessagesByLine = if (markedOriginals) {
            structure.shadowingFunctions.filterKeys { !excluded[it] && !hoisted[it] }.entries.associate { (start, offset) ->
                lineOf(start) to shadowingFunctionMessage(offset)
            }
        } else {
            emptyMap()
        }
        shadowingMessagesByLine.values.forEach { message ->
            lines += "@kotlin.RequiresOptIn(${stringLiteral(message)}, level = RequiresOptIn.Level.WARNING) annotation class ${optInMarker(message)}"
            mapping += null
        }
        // qualified, as the script might declare a function of its name
        lines += if (coroutineScopeCopies) "$SCRIPT_BLOCK_START kotlin.with(this as $BODY_SCOPE_CLASS) {" else SCRIPT_BLOCK_START
        mapping += null
        // the copies preceding the statements, where the local classes declared before them are visible
        val blockCopiesByLine = blockCopies.entries.groupBy({ lineOf(it.key) }) { it.value }
        // the probes on the lines following the local properties, or at the end of the script block, see propertyTypeProbes,
        // by the lines of the script, which the parsed script, with `\r\n` replaced by `\n\n`, does not tell
        val propertyTypeStatements = structure.propertyTypeStatements(
            if (propertyTypeProbes) untypedProperties else checkedPropertyTypes.keys,
            probes = propertyTypeProbes
        ).groupBy({ (end) ->
            (lineOf(end - 1) + 1).takeIf { it <= lineBounds.lastIndex }
        }) { it.second to it.third }
        fun addPropertyTypeStatements(
            line: Int?
        ) = propertyTypeStatements[line]?.forEach { (name, statement) ->
            propertyTypeLines[lines.size] = name
            lines += statement
            mapping += null
        }
        (lineOf(headerEnd)..lineBounds.lastIndex).forEach { line ->
            addPropertyTypeStatements(line)
            // separated, as the lines they are inserted between might be parsed as one expression otherwise
            hoistedByLine[line]?.let { functions ->
                lines += ";"
                mapping += null
                functions.forEach { function ->
                    addLines(lineOf(function.first)..lineOf(function.last)) { it in function && !excluded[it] }
                }
                lines += ";"
                mapping += null
            }
            blockCopiesByLine[line]?.flatten()?.forEach { (messages, declaration) ->
                lines += messages.joinToString(separator = " ", postfix = " ") { "@${optInMarker(it)}" } + declaration
                mapping += null
            }
            shadowingMessagesByLine[line]?.let { message ->
                lines += "@${optInMarker(message)}"
                mapping += null
            }
            addLines(line..line) { offset ->
                offset >= headerEnd && !excluded[offset] && !hoisted[offset]
            }
        }
        addPropertyTypeStatements(null)
        if (returnsUnit) {
            // the block would return the value of the statement before the declaration, unlike the script
            lines += "null"
            mapping += null
        }
        lines += if (coroutineScopeCopies) "} $SCRIPT_BLOCK_END" else SCRIPT_BLOCK_END
        mapping += null

        text = lines.joinToString(separator = "\n")
        lineMapping = mapping
        // unique, as the frames of the script are recognized by it, also among the frames of other scripts
        @OptIn(ExperimentalAtomicApi::class)
        locationId = "\$${sourceIdSeq.incrementAndFetch()}.kts"
    }

    /**
     * The types of the [untypedProperties], as named by the compiler in these [reports] of the compilation of this source,
     * see [propertyTypeProbes], by their names.
     */
    fun propertyTypes(
        reports: List<ScriptDiagnostic>,
        /** Receives the other errors of the probes, like `Variable must be initialized` of `val print: Printer`, or the unexpected messages. */
        unexpected: (ScriptDiagnostic) -> Unit = {}
    ): Map<String, String> = propertyTypeErrors(reports).mapNotNull { (name, diagnostic) ->
        probedType.find(diagnostic.message)?.let { name to it.groupValues[1] } ?: run {
            unexpected(diagnostic)
            null
        }
    }.toMap()

    /**
     * The names of the [untypedProperties], whose copies the confirming compilation declares of the same types as theirs,
     * unless these [reports] of the compilation of this source contain the errors of their copies, or of their checks, see [propertyTypes].
     */
    fun confirmedPropertyTypes(
        reports: List<ScriptDiagnostic>
    ): Set<String> = copiedProperties - propertyTypeErrors(reports).mapTo(HashSet()) { it.first }

    /** The errors of these [reports] at the lines probing, or checking, the types of the [untypedProperties], by their names. */
    private fun propertyTypeErrors(
        reports: List<ScriptDiagnostic>
    ): List<Pair<String, ScriptDiagnostic>> = reports.filter { it.severity == ScriptDiagnostic.Severity.ERROR }.mapNotNull { diagnostic ->
        diagnostic.location?.let { propertyTypeLines[it.start.line - 1] }?.let { it to diagnostic }
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

    /** The 0-based line of the local function named in this opt-in [message], see [SHADOWING_FUNCTION]. */
    fun shadowingFunctionLine(
        message: String
    ): Int? = shadowingFunctionOffset.find(message)?.groupValues?.get(1)?.toInt()
        ?.let { position(it).line }

    /** The 0-based line of the moved function, or of its copy, named in this opt-in [message], see [shadowedFunctionCopies]. */
    fun shadowedFunctionLine(
        message: String
    ): Int? = shadowedFunctionOffset.find(message)?.groupValues?.get(1)?.toInt()
        ?.let { position(it).line }

    /**
     * The start and the end of the name of the innermost declaration of the [path] found in the script,
     * like `I` of `T.I.f`, starting with the declaration moved out of the script body, like `T`.
     */
    fun movedDeclarationPath(
        path: List<String>
    ): Pair<ScriptPosition, ScriptPosition>? = movedDeclarations.find { it.name == path.firstOrNull() }?.let { declaration ->
        (path.lastIndex downTo 1).firstNotNullOfOrNull { declaration.nestedPathRanges[path.subList(1, it + 1).joinToString(".")] }
            ?: declaration.nameRange
    }?.let {
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

    /** The offset in the script of the [position]. */
    fun offset(
        position: ScriptPosition
    ): Int = lineStarts[position.line] + position.column

    /**
     * The name of the local function containing the [position], which the local function of the [name] calls back,
     * directly, or by the local functions it calls, so they cannot be declared one preceding the other, see [Parsed.hoisted].
     */
    fun callsBack(
        name: String,
        position: ScriptPosition
    ): String? {
        val offset = offset(position)
        val caller = bodyDeclarations.find {
            it.kind == DeclarationKind.FUNCTION && offset >= it.start && offset < it.end
        }?.name ?: return null
        return caller.takeIf {
            caller != name && caller in listOf(name).closure { function -> localReferences[function].orEmpty().filter { it in localReferences } }
        }
    }

    /**
     * The names of the local properties, which the local function of the [name] refers to, declared between the [position] and the function,
     * which are not visible to it there, in the order of their declaration.
     */
    fun laterAccessedProperties(
        name: String,
        position: ScriptPosition
    ): List<String> {
        val offset = offset(position)
        val function = bodyDeclarations.find { it.kind == DeclarationKind.FUNCTION && it.name == name } ?: return emptyList()
        val references = localReferences[name].orEmpty()
        return bodyDeclarations.filter { it.kind == DeclarationKind.PROPERTY && it.start > offset && it.start < function.start }
            .flatMap { listOfNotNull(it.name) + it.destructuredNames }
            .filter { it in references }
    }

    private fun position(
        offset: Int
    ): ScriptPosition {
        val line = lineOf(lineStarts, offset)
        return ScriptPosition(line, offset - lineStarts[line])
    }

}

private val lineBreak = Regex("\\s*\n\\s*")

@OptIn(ExperimentalAtomicApi::class)
private val sourceIdSeq = AtomicLong(0)

/** The structure of the [script], see [ScriptStructure]. */
private fun scriptStructure(
    script: String,
    dependencies: List<PreambleDependency>
) = ScriptStructure(
    // the parser recognizes only `\n` as a line terminator, replacing each character keeps the offsets
    script.replace('\r', '\n'),
    dependencies.mapTo(HashSet()) { it.name },
    lineBounds(script).map { it.first }
)

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

/**
 * See [ScriptSource.coroutineScopeCandidates], with the [offset] of the declaration, whether it is a function,
 * and the offset of the operator of the assignment to the moved `var`, if the [reference] is assigned, see [CoroutineScopeReference.assignment].
 */
private data class CoroutineScopeCandidate(
    val reference: Reference,
    val offset: Int,
    val isFunction: Boolean,
    val assignment: Int?
)

/** See [ScriptSource.shadowedFunctionCandidates], with the offsets of the local declarations and of the first moved function. */
private class ShadowedCandidate(
    val reference: Reference,
    val locals: List<Int>,
    val function: Int,
    val isFunction: Boolean,
    /** See [ShadowedFunctionReference.isInInferredType]. */
    val isInInferredType: Boolean = false
)

/**
 * See [ScriptSource.laterDeclaredFunctionCandidates], with the [offset] of the name of the first local function declared later,
 * and the offsets of the [arguments] and the [indices], see [LaterDeclaredFunctionReference].
 */
private class LaterFunctionCandidate(
    val reference: Reference,
    val offset: Int,
    val isConfirmedOnlyWhenFailing: Boolean,
    val arguments: List<Int>,
    val indices: Int?
)

/**
 * The starts of the arguments of the call of this [reference], or of the indices of the array access, or of the right operand of the binary operator,
 * where the compiler reports the errors of the function called instead, like `Argument type mismatch`, see [LaterDeclaredFunctionReference.arguments],
 * and the start of the indices of the array access, see [LaterDeclaredFunctionReference.indices].
 */
private fun KtFile.argumentStarts(
    reference: Reference
): Pair<List<Int>, Int?> {
    generateSequence(findElementAt(reference.offset)) { it.parent }.forEach { element ->
        when {
            element is KtCallExpression && element.calleeExpression?.let { it.startOffset == reference.offset && it.textLength == reference.length } == true ->
                return element.valueArguments.mapNotNull { it.getArgumentExpression()?.startOffset } to null
            element is KtArrayAccessExpression && element.startOffset == reference.offset && element.textLength == reference.length ->
                return element.indexExpressions.map { it.startOffset } to element.indicesNode.startOffset
            element is KtBinaryExpression && element.operationReference.startOffset == reference.offset ->
                return listOfNotNull(element.right?.startOffset) to null
        }
    }
    return emptyList<Int>() to null
}

/**
 * See [ScriptSource.laterDeclaredDependencyCandidates], with the [offset] of the declaration, whether it is a function,
 * and the ranges of the [LaterDeclaredDependencyReference.scopeCalls].
 */
private data class LaterDeclaredCandidate(
    val reference: Reference,
    val offset: Int,
    val isFunction: Boolean,
    val scopeCalls: List<IntRange> = emptyList()
)

/** See [ScriptSource.laterDeclaredClassCandidates], with the offset of the class, and the ranges of the [LaterDeclaredClassReference.scopeCalls]. */
private class ClassCandidate(
    val reference: Reference,
    val classOffset: Int,
    val scopeCalls: List<IntRange>
)

private class Declaration(
    val name: String?,
    val kind: DeclarationKind,
    val start: Int,
    val end: Int,
    val nameRange: IntRange?,
    /** The names of a destructuring declaration, like `val (a, b) = pair`. */
    val destructuredNames: List<String> = emptyList(),
    /** The ranges of the names of the classes nested in the declaration, by these names, the first one of each name. */
    val nestedNameRanges: Map<String, IntRange> = emptyMap(),
    /**
     * The ranges of the names of the classes and the functions nested in the declaration, by their paths within it, like `I.f`,
     * as the compiler names them in the failures of its backend, the first one of each path.
     */
    val nestedPathRanges: Map<String, IntRange> = emptyMap()
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

/** Whether it is named in the path of the code, which the compiler reports in the failures of its backend, unlike e.g. an object literal. */
private fun KtNamedDeclaration.isPathElement(): Boolean =
    (this is KtClassOrObject && !(this is KtObjectDeclaration && isObjectLiteral())) || this is KtNamedFunction

private fun KtNamedDeclaration.pathName(): String? =
    name ?: if (this is KtObjectDeclaration && isCompanion()) "Companion" else null

private fun KtDeclaration.memberOnlyAnnotationEntries(): List<KtAnnotationEntry> = annotationEntries.filter {
    it.useSiteTarget != null || it.shortName?.asString() in memberOnlyAnnotations
}

/**
 * Whether the function is of an inferred type, and might call a function within the scope of an implicit receiver,
 * like its receiver, or the one of a lambda, where the script, calling it before its declaration,
 * resolves all of its functions after the members of the receivers, unlike a local function, see [ScriptStructure.hoisting].
 */
private fun KtNamedFunction.mightResolveAfterImplicitReceivers(): Boolean =
    !hasBlockBody() && !hasDeclaredReturnType() && (
        receiverTypeReference != null || modifierList?.contextReceiverList != null
            || bodyExpression?.anyDescendantOfType<KtElement> { it is KtLambdaExpression || it is KtNamedFunction || it is KtClassOrObject } == true
            || valueParameters.any { parameter -> parameter.defaultValue?.anyDescendantOfType<KtElement> { it is KtLambdaExpression || it is KtNamedFunction || it is KtClassOrObject } == true }
    )

/**
 * The names this function refers to unqualified, like `f` of `f(1)`, unlike `x.f()`, or a named argument,
 * unless it declares a value, or a function, of the same name, which might be referred to instead, like the parameter of a lambda.
 */
private fun KtNamedFunction.unboundReferences(): Set<String> {
    val bound = collectDescendantsOfType<KtNamedDeclaration> { it is KtParameter || it is KtProperty || it is KtDestructuringDeclarationEntry || it is KtNamedFunction }
        .mapNotNullTo(HashSet()) { it.name }
    return collectDescendantsOfType<KtNameReferenceExpression> { reference ->
        val parent = reference.parent
        parent !is KtValueArgumentName && parent !is KtUserType
                && (parent as? KtQualifiedExpression)?.selectorExpression != reference
                && !(parent is KtCallExpression && (parent.parent as? KtQualifiedExpression)?.selectorExpression == parent)
    }.mapTo(HashSet()) { it.getReferencedName() } - bound
}

/**
 * The declaration of a function of the same signature, which is never called, like `fun f(s: String): Nothing = kotlin.TODO()`,
 * without the default values of the parameters, which might refer to the local declarations,
 * of the return type inferred, if it is inferred in the script, and if it [keepsInferredType],
 * otherwise declared as `Nothing`, like the return type of a block body.
 */
private fun KtNamedFunction.signature(
    keepsInferredType: Boolean = true,
    /** The replacements of the names of the types, see [singleLineText]. */
    renamed: Map<PsiElement, String> = emptyMap(),
    /** The receiver of the copy, like `kotlinx.coroutines.CoroutineScope`, unless the function has one already. */
    receiver: String? = null
): String? = if (
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
    +typeParameters.copiedTypeParameters(renamed)
    receiverTypeReference?.let {
        +"("
        +it.singleLineText(renamed)
        +")."
    } ?: receiver?.let {
        +it
        +"."
    }
    +"`$name`("
    +valueParameters.copiedParameters(renamed)
    +")"
    // keeping the type of the call, which might be an argument of another one, inferred if it is inferred from the expression,
    // as the compiler prefers the script functions of the inferred types to the other ones, see CLAUDE.md,
    // so the confirming compilation reports that the inferred `Nothing` should be declared, an error ignored there,
    // as declaring it would lose this preference, while `Nothing` applies wherever the type of the original might be expected
    when {
        typeReference != null -> +": ${typeReference!!.singleLineText(renamed)}"
        !isOfInferredType() || !keepsInferredType -> +": Nothing"
    }
    typeConstraintList?.let {
        +" where "
        +it.singleLineText(renamed)
    }
    +" = kotlin.TODO()"
}

/** The type parameters of a copy, like `<T: Number> `, without the variance of the type parameters of a class. */
private fun List<KtTypeParameter>.copiedTypeParameters(
    /** The replacements of the names of the types, see [singleLineText]. */
    renamed: Map<PsiElement, String> = emptyMap()
): String = if (isEmpty()) "" else joinToString(prefix = "<", postfix = "> ") { parameter ->
    // as written, as the quoted name is not recognized by the `where` constraints
    parameter.nameIdentifier?.text + (parameter.extendsBound?.let { ": ${it.singleLineText(renamed)}" } ?: "")
}

/** The value parameters of a copy, without the default values, which might refer to the local declarations, see [signature]. */
private fun List<KtParameter>.copiedParameters(
    /** The replacements of the names of the types, see [singleLineText]. */
    renamed: Map<PsiElement, String> = emptyMap()
): String = joinToString { parameter ->
    (if (parameter.isVarArg) "vararg " else "") + "`${parameter.name}`: ${parameter.typeReference?.singleLineText(renamed)}" +
            (if (parameter.hasDefaultValue()) " = kotlin.TODO()" else "")
}

/**
 * The declarations of the functions named like the class, of the same signatures as its constructors visible to the script body,
 * like `fun Foo(v: Int): Nothing = kotlin.TODO()`, see [signature], except the ones referring to the types of these [invisibleTypes],
 * like the local classes declared later, the values of which cannot precede them, or the ones which cannot be copied, e.g. of a script with a syntax error.
 * The class itself, like in `next: Foo?`, is replaced with a class of its type parameters declared preceding the copies, which no value preceding the class is of either.
 */
private fun KtClass.constructorCopies(
    invisibleTypes: Set<String>
): List<String> {
    if (typeParameters.any { it.nameIdentifier == null }) return emptyList()
    val ownTypes = typeParameters.mapNotNullTo(HashSet()) { it.name } + listOfNotNull(name)
    fun PsiElement.refersToInvisibleTypes() = collectDescendantsOfType<KtUserType>().any {
        it.referencedName in invisibleTypes && it.referencedName !in ownTypes
    }
    val self = "`$LATER_DECLARED_CLASS_PREFIX$name`"
    // unlike a qualifier, like of `Foo.Kind`
    val renamed = collectDescendantsOfType<KtUserType> { it.qualifier == null && it.parent !is KtUserType && it.referencedName == name }
        .mapNotNull { it.referenceExpression?.getReferencedNameElement() }
        .associateWith { self }
    if (listOfNotNull(typeParameterList, typeConstraintList).any { it.refersToInvisibleTypes() }) return emptyList()
    // without any declared, the implicit one without parameters
    val constructors: List<KtConstructor<*>?> = if (primaryConstructor == null && secondaryConstructors.isEmpty()) listOf(null) else {
        listOfNotNull(primaryConstructor) + secondaryConstructors
    }
    return constructors.filterNot { constructor ->
        // so the script body calls the dependency instead, as in the script
        constructor != null && (constructor.hasModifier(KtTokens.PRIVATE_KEYWORD) || constructor.hasModifier(KtTokens.PROTECTED_KEYWORD))
    }.mapNotNull { constructor ->
        val parameters = constructor?.valueParameters.orEmpty()
        if (
            parameters.any { it.typeReference == null || it.name == null }
            || constructor?.valueParameterList?.refersToInvisibleTypes() == true
        ) null else buildString {
            +"fun "
            +typeParameters.copiedTypeParameters(renamed)
            +"`$name`("
            +parameters.copiedParameters(renamed)
            +"): Nothing"
            typeConstraintList?.let {
                +" where "
                +it.singleLineText(renamed)
            }
            +" = kotlin.TODO()"
        }
    }.let { copies ->
        val typeParameterNames = if (typeParameters.isEmpty()) "" else typeParameters.joinToString(prefix = "<", postfix = ">") { it.nameIdentifier!!.text }
        if (copies.none { self in it }) copies else listOf("class $self$typeParameterNames") + copies
    }
}

/** The prefix of the name of the class replacing a local class in the copies of its constructors, see [constructorCopies]. */
private const val LATER_DECLARED_CLASS_PREFIX = "_LaterDeclaredClass_"

/**
 * The declaration of the class replacing this local class, named [name], in the copies preceding its declaration, see [ScriptSource.laterDeclaredDependencyCandidates],
 * of the same type parameters and supertypes, of the names of their types [renamed], like `abstract class P_ : Runnable` of `class P(val v: Int) : Runnable`,
 * so the copies apply to the same arguments as the declarations referring to the local class, as no value preceding it is of the local class.
 * The supertypes are not initialized, an error ignored there, as the class is never instantiated.
 */
private fun KtClassOrObject.placeholder(
    name: String,
    renamed: Map<PsiElement, String>
): String = buildString {
    +"abstract class $name"
    typeParameterList?.let { +it.singleLineText(renamed) }
    superTypeListEntries.mapNotNull { it.typeReference }.takeIf { it.isNotEmpty() }?.let { types ->
        +" : "
        +types.joinToString { it.singleLineText(renamed) }
    }
    typeConstraintList?.let {
        +" where "
        +it.singleLineText(renamed)
    }
}

/** The prefix of the name of the class replacing a local class in the copies preceding its declaration, see [placeholder]. */
private const val PLACEHOLDER_CLASS_PREFIX = "_InvisibleClass_"

/** These elements, and the ones they refer to, transitively. */
private inline fun <T> List<T>.closure(
    references: (T) -> List<T>
): Set<T> {
    val closure = LinkedHashSet<T>()
    var elements = distinct()
    while (elements.isNotEmpty()) {
        closure += elements
        elements = elements.flatMap { references(it) }.distinct().filter { it !in closure }
    }
    return closure
}

/**
 * The overriding declaration of the member of `Any` of the same signature as this function, like `override fun toString(): kotlin.String`,
 * which a declaration of this signature in a class would hide, or `null`, if it has another signature, like `fun equals(other: String)`,
 * or declares another return type, like `fun toString(): Int`, the calls of which are of this type.
 */
private fun KtNamedFunction.anyMemberOverride(): String? = if (receiverTypeReference != null || typeParameters.isNotEmpty()) null else {
    val parameterTypes = valueParameters.map { it.typeReference?.singleLineText()?.trim()?.removePrefix("kotlin.") }
    // `Unit` of a block body, while an inferred one is not known, assumed to be the one of the member
    val returnType = typeReference?.singleLineText()?.trim()?.removePrefix("kotlin.") ?: "Unit".takeIf { bodyBlockExpression != null }
    fun returning(type: String) = returnType == null || returnType == type
    when {
        name == "toString" && parameterTypes.isEmpty() && returning("String") -> "override fun toString(): kotlin.String = kotlin.TODO()"
        name == "hashCode" && parameterTypes.isEmpty() && returning("Int") -> "override fun hashCode(): kotlin.Int = kotlin.TODO()"
        name == "equals" && parameterTypes == listOf("Any?") && returning("Boolean") ->
            "override fun equals(other: kotlin.Any?): kotlin.Boolean = kotlin.TODO()"
        else -> null
    }
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
    // like `val log get() = { m: String -> ... }` of a moved property
    return when (val initializer = initializer ?: getter?.bodyExpression) {
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

/** The text without the comments and the line breaks, as each declaration of the preamble is a single line, with these leaves [renamed]. */
private fun PsiElement.singleLineText(
    renamed: Map<PsiElement, String> = emptyMap()
): String = buildString {
    collectDescendantsOfType<PsiElement> { it.firstChild == null }.forEach { leaf ->
        +(renamed[leaf] ?: if (leaf is PsiComment || leaf is PsiWhiteSpace) " " else leaf.text)
    }
}

/**
 * Whether the expression is of the type of a literal, like `10L * 2` or `"a$b"`, which cannot be called,
 * as the operators of the literals are their members, unless an `invoke` operator extension of their type is declared,
 * which the script is checked for.
 */
private fun KtExpression?.isLiteral(): Boolean = when (this) {
    is KtConstantExpression, is KtStringTemplateExpression -> true
    is KtParenthesizedExpression -> expression.isLiteral()
    is KtPrefixExpression -> baseExpression.isLiteral()
    is KtBinaryExpression -> operationToken in literalOperations && left.isLiteral() && right.isLiteral()
    else -> false
}

private val literalOperations = setOf(
    KtTokens.PLUS, KtTokens.MINUS, KtTokens.MUL, KtTokens.DIV, KtTokens.PERC, KtTokens.ANDAND, KtTokens.OROR,
    KtTokens.LT, KtTokens.GT, KtTokens.LTEQ, KtTokens.GTEQ, KtTokens.EQEQ, KtTokens.EXCLEQ
)

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
    /** The start of the whole callable reference, like `String::foo`, or the [offset], if it is not one. */
    val expressionOffset: Int,
    /**
     * Whether it is within a declaration, which can access the members of the script, when moved out of the script body,
     * unlike e.g. an object, an enum class, an interface, or a nested class.
     */
    val canAccessScriptMembers: Boolean,
    /** Whether it is an operator, or a convention, like `+`, referring to the operator functions only, like `plus`. */
    val isOperator: Boolean = false,
    /** Whether it is within a lambda, which might have a receiver, so its resolution is not known without compiling. */
    val isInLambda: Boolean = false,
    /**
     * Whether it is within a lambda, a class, an object, or an extension function,
     * so it might resolve to a member of their receivers, like `append` in `buildString { append("x") }`.
     */
    val mightResolveOnReceiver: Boolean = false,
    /**
     * The ranges of the references of its [name] within it, unless the compiler reports it at one of them,
     * like `getValue` of `by lazy { "a".getValue(null, p) }`, the warnings at which are of them,
     * even if not candidates themselves, like when bound by name to a member, which does not apply.
     */
    val inner: List<IntRange> = emptyList(),
    /**
     * The range of the callee of the delegate expression of the delegate convention, where the compiler reports it,
     * like `getValue` of `by getValue(cfg)`, which might be a reference of its name, calling another function.
     */
    val delegateCallee: IntRange? = null,
    /** The name of the value called by it, like `n` of `n(1)`, if it is the `invoke` operator called on it, see [operatorReferences]. */
    val invokedValue: String? = null
) {

    /** Whether it might refer to one of the local declarations of the script body of these [names]. */
    fun refersToLocal(
        names: Set<String>
    ): Boolean = !isQualified && !isBound && name in names

    /**
     * Whether it might call the operator function of its [name], unless its [invokedValue] is named unlike any of the [values],
     * like a function, like `listOf` of `listOf(1)`, so the local `invoke` operators are not moved, nor confirmed, needlessly.
     */
    fun mightCallOperator(
        values: Set<String>
    ): Boolean = invokedValue == null || invokedValue in values

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
        expressionOffset = callableReference?.takeIf { it.callableReference == expression }?.startOffset ?: expression.startOffset,
        canAccessScriptMembers = canAccessScriptMembers(expression, scope),
        isInLambda = isInLambda(expression, scope),
        mightResolveOnReceiver = mightResolveOnReceiver(expression, scope)
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
 * like `plus` of `a + b`, `get` of `a[i]`, `iterator` of `for (x in a)`, `component1` of `val (a, b) = p`, or `invoke` of `f()()`, or of `n(1)`, calling a value,
 * which are qualified, by the operands, so they might refer to the extension functions only,
 * at the positions where the compiler reports the calls, like the whole array access expression,
 * which might contain the references of the operands, and other operators, like `set` and `get`,
 * so they are confirmed by the names of the functions as well, see [HiddenLocalFunctionReference.isOperator].
 */
private fun PsiElement.operatorReferences(
    scope: PsiElement
): List<Reference> = buildList {
    fun add(
        names: List<String>,
        element: PsiElement,
        offset: Int = element.startOffset,
        length: Int = element.textLength,
        delegateCallee: IntRange? = null,
        invokedValue: String? = null,
        inner: (String) -> List<IntRange> = { emptyList() }
    ) {
        val canAccessScriptMembers = canAccessScriptMembers(element, scope)
        val mightResolveOnReceiver = mightResolveOnReceiver(element, scope)
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
                    expressionOffset = offset,
                    canAccessScriptMembers = canAccessScriptMembers,
                    isOperator = true,
                    mightResolveOnReceiver = mightResolveOnReceiver,
                    inner = inner(name),
                    delegateCallee = delegateCallee,
                    invokedValue = invokedValue
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
            is KtCallExpression -> when (val callee = element.calleeExpression) {
                null -> {}
                // the call of a value by name, like `print("")` of `val print = 3`, which might call an extension, unlike a function
                is KtSimpleNameExpression -> add(listOf("invoke"), element, invokedValue = callee.getReferencedName())
                else -> {
                    val arguments = element.valueArgumentList ?: element.lambdaArguments.firstOrNull() ?: element
                    add(listOf("invoke"), element, arguments.startOffset, arguments.textLength)
                }
            }
            // the compiler reports the calls on the delegate expression, or at its callee, like `let` of `by Cfg().let { it }`,
            // which might be a reference of the same name, like in `by map.getValue("a")`
            is KtPropertyDelegate -> {
                val conventions = listOf("getValue", "setValue", "provideDelegate")
                val callee = element.expression?.reportedCallee()
                val names = element.collectDescendantsOfType<KtSimpleNameExpression> {
                    it !== callee && it.getReferencedName() in conventions
                }.groupBy { it.getReferencedName() }
                add(conventions, element, delegateCallee = callee?.let { it.startOffset until it.endOffset }) { name ->
                    names[name].orEmpty().map { it.startOffset until it.endOffset }
                }
            }
            else -> {}
        }
    }
}

/** The callee reported as the position of the call of this expression, like `let` of `Cfg().let { it }`, or `+` of `a + b`. */
private fun KtExpression.reportedCallee(): KtExpression? = when (val expression = KtPsiUtil.safeDeparenthesize(this)) {
    is KtQualifiedExpression -> expression.selectorExpression?.reportedCallee()
    is KtCallExpression -> expression.calleeExpression?.reportedCallee()
    is KtOperationExpression -> expression.operationReference
    else -> expression
}

/**
 * Whether the [expression] is within a lambda, which might have a receiver, within the [scope], exclusive,
 * or within an anonymous function with a receiver, like `fun Job.() = isActive`.
 */
private fun isInLambda(
    expression: PsiElement,
    scope: PsiElement
): Boolean = expression.parents.takeWhile { it !== scope }.any { it.isLambda() }

/** Whether it is a lambda, or an anonymous function with a receiver, like `fun Job.() = isActive`, see [isInLambda]. */
private fun PsiElement.isLambda(): Boolean =
    this is KtLambdaExpression || (this is KtNamedFunction && isAnonymous && receiverTypeReference != null)

/** The call taking this lambda as its argument, also as a trailing lambda, or a labeled one, like `run(label@{ })`, with the argument. */
private fun KtLambdaExpression.argumentOf(): Pair<KtCallExpression, KtValueArgument>? {
    var argument: PsiElement = this
    while (argument.parent.let { it is KtLabeledExpression || it is KtParenthesizedExpression || it is KtAnnotatedExpression }) argument = argument.parent
    val valueArgument = argument.parent as? KtValueArgument ?: return null
    // also a trailing lambda, which is a value argument as well
    val call = valueArgument.parent.let { (it as? KtValueArgumentList)?.parent ?: it } as? KtCallExpression ?: return null
    return call to valueArgument
}

/** See [Reference.mightResolveOnReceiver]. */
private fun mightResolveOnReceiver(
    expression: PsiElement,
    scope: PsiElement
): Boolean = expression.parents.takeWhile { it !== scope }.any {
    it is KtLambdaExpression || it is KtClassOrObject || (it is KtNamedFunction && it.receiverTypeReference != null)
}

/**
 * The scope functions, the lambdas of which have the receivers of their calls as their receivers, like `run` of `run { }`, by their names,
 * and the declarations of their copies, the members of the coroutine scope of the script body, see [ScriptSource.laterDeclaredClassCandidates].
 */
private val scopeFunctions = mapOf(
    // of the type parameters of the originals, like `run<Any> { }`, which is the one without a receiver,
    // bound to the coroutine scope, unlike e.g. the lambda of `run<Any, Any> { }`, which is of a receiver of `Any`
    "run" to "inline fun <T : $BODY_SCOPE_CLASS, R> T.`run`(block: T.() -> R): R = kotlin.TODO()",
    "apply" to "inline fun <T : $BODY_SCOPE_CLASS> T.`apply`(block: T.() -> kotlin.Unit): T = kotlin.TODO()",
    "runCatching" to "inline fun <T : $BODY_SCOPE_CLASS, R> T.`runCatching`(block: T.() -> R): kotlin.Result<R> = kotlin.TODO()",
    "with" to "inline fun <T : $BODY_SCOPE_CLASS, R> `with`(receiver: T, block: T.() -> R): R = kotlin.TODO()"
)

/**
 * The ranges of the callees of the calls of the [scopeFunctions], the lambda arguments of which contain the [element],
 * within the [scope], exclusive, like `run` of `run { Foo(2) }`.
 */
private fun scopeCalls(
    element: PsiElement,
    scope: PsiElement
): List<IntRange> = element.parents.takeWhile { it !== scope }.filterIsInstance<KtLambdaExpression>().mapNotNull { lambda ->
    (lambda.argumentOf()?.first?.calleeExpression as? KtSimpleNameExpression)
        ?.takeIf { it.getReferencedName() in scopeFunctions }?.let { it.startOffset until it.endOffset }
}.toList()

/** The functions of `kotlinx.coroutines`, the block of which has a coroutine scope receiver, nested in the coroutine scope of the script body. */
private val coroutineScopeBuilders = setOf("coroutineScope", "supervisorScope", "withContext", "withTimeout", "withTimeoutOrNull", "launch", "async")

/**
 * Whether the [element] is within the block of a [coroutineScopeBuilders], like `isActive` of `coroutineScope { run { isActive } }`,
 * the outermost lambda within the [scope], exclusive, which is called by its name, unless one of the [scriptNames],
 * not within a receiver of its own, so it is the one of `kotlinx.coroutines`,
 * the coroutine scope of which has the same members, and extensions, as the coroutine scope of the script body, while taking precedence over them,
 * also over the members of the script in a regular script.
 */
private fun isInNestedCoroutineScope(
    element: PsiElement,
    scope: PsiElement,
    scriptNames: Set<String>
): Boolean {
    val lambda = element.parents.takeWhile { it !== scope }.lastOrNull { it.isLambda() } as? KtLambdaExpression ?: return false
    val (call, argument) = lambda.argumentOf() ?: return false
    val callee = call.calleeExpression as? KtSimpleNameExpression ?: return false
    // the block, which is the last argument
    return call.valueArguments.lastOrNull() === argument
            && callee.getReferencedName().let { it in coroutineScopeBuilders && it !in scriptNames }
            && call.parent.let { it !is KtQualifiedExpression || it.selectorExpression !== call }
            && !mightResolveOnReceiver(call, scope)
}

/** Whether the return type of the function is inferred from its expression body, unlike a declared one, or `Unit` of a block body. */
private fun KtNamedFunction.isOfInferredType(): Boolean = typeReference == null && bodyBlockExpression == null && bodyExpression != null

/**
 * Whether the [element] is within a declaration of a declared type, like `fun h(): String = show(1)`, which would be a member in the script,
 * rather than the local declaration of another one, within the [scope], exclusive, see [ShadowedFunctionReference.isInInferredType].
 * The `init` blocks, the constructors, and the default values of the parameters of the primary constructors are resolved like them.
 */
private fun isInDeclaredType(
    element: PsiElement,
    scope: PsiElement
): Boolean {
    /** Whether it would be a member in the script, of the script class, or of a class which would be its member. */
    fun KtDeclaration.isMember(): Boolean = parent === scope || (parent as? KtClassBody)?.parent.let { owner ->
        owner is KtClassOrObject && !(owner is KtObjectDeclaration && owner.isObjectLiteral()) && owner.isMember()
    }
    val declaration = element.parents.takeWhile { it !== scope }.firstOrNull {
        when (it) {
            // like the top-level statements, which are the initializers of the script
            is KtScriptInitializer -> false
            is KtNamedFunction, is KtProperty, is KtAnonymousInitializer, is KtSecondaryConstructor -> (it as KtDeclaration).isMember()
            is KtParameter -> (it.parent?.parent as? KtPrimaryConstructor)?.getContainingClassOrObject()?.isMember() == true
            else -> false
        }
    }
    return when (declaration) {
        is KtNamedFunction -> !declaration.isOfInferredType()
        is KtProperty -> declaration.typeReference != null
        else -> declaration != null
    }
}

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
 * A statement of the script body staying in the script block, see [ScriptStructure.hoisting],
 * from its [start], without the comments preceding it, like of a declaration, to its [end].
 */
private class BlockStatement(
    private val psi: KtExpression,
    val start: Int,
    val end: Int,
    /** The name of the local function it declares, if any. */
    val function: String?,
    /** Whether it declares a local function, which might be hoisted, see [ScriptStructure.hoisting]. */
    val isHoistable: Boolean,
    /**
     * The index of the first statement of the script block sharing its line, preceding which the hoisted functions are inserted,
     * so the inserted lines do not split another statement, like the copies, see [ScriptStructure.blockCopies].
     */
    val insertion: Int,
    /** The offset where the functions hoisted preceding it are inserted, like the copies, preceding its line. */
    val insertionOffset: Int
) {
    /** The names it refers to, or declares, by their offsets, recognized only when hoisting, see [ScriptStructure.hoisting]. */
    val identifiers: Map<Int, String> by lazy {
        psi.collectDescendantsOfType<LeafPsiElement> { it.elementType == KtTokens.IDENTIFIER }
            .associate { it.startOffset to KtPsiUtil.unquoteIdentifier(it.text) }
    }
}

/**
 * The local functions declared preceding the statements calling them, see [ScriptStructure.hoisting]:
 * the ranges of these functions, each following the ones it calls, by the offsets preceding the lines of which they are declared,
 * the offsets of the [references] to them, which were unresolved, the [region] of the script, which the hoisting changes,
 * and whether the script ends with a hoisted function, so it returns `Unit`, see [ScriptSource.returnsUnit].
 */
private class Hoisting(
    val functions: Map<Int, List<IntRange>>,
    val references: Set<Int>,
    val region: IntRange,
    val endsWithHoistedFunction: Boolean
)

/**
 * The top-level structure of the script, recognized with the Kotlin parser:
 * the header of file annotations, package and imports, and the declarations
 * which cannot be local to the script block, see [ScriptSource].
 */
/**
 * The deprecated copy of the local [declaration] of the [name], see [ScriptSource.hiddenLocalFunctionCandidates],
 * whether it [isOperator], and the [offset] of the name of the local function, unlike of a property.
 */
private class HiddenLocalCopy(
    val name: String,
    val declaration: String,
    val isOperator: Boolean,
    val offset: Int?
)

/**
 * The [declaration] of a member of the script, copying a local declaration in a confirming compilation, see [ScriptSource.confirming],
 * following the moved declarations preceding the [offset] of the name of its original, like the type aliases, which are not visible before their declarations,
 * as in a regular script, or all of them, if [Int.MAX_VALUE], like the copies of the properties,
 * and the name of the property, whose type it refers to, see [ScriptSource.propertyTypes].
 */
private class MemberCopy(
    val offset: Int,
    val declaration: String,
    val propertyTypeName: String? = null
)

private class ScriptStructure(
    private val script: String,
    dependencyNames: Set<String>,
    /** The starts of the lines of the original script, unlike of the [script], where `\r\n` ends two lines. */
    lineStarts: List<Int>
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

    /**
     * The names of the [localFunctions], which the references of the moved declarations cannot be confirmed to refer to,
     * see [ScriptSource.hiddenLocalFunctionCandidates], as they cannot be copied, like the ones referring to the local classes, or to the type aliases declared after them,
     * or as they are referred to as functions, like `::log`, which would be resolved to the dependency of the same name instead,
     * or as their names are bound within the moved declarations, which are not candidates.
     * Only the ones named like the dependencies are checked for the copies.
     */
    val unconfirmedLocalFunctions: Set<String>

    /** See [ScriptSource.hiddenLocalFunctionCandidates], with the offsets of the first local declarations, and whether they are functions. */
    val hiddenLocalFunctionCandidates: List<Triple<Reference, Int, Boolean>>

    /** The declarations of each name of the [hiddenLocalFunctionCandidates], which their deprecated copies are declared of, see [hiddenLocalFunctionCopies]. */
    private val hiddenLocalDeclarations: List<HiddenLocalDeclarations>

    /** The [hiddenLocalFunctionCandidates] calling the local properties of the types known only to the compiler, see [ScriptSource.untypedPropertyCalls]. */
    val untypedPropertyCalls: Set<Reference>

    /** The names of the local properties the [untypedPropertyCalls] call, see [ScriptSource.untypedProperties]. */
    val untypedProperties: Set<String>

    /** The ends of the [untypedProperties], which the statements probing, or checking, their types follow, by their names, see [propertyTypeStatements]. */
    private val untypedPropertyEnds: Map<String, Int>

    /** The ranges of the `@Suppress` annotations of the script, which might suppress the deprecations, see [ScriptSource.confirming]. */
    val suppressions: List<IntRange>

    /** See [ScriptSource.shadowedFunctionCandidates]. */
    val shadowedFunctionCandidates: List<ShadowedCandidate>

    /** The names of the moved functions overloaded by the local ones, which the [shadowedFunctionCandidates] call, see [ScriptSource.overloadingFunctionCopies]. */
    val overloadedFunctions: Set<String>

    /** The copies of the local functions overloading the [overloadedFunctions], by the offsets of their names, see [ScriptSource.overloadingFunctionCopies]. */
    val overloadingCopies: List<Pair<Int, String>>

    /** The names of the local functions overloading the [overloadedFunctions], renamed with these letters, see [ScriptSource.overloadingFunctionCopies]. */
    val renamedRanges: List<Pair<IntRange, Char>>

    /** The offsets of the names of the local functions, which the [shadowedFunctionCandidates] might call, marked by the confirming compilation, by their starts, see [SHADOWING_FUNCTION]. */
    val shadowingFunctions: Map<Int, Int>

    /** See [ScriptSource.coroutineScopeCandidates]. */
    val coroutineScopeCandidates: List<CoroutineScopeCandidate>

    /** The copies of the moved declarations, the members of the coroutine scope of the script body, see [ScriptSource.coroutineScopeCopies]. */
    val coroutineScopeMembers: List<String>

    /** See [ScriptSource.packageName]. */
    val packageName: String

    /** The names and the ranges of the [ScriptSource.scriptMemberCallableReferences]. */
    val scriptMemberCallableReferences: List<Pair<String, IntRange>>

    /** The copies of the local functions, see [ScriptSource.laterDeclaredDependencyCandidates], with their messages, by the offsets of the statements they precede. */
    private val laterDeclaredFunctionCopies: Map<Int, List<Pair<String, String>>>

    /** The copies of the moved functions, see [ScriptSource.shadowedFunctionCopies], with their messages, by the offsets of the statements they precede. */
    private val shadowedFunctionCopies: Map<Int, List<Pair<String, String>>>

    /** The copies of the constructors of the local classes, see [ScriptSource.laterDeclaredClassCandidates], with their messages, by the offsets of the statements they precede. */
    private val constructorCopies: Map<Int, List<Pair<String, String>>>

    /** See [ScriptSource.laterDeclaredDependencyCandidates]. */
    val laterDeclaredDependencyCandidates: List<LaterDeclaredCandidate>

    /** See [ScriptSource.laterDeclaredClassCandidates]. */
    val laterDeclaredClassCandidates: List<ClassCandidate>

    /** See [ScriptSource.laterDeclaredFunctionCandidates]. */
    val laterDeclaredFunctionCandidates: List<LaterFunctionCandidate>

    /** The copies of the local functions, see [ScriptSource.laterDeclaredFunctionCandidates], with their messages, by the offsets of the statements they precede. */
    private val laterDeclaredLocalFunctionCopies: Map<Int, List<Pair<String, String>>>

    /**
     * The copies of the local functions, see [ScriptSource.laterDeclaredFunctionCandidates], the members of the script, as the functions of a regular script,
     * which the members of the receivers take precedence over, with their messages, by the offsets of the names of their originals,
     * unless the [hiddenLocalFunctionCopies] declare them already.
     */
    val laterDeclaredFunctionMemberCopies: List<Triple<Int, String, String>>

    /** The copies of the scope functions of the script instance, see [ScriptSource.laterDeclaredClassCandidates]. */
    val scopeFunctionCopies: List<String>

    /** The names of the [ScriptSource.conflictingDependencyDeclarations], and their ranges. */
    val conflictingDependencyDeclarations: List<Pair<String, IntRange>>

    /** The modifiers which are not applicable to local declarations. */
    val blankedRanges = mutableListOf<IntRange>()

    /** The modifiers of the moved declarations, which are not applicable to the members of the script class, like `const`. */
    val movedBlankedRanges = mutableListOf<IntRange>()

    /** The statements of the script block, see [hoisting]. */
    private val blockStatements: List<BlockStatement>

    /** The offsets of the declarations of the script, by the names they declare, see [hoisting]. */
    private val declarationStarts: Map<String, List<Int>>

    /** The offsets of the operator functions of the script, see [hoisting]. */
    private val operatorStarts: List<Int>

    /**
     * The names of the local declarations of the script body, which each local function refers to, by its name,
     * unless it declares a value or a function of the same name, see [ScriptSource.callsBack].
     */
    val localReferences: Map<String, Set<String>>

    /** The names of the local variables of the script body, the `var` ones, see [hoisting]. */
    private val localVariables: Set<String>

    /** Whether the last statement of the script stays in the script block, see [Hoisting.endsWithHoistedFunction]. */
    private val endsWithBlockStatement: Boolean

    private val declaredNames = DeclaredNames()

    init {
        val file = psiFactory.createFile("script.kts", script)
        packageName = file.packageFqName.asString()
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
        // the names of the values, which the calls by name might invoke, see Reference.mightCallOperator
        val valueNames = file.collectDescendantsOfType<KtNamedDeclaration> {
            it is KtProperty || it is KtParameter || it is KtDestructuringDeclarationEntry
        }.mapNotNullTo(HashSet()) { it.name } + dependencyNames + listOfNotNull(
            // the implicit parameter of a lambda, like `it("x")` of `listOf(3).map { it("x") }`
            "it".takeIf { file.anyDescendantOfType<KtFunctionLiteral> { !it.hasParameterSpecification() } }
        )
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
        // referred to by the statements, which stay in the script body, unlike the declarations
        val statementNames = statements.zip(statementReferences).filter { (statement) -> statement !in declarationsByPsi }
            .flatMap { it.second }
            .filter { it.mightCallOperator(valueNames) }
            .mapTo(HashSet()) { it.name }
        val selected = selectMovedDeclarations(declarations, bodyReferences, statementNames, propertyNameSet, valueNames)
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
        val (movedExtensions, movedMembers) = moved
            .filter { it.kind == DeclarationKind.FUNCTION || it.kind == DeclarationKind.PROPERTY }
            .partition { it.isExtension || (it.psi as? KtProperty)?.receiverTypeReference != null }
        val scriptMemberNames = movedMembers.flatMapTo(HashSet(dependencyNames)) { listOfNotNull(it.name) + it.destructuredNames }
        val scriptExtensionNames = movedExtensions.mapNotNullTo(HashSet()) { it.name }
        scriptMemberCallableReferences = moved.filter { it.kind == DeclarationKind.CLASS }.flatMap { declaration ->
            declaration.references.filter {
                it.isCallableReference && it.canAccessScriptMembers && if (it.isQualified) {
                    // like `String::up`, where the members of the class do not apply
                    it.name in scriptExtensionNames
                } else {
                    !it.isBound && it.name in scriptMemberNames
                }
            }
        }.map { it.name to (it.expressionOffset until it.offset + it.length) }
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
        fun ParsedDeclaration.signature(
            keepsInferredType: Boolean = true
        ) = (psi as KtNamedFunction).signature(keepsInferredType)
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
        // like `operator fun Int.invoke(x: Any?)`, which the script might import, also by a star import, or declare
        val literalsMightBeCalled = file.importDirectives.any {
            it.isAllUnder || it.aliasName == "invoke" || it.importedFqName?.shortName()?.asString() == "invoke"
        } || file.collectDescendantsOfType<KtNamedFunction> { it.name == "invoke" }.isNotEmpty()
        // the other local properties, which might be called, if the compiler infers their types callable, like `val print = printer()`,
        // unlike the literals, like `val delay = 10L * 2`, by their ends, where their types are probed on the following lines,
        // see ScriptSource.propertyTypeProbes, except the ones followed by another statement on the same line
        untypedPropertyEnds = buildMap {
            localPropertiesByName.filterKeys { it !in callableProperties }.forEach { (name, properties) ->
                val property = properties.first().psi
                if (!literalsMightBeCalled && (property as? KtProperty)?.initializer.isLiteral()) return@forEach
                var element = property.nextSibling
                while (element != null && !(element is PsiWhiteSpace && '\n' in element.text)) {
                    if (element !is PsiWhiteSpace && element.node.elementType != KtTokens.SEMICOLON && element.node.elementType != KtTokens.EOL_COMMENT) {
                        return@forEach
                    }
                    element = element.nextSibling
                }
                put(name, property.endOffset)
            }
        }
        val untypedCalls = mutableSetOf<Reference>()
        hiddenLocalFunctionCandidates = moved.flatMap { declaration ->
            declaration.references.filter { !it.isBound }.mapNotNull { reference ->
                val name = reference.name
                when {
                    reference.isCall && reference.refersToLocal(localFunctions) -> localFunctionsByName.getValue(name).first() to true
                    // like `listOf(1, 2).sum()`
                    reference.isCall && reference.mightCallOperator(valueNames) && localExtensions[name]?.any { !reference.isOperator || it.isOperator } == true ->
                        localExtensions.getValue(name).first { !reference.isOperator || it.isOperator } to true
                    // also with a receiver, like `" a ".trim()` of `val trim: String.() -> String`
                    reference.isCall && name in callableProperties -> callableProperties.getValue(name).first().first to false
                    // like `print("shape")` of `val print = printer()`, confirmed once its type is known, see ScriptSource.propertyTypeProbes
                    reference.isCall && !reference.isCallableReference && !reference.isOperator && name in untypedPropertyEnds -> {
                        untypedCalls += reference
                        localPropertiesByName.getValue(name).first() to false
                    }
                    // like `E`, resolved to `kotlin.math.E` instead
                    !reference.isCall && reference.refersToLocal(localProperties) && name in localPropertiesByName ->
                        localPropertiesByName.getValue(name).first() to false
                    else -> null
                }?.let { (local, isFunction) -> Triple(reference, local.line(), isFunction) }
            }
        }
        /**
         * Whether the function cannot be copied, as it refers to the local classes, unlike to the type aliases,
         * as its copy follows the moved declarations preceding it, so it sees the same type aliases as the function in a regular script,
         * and does not apply either, if it refers to the ones declared after it, see [ScriptSource.confirming].
         */
        fun ParsedDeclaration.isUncopied() = signature() == null || refersToTypes(localClassNames)
        unconfirmedLocalFunctions = localFunctionsByName.filter { (name, functions) ->
            name in dependencyNames && functions.any { it.isUncopied() }
        }.keys + hiddenLocalFunctionCandidates.filter { (reference) ->
            reference.isCallableReference && reference.refersToLocal(localFunctions)
        }.map { it.first.name } + moved.flatMap { declaration ->
            // like `log("x")` of `fun f(log: Int)`, calling the local function in the script, if the inner declaration does not apply
            declaration.references.filter { it.isBound && it.isCall && !it.isQualified && it.name in localFunctions && it.name in dependencyNames }.map { it.name }
        }
        untypedPropertyCalls = untypedCalls
        untypedProperties = untypedCalls.mapTo(LinkedHashSet()) { it.name }
        hiddenLocalDeclarations = hiddenLocalFunctionCandidates.mapTo(LinkedHashSet()) { it.first.name }.map { name ->
            HiddenLocalDeclarations(
                name,
                // the moved declarations cannot refer to the values of the local classes
                functions = (localFunctionsByName[name].orEmpty() + localExtensions[name].orEmpty())
                    .filterNot { it.refersToTypes(localClassNames) }
                    .mapNotNull { function -> function.signature()?.let { FunctionCopy(it, function.isOperator, function.line()) } },
                propertyTypes = callableProperties[name]?.map { it.second },
                // referred to as values, like `E`, unlike the properties of unknown types, which might not be callable
                isReferredToAsValue = name in localPropertiesByName && hiddenLocalFunctionCandidates.any { (reference) -> reference.name == name && reference !in untypedCalls }
            )
        }
        // also the aliases, like `typealias Q = Suppress` or `import kotlin.Suppress as Q`
        val suppressNames = setOf("Suppress") +
                file.collectDescendantsOfType<KtTypeAlias> {
                    it.getTypeReference()?.text?.removePrefix("kotlin.") == "Suppress"
                }.mapNotNull { it.name } +
                file.importDirectives.filter { it.importedFqName?.asString() == "kotlin.Suppress" }.mapNotNull { it.aliasName }
        fun KtAnnotationEntry.isSuppress() = shortName?.asString() in suppressNames
        // also of the errors of the probes of the types, see ScriptSource.propertyTypeProbes
        suppressions = file.collectDescendantsOfType<KtAnnotationEntry> { it.isSuppress() }.map { entry ->
            // like `@[Suppress("X")]`, which would be invalid without the entries
            val annotation = entry.parent as? KtAnnotation
            if (annotation != null && annotation.entries.all { it.isSuppress() }) annotation else entry
        }.distinct().map { it.startOffset until it.endOffset }
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
        // the local functions overloading the moved functions, by the indices of their statements
        val overloadingFunctions = buildMap<String, MutableList<Pair<Int, ParsedDeclaration>>> {
            statements.forEachIndexed { index, statement ->
                declarationsByPsi[statement]?.takeIf { it in local && it.name in movedFunctionsByName }?.let { function ->
                    getOrPut(function.name!!) { mutableListOf() } += index to function
                }
            }
        }
        // the names of the overloads of both inferred and declared types, which a regular script resolves differently
        // within the declarations of inferred types, see ScriptSource.overloadingFunctionCopies
        val mixedOverloads = overloadingFunctions.filter { (name, functions) ->
            (functions.map { it.second } + movedFunctionsByName.getValue(name)).map { (it.psi as KtNamedFunction).isOfInferredType() }.distinct().size > 1
        }.keys
        // unless they cannot be copied, see ScriptSource.overloadingFunctionCopies
        val uncopiedOverloads = overloadingFunctions.filter { (_, functions) ->
            functions.any { (_, function) -> function.isUncopied() }
        }.keys
        /** The candidates among the references of each statement of the script body, unless moved, by its index. */
        fun <T : Any> bodyCandidates(
            candidate: (index: Int, reference: Reference) -> T?
        ) = statements.indices.associateWith { index ->
            if (declarationsByPsi[statements[index]] in moved) emptyList() else {
                statementReferences[index].mapNotNull { candidate(index, it) }
            }
        }
        val shadowedFunctionCandidatesByStatement = bodyCandidates { index, reference ->
            val function = movedFunctionsByName[reference.name]?.first()?.line() ?: return@bodyCandidates null
            shadowingProperties[reference.name]?.takeIf { (propertyIndex) ->
                propertyIndex < index && reference.isCall && reference.refersToLocal(localProperties)
            }?.let { (_, property) ->
                ShadowedCandidate(reference, listOf(property.line()), function, isFunction = false)
            } ?: overloadingFunctions[reference.name]
                // also within the function itself, which is visible there
                ?.filter { (functionIndex) -> functionIndex <= index }
                ?.takeIf { it.isNotEmpty() && reference.isCall && reference.refersToLocal(localFunctions) }
                ?.let { visible ->
                    val isInInferredType = reference.name in mixedOverloads
                            && file.findElementAt(reference.offset)?.let { isInDeclaredType(it, statements[index].parent) } != true
                    if (isInInferredType && reference.name in uncopiedOverloads) null else {
                        ShadowedCandidate(reference, visible.map { it.second.line() }, function, isFunction = true, isInInferredType)
                    }
                }
        }
        shadowedFunctionCandidates = shadowedFunctionCandidatesByStatement.values.flatten()
        val overloadedNames = shadowedFunctionCandidates.filter { it.isInInferredType }.mapTo(HashSet()) { it.reference.name }
        overloadedFunctions = overloadedNames
        val overloading = overloadingFunctions.filterKeys { it in overloadedNames }
        overloadingCopies = overloading.values.flatten().mapNotNull { (_, function) -> function.signature()?.let { function.line() to it } }
        // a distinct letter for each name, so the renamed functions of different names do not overload each other
        renamedRanges = overloading.values.flatMapIndexed { index, functions ->
            functions.mapNotNull { (_, function) ->
                (function.psi as KtNamedFunction).nameIdentifier?.let { identifier ->
                    // within the backticks, if quoted
                    val quoted = identifier.text.length > 1 && identifier.text.startsWith('`')
                    val range = if (quoted) identifier.startOffset + 1 until identifier.endOffset - 1 else identifier.startOffset until identifier.endOffset
                    range to RENAMED_CHARS + index
                }
            }
        }
        // the local overloads, one of which the candidates call, starting their lines, so that they can be marked on the lines preceding them
        val ambiguousNames = shadowedFunctionCandidates.filter { it.isFunction && it.locals.size > 1 }.mapTo(HashSet()) { it.reference.name }
        shadowingFunctions = overloadingFunctions.filterKeys { it in ambiguousNames }.values.flatten().map { it.second }.filter { function ->
            script.substring(script.lastIndexOf('\n', function.start - 1) + 1, function.start).isBlank()
        }.associate { it.start to it.line() }
        // unlike the extension properties, which do not apply without their receivers
        val coroutineScopeDeclarations = moved.filter {
            (it.kind == DeclarationKind.FUNCTION || (it.kind == DeclarationKind.PROPERTY && (it.psi as? KtProperty)?.receiverTypeReference == null))
                    && it.name != null && (it.name in coroutineScopeProperties || it.name in coroutineScopeFunctions)
        }.groupBy { it.name!! }
        // the names, which the calls by these names might refer to: of the declarations of the script, of the dependencies,
        // and the imported ones, other than the builders of kotlinx.coroutines themselves, like `launch` of `import kotlin.run as launch`,
        // or all the builders, if a star import of another package might import them
        val scriptNames by lazy {
            val imports = file.importDirectives
            if (imports.any { it.isAllUnder && it.importedFqName?.asString() != "kotlinx.coroutines" }) coroutineScopeBuilders else {
                file.collectDescendantsOfType<KtNamedDeclaration>().mapNotNullTo(HashSet()) { it.name } + dependencyNames + imports.filter {
                    !it.isAllUnder && (it.aliasName != null || it.importedFqName?.parent()?.asString() != "kotlinx.coroutines")
                }.mapNotNull { it.importedName?.asString() }
            }
        }
        val coroutineScopeCandidatesByStatement = bodyCandidates { index, reference ->
            coroutineScopeDeclarations[reference.name]?.takeIf {
                !reference.isQualified && !reference.isBound
                        // refers to the local property shadowing the function, see shadowedFunctionCandidates
                        && shadowingProperties[reference.name]?.let { (propertyIndex) -> propertyIndex < index } != true
            }?.let { declarations ->
                val declaration = declarations.first()
                val element = file.findElementAt(reference.offset)
                // resolved to the nested coroutine scope, also in a regular script, without compiling
                if (reference.isInLambda && element != null && isInNestedCoroutineScope(element, statements[index].parent, scriptNames)) {
                    return@bodyCandidates null
                }
                // like `isActive = "b"`, `isActive += "b"`, or `isActive++`
                val assignment = (declaration.psi as? KtProperty)?.takeIf { it.isVar }?.let {
                    when (val operation = element?.parent?.parent) {
                        is KtBinaryExpression -> operation.takeIf {
                            it.left === element.parent && it.operationToken in KtTokens.ALL_ASSIGNMENTS
                        }?.operationReference
                        is KtUnaryExpression -> operation.takeIf {
                            it.baseExpression === element.parent && (it.operationToken == KtTokens.PLUSPLUS || it.operationToken == KtTokens.MINUSMINUS)
                        }?.operationReference
                        else -> null
                    }?.startOffset
                }
                CoroutineScopeCandidate(reference, declaration.line(), declaration.kind == DeclarationKind.FUNCTION, assignment)
            }
        }
        coroutineScopeCandidates = coroutineScopeCandidatesByStatement.values.flatten()
        val laterDeclaredCandidatesByStatement = bodyCandidates { index, reference ->
            dependencyProperties[reference.name]?.takeIf { (declarationIndex) ->
                // also when called, as the function of the same name might not apply
                declarationIndex > index && reference.refersToLocal(localProperties)
            }?.let { (_, property) -> LaterDeclaredCandidate(reference, property.line(), isFunction = false) }
                ?: dependencyFunctions[reference.name]?.takeIf { (declarationIndex) ->
                    declarationIndex > index && reference.isCall && reference.refersToLocal(localFunctions)
                }?.let { (_, function) ->
                    // the calls, unlike the callable references, within the lambdas of which the script instance declaring the dependency is the receiver
                    val scopeCalls = if (reference.isCallableReference) emptyList() else {
                        file.findElementAt(reference.offset)?.let { scopeCalls(it, statements[index].parent) }.orEmpty()
                    }
                    LaterDeclaredCandidate(reference, function.line(), isFunction = true, scopeCalls)
                }
        }
        laterDeclaredDependencyCandidates = laterDeclaredCandidatesByStatement.values.flatten()
        // the first local class of each name of a dependency, by the index of its statement
        val dependencyClasses = mutableMapOf<String, Pair<Int, ParsedDeclaration>>()
        statements.forEachIndexed { index, statement ->
            declarationsByPsi[statement]?.takeIf { it in localClasses && it.name in dependencyNames }?.let {
                dependencyClasses.putIfAbsent(it.name!!, index to it)
            }
        }
        val laterDeclaredClassCandidatesByStatement = bodyCandidates { index, reference ->
            dependencyClasses[reference.name]?.takeIf { (classIndex) ->
                // unlike a callable reference, like `::Foo`, crashing the compiler of a regular script,
                // or a call of the name of a local declaration, which might be resolved instead
                classIndex > index && reference.isCall && !reference.isCallableReference && !reference.isOperator
                        && reference.refersToLocal(localClassNames)
                        && reference.name !in localFunctions && reference.name !in localProperties
            }?.let { (_, declaration) ->
                val scopeCalls = file.findElementAt(reference.offset)?.let { scopeCalls(it, statements[index].parent) }.orEmpty()
                ClassCandidate(reference, declaration.line(), scopeCalls)
            }
        }
        laterDeclaredClassCandidates = laterDeclaredClassCandidatesByStatement.values.flatten()
        // unless they would conflict with the copies of the moved declarations of these names, see coroutineScopeMembers
        scopeFunctionCopies = if (laterDeclaredClassCandidates.none { it.scopeCalls.isNotEmpty() } && laterDeclaredDependencyCandidates.none { it.scopeCalls.isNotEmpty() }) {
            emptyList()
        } else {
            scopeFunctions.filterKeys { name -> moved.none { it.name == name } }.values.toList()
        }
        val statementIndices = statements.withIndex().associate { (index, statement) -> statement to index }
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
        /**
         * The offset where the copies preceding the statement of this [index] are inserted,
         * and the names of the local classes declared from there on, which are not visible there.
         */
        fun copyPlace(
            index: Int
        ): Pair<Int, Set<String>> {
            // preceding the first statement sharing the line, so the inserted line does not split another statement
            var first = index
            while (first > 0 && '\n' !in script.substring(statements[first - 1].endOffset, leadingStart(first))) first--
            return leadingStart(first) to localClasses.filter { statementIndices.getValue(it.psi) >= first }.mapTo(HashSet()) { it.name!! }
        }
        /** The indices of the statements of the candidates of each name, in the order of the script. */
        fun candidateIndices(
            candidatesByStatement: Map<Int, List<Reference>>
        ) = buildMap<String, MutableList<Int>> {
            candidatesByStatement.entries.sortedBy { it.key }.forEach { (index, candidates) ->
                candidates.mapTo(LinkedHashSet()) { it.name }.forEach { getOrPut(it) { mutableListOf() } += index }
            }
        }
        /**
         * The copies of the declarations of each name, with their messages, preceding the first statement of the candidates of this name,
         * given the names of the local classes declared from this statement on, which are not visible there,
         * and preceding each following one, which more local classes are visible to, like `Foo(Bar())` following `Foo(null)` and `class Bar`,
         * declaring the copies referring to them, while each copy is declared once, preceding its first use, see blockCopies.
         */
        fun copies(
            candidatesByStatement: Map<Int, List<Reference>>,
            declarations: (name: String, invisibleClasses: Set<String>) -> List<Pair<String, String>>
        ) = buildMap<Int, MutableList<Pair<String, String>>> {
            candidateIndices(candidatesByStatement).forEach { (name, indices) ->
                // only the ones not declared at the preceding places
                val declared = HashSet<String>()
                indices.map { copyPlace(it) }.distinctBy { (_, invisibleClasses) -> invisibleClasses }.forEach { (offset, invisibleClasses) ->
                    declarations(name, invisibleClasses).filter { (_, declaration) -> declared.add(declaration) }.takeIf { it.isNotEmpty() }?.let {
                        getOrPut(offset) { mutableListOf() } += it
                    }
                }
            }
        }
        /** The copies of these [functions] of each name, see [signature]. */
        fun functionCopies(
            functions: Map<String, List<ParsedDeclaration>>,
            message: (ParsedDeclaration) -> String
        ) = { name: String, invisibleClasses: Set<String> ->
            functions[name].orEmpty()
                .filterNot { it.refersToTypes(invisibleClasses) }
                .mapNotNull { function -> function.signature()?.let { message(function) to it } }
        }
        // the local classes of the names declared once, which the placeholders replace, see placeholderCopies
        val uniqueLocalClasses = localClasses.groupBy { it.name!! }.filterValues { it.size == 1 }.mapValues { it.value.single() }
        /**
         * The references of these parts of a declaration to the local classes of these [invisibleClasses] names, unqualified, unless shadowed by its type parameters,
         * which are the only declarations taking precedence over the local classes of the script body there, as the classes declared later than them are not visible,
         * or `null`, if it refers to them qualified, like `P.Inner`, which their placeholders do not declare, or to a class of a name declared more than once.
         */
        fun List<PsiElement>.invisibleClassReferences(
            invisibleClasses: Set<String>,
            typeParameters: List<KtTypeParameter>
        ): List<Pair<PsiElement, ParsedDeclaration>>? {
            val shadowed = typeParameters.mapNotNullTo(HashSet()) { it.name }
            return flatMap { part -> part.collectDescendantsOfType<KtUserType> { it.qualifier == null && it.referencedName in invisibleClasses && it.referencedName !in shadowed } }
                .map { type ->
                    val name = type.referenceExpression?.getReferencedNameElement()
                    val localClass = uniqueLocalClasses[type.referencedName]
                    if (name == null || localClass == null || type.parent is KtUserType) return null
                    name to localClass
                }
        }
        /** The references of the declaration of the local class, which its placeholder copies, see [placeholder]. */
        fun ParsedDeclaration.placeholderReferences(
            invisibleClasses: Set<String>
        ) = (psi as KtClassOrObject).let { declaration ->
            (listOfNotNull(declaration.typeParameterList, declaration.typeConstraintList) + declaration.superTypeListEntries.mapNotNull { it.typeReference })
                .invisibleClassReferences(invisibleClasses, declaration.typeParameters)
        }
        /**
         * The copies of the local functions of each name, like [functionCopies], while the ones referring to the local classes not visible there
         * refer to their placeholders instead, declared preceding them, of the same type parameters and supertypes, see [placeholder],
         * so they apply to the same arguments, as no value preceding a local class is of it.
         * These are the extensions of the coroutine scope of the script body, ranking below the local functions, like the copies following the classes,
         * so a call applying to both, like `f(null)` of `fun f(x: P?)` following `class P`, is not ambiguous,
         * and above the dependencies, the members of the receivers of the script block.
         * The ones of a function at the places, where different classes it refers to are not visible, rank the same,
         * so a call applying to both of them is ambiguous, see README.
         */
        val placeholderCopies = { name: String, invisibleClasses: Set<String> ->
            localFunctionsByName[name].orEmpty().flatMap { function ->
                val declaration = function.psi as KtNamedFunction
                // the types of the signature, unlike the default values replaced in the copy, of a function without a receiver, like the dependency
                val references = (
                    declaration.valueParameters.mapNotNull { it.typeReference }
                        + listOfNotNull(declaration.typeParameterList, declaration.typeConstraintList, declaration.typeReference)
                ).invisibleClassReferences(invisibleClasses, declaration.typeParameters) ?: return@flatMap emptyList()
                if (references.isEmpty()) return@flatMap listOfNotNull(declaration.signature()?.let { LATER_DECLARED_FUNCTION to it })
                // also the ones their declarations refer to, like their supertypes, by the classes
                val classReferences = HashMap<ParsedDeclaration, List<Pair<PsiElement, ParsedDeclaration>>>()
                references.map { it.second }.closure { localClass ->
                    classReferences.getOrPut(localClass) { localClass.placeholderReferences(invisibleClasses) ?: return@flatMap emptyList() }.map { it.second }
                }
                // named after the first declared of the classes it replaces, itself or the ones it refers to, which tells which of them are replaced,
                // so the copies at the places, where the same ones are not visible, like preceding and following an unrelated class, declare the same placeholders
                val placeholderNames = classReferences.keys.associateWith { localClass ->
                    val replaced = listOf(localClass).closure { classReferences.getValue(it).map { (_, referred) -> referred } }
                    "`$PLACEHOLDER_CLASS_PREFIX${replaced.minOf { statementIndices.getValue(it.psi) }}_${localClass.name}`"
                }
                fun List<Pair<PsiElement, ParsedDeclaration>>.renamed() = associate { (name, localClass) -> name to placeholderNames.getValue(localClass) }
                // in the order of the declarations, which refer only to the ones preceding them
                val placeholders = classReferences.entries.sortedBy { statementIndices.getValue(it.key.psi) }.map { (localClass, classReferences) ->
                    (localClass.psi as KtClassOrObject).placeholder(placeholderNames.getValue(localClass), classReferences.renamed())
                }
                val copy = declaration.signature(renamed = references.renamed(), receiver = "kotlinx.coroutines.CoroutineScope")
                    ?: return@flatMap emptyList()
                (placeholders + copy).map { LATER_DECLARED_FUNCTION to it }
            }
        }
        laterDeclaredFunctionCopies = copies(
            laterDeclaredCandidatesByStatement.mapValues { (_, candidates) -> candidates.filter { it.isFunction }.map { it.reference } },
            placeholderCopies
        )
        // the local functions, also the extension ones, by the indices of their statements, unless named like the dependencies, see laterDeclaredDependencyCandidates
        val namedFunctions = buildMap<String, MutableList<Pair<Int, ParsedDeclaration>>> {
            statements.forEachIndexed { index, statement ->
                declarationsByPsi[statement]?.takeIf {
                    it.kind == DeclarationKind.FUNCTION && it !in moved && it.name != null && it.name !in dependencyNames
                }?.let { getOrPut(it.name!!) { mutableListOf() } += index to it }
            }
        }
        val laterFunctionCandidatesByStatement = bodyCandidates { index, reference ->
            if (!reference.isCall || reference.isBound || reference.isCallableReference || !reference.mightCallOperator(valueNames)) {
                return@bodyCandidates null
            }
            namedFunctions[reference.name]?.firstOrNull { (functionIndex, function) ->
                functionIndex > index && when {
                    reference.isOperator -> function.isExtension && function.isOperator
                    // like `listOf(1, 2).sum()`
                    reference.isQualified -> function.isExtension
                    else -> true
                }
            }?.let { (_, function) -> reference to function.line() }
        }
        val laterCandidateIndices = candidateIndices(laterFunctionCandidatesByStatement.mapValues { (_, candidates) -> candidates.map { it.first } })
        // the functions of each name declared after its first candidate, which the copies would conflict with otherwise
        val laterFunctions = laterCandidateIndices.mapValues { (name, indices) ->
            namedFunctions.getValue(name).filter { (functionIndex) -> functionIndex > indices.first() }
        }
        // the local copies of the ones referring to the local classes, which the members of the script would not see,
        // and of the names of the functions of the coroutine scope of the script body, which its members would take precedence over
        val locallyCopied = laterFunctions.filter { (name, functions) ->
            name in coroutineScopeFunctions || functions.any { it.second.refersToTypes(localClassNames) }
        }.keys
        laterDeclaredFunctionCandidates = laterFunctionCandidatesByStatement.values.flatten().map { (reference, offset) ->
            val (arguments, indices) = file.argumentStarts(reference)
            LaterFunctionCandidate(
                reference, offset,
                isConfirmedOnlyWhenFailing = reference.name in locallyCopied && reference.mightResolveOnReceiver,
                arguments = arguments,
                indices = indices
            )
        }
        /** The copy of the local function, of the return type declared, as an inferred one would take precedence over the members of the receivers. */
        fun ParsedDeclaration.laterDeclaredFunctionCopy() = signature(keepsInferredType = false)?.let { signature ->
            laterDeclaredFunctionMessage(name!!, lineOf(lineStarts, line())) to signature
        }
        laterDeclaredLocalFunctionCopies = buildMap<Int, MutableList<Pair<String, String>>> {
            // each function once, as declaring it twice would make the calls ambiguous, preceding the first of its candidates seeing the local classes it refers to,
            // as the preceding ones cannot call it, unlike the following ones, like `V(1) + V(2)` preceded by `1 + 2` and `data class V(val x: Int)`
            laterFunctions.filterKeys { it in locallyCopied }.forEach { (name, functions) ->
                functions.forEach { (functionIndex, function) ->
                    val copy = function.laterDeclaredFunctionCopy() ?: return@forEach
                    laterCandidateIndices.getValue(name).asSequence().takeWhile { it < functionIndex }.map { copyPlace(it) }
                        .firstOrNull { (_, invisibleClasses) -> !function.refersToTypes(invisibleClasses) }
                        ?.let { (offset) -> getOrPut(offset) { mutableListOf() } += copy }
                }
            }
        }
        // the hidden local functions are copied as the members of the script already, see hiddenLocalFunctionCopies
        val hiddenNames = hiddenLocalDeclarations.mapTo(HashSet()) { it.name }
        laterDeclaredFunctionMemberCopies = laterFunctions.filterKeys { it !in locallyCopied && it !in hiddenNames }.values.flatten().mapNotNull { (_, function) ->
            function.laterDeclaredFunctionCopy()?.let { (message, signature) -> Triple(function.line(), message, signature) }
        }
        // resolved among the local functions, like among the members of the script class in the declarations of declared types
        shadowedFunctionCopies = copies(
            shadowedFunctionCandidatesByStatement.mapValues { (_, candidates) ->
                candidates.filterNot { it.isInInferredType }.map { it.reference }
            },
            functionCopies(movedFunctionsByName) { shadowedFunctionMessage(it.line()) }
        )
        constructorCopies = copies(laterDeclaredClassCandidatesByStatement.mapValues { (_, candidates) -> candidates.map { it.reference } }) { name, invisibleClasses ->
            (dependencyClasses.getValue(name).second.psi as KtClass).constructorCopies(invisibleClasses).map { LATER_DECLARED_CLASS to it }
        }
        coroutineScopeMembers = run {
            // the properties, which always apply, only within the lambdas, see ScriptSource.coroutineScopeCandidates
            val names = coroutineScopeCandidates.filter { (reference, _, isFunction) -> isFunction || reference.isInLambda }
                .mapTo(HashSet()) { it.reference.name }
            // also the extension functions, which might not apply, like `fun Robot.launch()` to `launch { }`
            moved.filter { it.name in names }.mapNotNull { declaration ->
                val property = declaration.psi as? KtProperty
                when {
                    declaration.kind == DeclarationKind.FUNCTION -> (declaration.psi as KtNamedFunction).anyMemberOverride()
                        ?: if (declaration.refersToTypes(localClassNames)) null else declaration.signature()
                    // overriding the inherited member, of the type compatible with any, as the copies are never evaluated
                    property != null && property.receiverTypeReference == null && property.name == "coroutineContext" ->
                        "override val `coroutineContext`: Nothing get() = kotlin.TODO()"
                    // of the known type, which might be called, like the function type of `val log get() = { m: String -> ... }`
                    property != null && property.receiverTypeReference == null -> "val `${property.name}`: ${
                        property.functionType(
                            localFunction = { name -> movedFunctionsByName[name]?.singleOrNull()?.psi as? KtNamedFunction },
                            typeAlias = { name -> typeAliases[name] }
                        ) ?: "Nothing"
                    } get() = kotlin.TODO()"
                    else -> null
                }
            }.distinct()
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
        // the names declared once, as the overloads, or the declarations of other kinds, would be resolved together in the script
        val declarationCounts = declarations.flatMap { listOfNotNull(it.name) + it.destructuredNames }.groupingBy { it }.eachCount()
        val blockIndices = statements.indices.filter { declarationsByPsi[statements[it]] !in moved }
        val insertions = blockIndices.map { copyPlace(it).first }
        var insertion = 0
        blockStatements = blockIndices.mapIndexed { index, statementIndex ->
            val statement = statements[statementIndex]
            val declaration = declarationsByPsi[statement]
            val function = declaration?.takeIf { it.kind == DeclarationKind.FUNCTION }?.name
            BlockStatement(
                psi = statement,
                start = declaration?.start ?: statement.startOffsetSkippingComments,
                end = statement.endOffset,
                function = function,
                isHoistable = function != null && !declaration.isOperator && declarationCounts[function] == 1 && function !in dependencyNames
                        && !(declaration.psi as KtNamedFunction).mightResolveAfterImplicitReceivers(),
                insertion = (if (index > 0 && insertions[index] == insertions[index - 1]) insertion else index).also { insertion = it },
                insertionOffset = insertions[index]
            )
        }
        localVariables = statements.filter { declarationsByPsi[it] !in moved }.flatMap { statement ->
            when (statement) {
                is KtProperty -> listOfNotNull(statement.name).takeIf { statement.isVar }.orEmpty()
                is KtDestructuringDeclaration -> statement.entries.mapNotNull { it.name }.takeIf { statement.isVar }.orEmpty()
                else -> emptyList()
            }
        }.toSet()
        endsWithBlockStatement = statements.isNotEmpty() && declarationsByPsi[statements.last()] !in moved
        declarationStarts = declarations.flatMap { declaration ->
            (listOfNotNull(declaration.name) + declaration.destructuredNames).map { it to declaration.start }
        }.groupBy({ it.first }) { it.second }
        operatorStarts = declarations.filter { it.isOperator }.map { it.start }
        val bodyNames = declarations.filter { it !in moved }.flatMapTo(HashSet()) { listOfNotNull(it.name) + it.destructuredNames }
        localReferences = blockStatements.zip(blockIndices).mapNotNull { (statement, index) ->
            val function = statements[index] as? KtNamedFunction ?: return@mapNotNull null
            statement.function?.let { it to function.unboundReferences().filterTo(HashSet()) { name -> name in bodyNames } }
        }.toMap()
    }

    /**
     * The local functions, which the [unresolved] references, by their offsets, call, declared later in the script block,
     * which are not visible there, while in the script they are, declared preceding the statements calling them instead,
     * each preceding the hoisted functions calling it, unless they call each other,
     * or `null`, if it might change how any other reference is resolved, or if another reference is unresolved.
     *
     * The statements gaining the visibility of a hoisted function might refer to its name only by these unresolved references,
     * which refer to it in the script, as nothing else of this name is visible there,
     * unlike e.g. a member of an implicit receiver, which a local function takes precedence over.
     * A hoisted function does not refer to the declarations in between, which are not visible there anymore,
     * while the script resolves them after the implicit receivers, as declared later than the statement calling it, if its type is inferred,
     * nor to the local variables, whose smart casts it might affect,
     * and it is not an operator, nor any declaration in between, as the operators are referred to without their names, like `a + b`.
     * A hoisted function of an inferred type, which the script resolves after the implicit receivers, as called before its declaration,
     * has no scopes of implicit receivers, see [mightResolveAfterImplicitReceivers].
     */
    fun hoisting(
        unresolved: Map<Int, String>
    ): Hoisting? {
        if (unresolved.isEmpty()) return null
        val functionIndices = blockStatements.indices.filter { blockStatements[it].function != null }
            .associateBy { blockStatements[it].function!! }
        // by the callers, which precede the callees, so each caller is hoisted already, when its callees are
        val requests = unresolved.map { (offset, name) ->
            val caller = (blockStatements.binarySearchBy(offset) { it.start }.let { if (it >= 0) it else -it - 2 })
                .takeIf { it >= 0 && offset < blockStatements[it].end } ?: return null
            val callee = functionIndices[name]?.takeIf { it > caller && blockStatements[it].isHoistable } ?: return null
            caller to callee
        }.sortedBy { it.first }
        // the index of the statement, which each hoisted function precedes
        val targets = mutableMapOf<Int, Int>()
        requests.forEach { (caller, callee) ->
            val target = blockStatements[targets[caller] ?: caller].insertion
            targets[callee] = minOf(targets[callee] ?: target, target)
        }
        val hoistedFunctions = targets.keys.associateBy { blockStatements[it].function!! }
        // the declarations in between, declared later than the statement calling it, which the script resolves after the implicit receivers,
        // if they are not resolved yet, while the local declarations are resolved before them, like the hoisted functions
        targets.forEach { (index, target) ->
            val hoisted = blockStatements[index]
            val between = blockStatements[target].insertionOffset until hoisted.start
            if (
                operatorStarts.any { it in between }
                || hoisted.identifiers.values.any { name -> declarationStarts[name].orEmpty().any { it in between } }
                || hoisted.identifiers.values.any { it in localVariables }
            ) return null
        }
        // the hoisted functions preceding each statement, each following the ones it calls, which are declared later than it,
        // as it does not refer to the ones declared in between
        val functions = targets.entries.groupBy({ it.value }) { it.key }.mapValues { (_, indices) -> indices.sortedDescending() }
        val positions = IntArray(blockStatements.size)
        blockStatements.indices.flatMap { index ->
            functions[index].orEmpty() + listOf(index).filter { it !in targets }
        }.forEachIndexed { position, index -> positions[index] = position }
        // the statements gaining the visibility of the hoisted functions declared later, referring to them only by the unresolved references
        blockStatements.forEachIndexed { index, statement ->
            if (statement.identifiers.any { (offset, name) ->
                    hoistedFunctions[name]?.let { hoisted -> hoisted > index && positions[hoisted] < positions[index] } == true && unresolved[offset] != name
                }) return null
        }
        return Hoisting(
            functions = functions.entries.associate { (target, indices) ->
                blockStatements[target].insertionOffset to indices.map { blockStatements[it].start until blockStatements[it].end }
            },
            references = unresolved.keys,
            region = functions.keys.minOf { blockStatements[it].insertionOffset } until targets.keys.maxOf { blockStatements[it].end },
            endsWithHoistedFunction = endsWithBlockStatement && blockStatements.lastIndex in targets
        )
    }


    /** The [propertyTypes] which are types indeed, which the copies of the [untypedProperties] are declared of, by their names, see [ScriptSource.propertyTypes]. */
    fun copiedPropertyTypes(
        propertyTypes: Map<String, String>
    ): Map<String, String> = propertyTypes.filter { (name, type) ->
        // as rendered by the compiler, which might not be valid types, like `<anonymous>`
        name in untypedProperties && '\n' !in type && psiFactory.createTypeIfPossible(type)?.let { reference ->
            reference.text == type && !reference.anyDescendantOfType<PsiErrorElement>()
        } == true
    }

    /** The deprecated copies of the local declarations, see [ScriptSource.hiddenLocalFunctionCandidates], given the [copiedPropertyTypes]. */
    fun hiddenLocalFunctionCopies(
        copiedPropertyTypes: Map<String, String>
    ): List<HiddenLocalCopy> = hiddenLocalDeclarations.flatMap { declarations ->
        val name = declarations.name
        declarations.functions.map { HiddenLocalCopy(name, it.signature, it.isOperator, it.offset) } + (declarations.propertyTypes ?: listOfNotNull(
            copiedPropertyTypes[name] ?: if (declarations.isReferredToAsValue) "Nothing" else null
        )).map { HiddenLocalCopy(name, "val `$name`: $it get() = kotlin.TODO()", isOperator = false, offset = null) }
    }

    /** The functions checking the types of the copies of the [untypedProperties] of these [copiedPropertyTypes], by their names, see [ScriptSource.propertyTypes]. */
    fun propertyTypeCheckFunctions(
        copiedPropertyTypes: Map<String, String>
    ): List<Pair<String, String>> = copiedPropertyTypes.entries.mapIndexed { index, (name, type) ->
        name to "fun `_typeCheck_$index`(value: $type): $type = kotlin.TODO()"
    }

    /**
     * The statements probing, if [probes], or checking, the types of these [properties], by their names, with the ends of the properties they follow,
     * see [ScriptSource.propertyTypeProbes].
     */
    fun propertyTypeStatements(
        properties: Collection<String>,
        probes: Boolean
    ): List<Triple<Int, String, String>> = properties.mapIndexed { index, name ->
        Triple(
            untypedPropertyEnds.getValue(name),
            name,
            if (probes) {
                "val `_typeProbe_$index`: $TYPE_PROBE_CLASS = `$name`"
            } else {
                // assignable both ways, so the copy is of the same type, unless the names of its type refer to other declarations there
                "var `_typeChecked_$index` = `$name`; `_typeChecked_$index` = `_typeCheck_$index`(`$name`)"
            }
        )
    }

    /**
     * The deprecated copies of the local functions, see [ScriptSource.laterDeclaredDependencyCandidates],
     * and of the moved functions, if [withShadowedFunctions], see [ScriptSource.shadowedFunctionCopies],
     * and of the constructors of the local classes, if [withConstructors], see [ScriptSource.coroutineScopeCopies],
     * and of the local functions, if [withLaterDeclaredFunctions], see [ScriptSource.laterDeclaredFunctionCandidates],
     * with the messages of their opt-in markers, by the offsets of the statements they precede.
     */
    fun blockCopies(
        withShadowedFunctions: Boolean,
        withConstructors: Boolean,
        withLaterDeclaredFunctions: Boolean
    ): Map<Int, List<Pair<List<String>, String>>> = buildMap<Int, MutableList<Pair<String, String>>> {
        listOfNotNull(
            laterDeclaredFunctionCopies,
            laterDeclaredLocalFunctionCopies.takeIf { withLaterDeclaredFunctions },
            shadowedFunctionCopies.takeIf { withShadowedFunctions },
            constructorCopies.takeIf { withConstructors }
        ).forEach { copies ->
            copies.forEach { (offset, declarations) -> getOrPut(offset) { mutableListOf() } += declarations }
        }
    }.let { copies ->
        // the same signature once, preceding its first use, as declaring it twice would make the calls ambiguous
        copies.entries.sortedBy { it.key }
            .flatMap { (offset, declarations) -> declarations.map { (message, declaration) -> Triple(offset, message, declaration) } }
            .groupBy { it.third }
            .map { (declaration, uses) -> uses.first().first to (uses.map { it.second }.distinct() to declaration) }
            .groupBy({ it.first }) { it.second }
    }

    /**
     * The local declarations of the [name] of the [hiddenLocalFunctionCandidates]: the signatures of the [functions],
     * the function types of the callable [propertyTypes], if known, see [hiddenLocalFunctionCopies].
     */
    /** The [signature] of the local function, whether it [isOperator], and the [offset] of its name. */
    private class FunctionCopy(
        val signature: String,
        val isOperator: Boolean,
        val offset: Int
    )

    private class HiddenLocalDeclarations(
        val name: String,
        val functions: List<FunctionCopy>,
        val propertyTypes: List<String>?,
        /** Whether the moved declarations refer to the local property as a value, like `E`, so its copy is declared even if its type is not known. */
        val isReferredToAsValue: Boolean
    )

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
            },
            nestedPathRanges = if (withNestedNames) {
                buildMap {
                    psi.collectDescendantsOfType<KtNamedDeclaration> { it.isPathElement() }.forEach { nested ->
                        val path = (nested.parents.takeWhile { it !== psi }.filterIsInstance<KtNamedDeclaration>()
                            .filter { it.isPathElement() }.toList().asReversed() + nested)
                            .map { it.pathName() ?: return@forEach }
                        val name = nested.nameIdentifier
                            ?: nested.modifierList?.getModifier(KtTokens.COMPANION_KEYWORD)
                            ?: return@forEach
                        putIfAbsent(path.joinToString("."), name.startOffset until name.endOffset)
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
        statementNames: Set<String>,
        localProperties: Set<String>,
        valueNames: Set<String>
    ): Set<ParsedDeclaration> {
        val named = declarations.filter { it.name != null && it.kind != DeclarationKind.PROPERTY }
        val namedByName = named.groupBy { it.name!! }
        val subtypesByName = mutableMapOf<String, MutableList<ParsedDeclaration>>()
        named.filter { it.kind != DeclarationKind.FUNCTION }.forEach { declaration ->
            declaration.supertypeNames.forEach { subtypesByName.getOrPut(it) { mutableListOf() } += declaration }
        }
        // a qualified reference, like `list.size`, cannot refer to a declaration of the script, unless it is an extension
        fun Reference.referenced(): List<ParsedDeclaration> = if (!mightCallOperator(valueNames)) emptyList() else namedByName[name].orEmpty().filter {
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
        /**
         * Whether the [declaration] is a function, which cannot be moved, as it is accessing, or it is an extension overloaded by an accessing one,
         * which would stay in the script body, hiding the moved one there for the references staying there,
         * like `3.f()` of `fun Any.f()` accessing and `fun Int.f()`, or `3("x")` of the `invoke` operators,
         * except the ones of the overloads themselves, like `this + v.n` of `operator fun String.plus(v: V)`,
         * unlike the plain functions, the calls of which split this way are confirmed, see ScriptSource.shadowedFunctionCandidates.
         */
        fun isAccessingFunction(
            declaration: ParsedDeclaration
        ): Boolean {
            if (declaration.kind != DeclarationKind.FUNCTION) return false
            if (declaration in accessing) return true
            val name = declaration.name!!
            return declaration.isExtension && namedByName.getValue(name).any { it.kind == DeclarationKind.FUNCTION && it.isExtension && it in accessing }
                    && (name in statementNames || accessing.any { staying ->
                        staying.name != name && staying.references.any { it.name == name && it.mightCallOperator(valueNames) }
                    })
        }
        val queue = ArrayDeque(moved)
        while (queue.isNotEmpty()) {
            references.getValue(queue.removeFirst()).forEach { declaration ->
                if (declaration !in moved && !isAccessingFunction(declaration)) {
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
                        if (!isAccessingFunction(it) && moved.add(it)) queue += it
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
