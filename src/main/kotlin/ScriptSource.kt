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
import kotlin.script.experimental.api.SourceCode

/**
 * A position in the original script, both the [line] and the [column] are 0-based.
 */
internal data class ScriptPosition(
    val line: Int,
    val column: Int
)

/**
 * The script turned into a block, which can be run in the coroutine of the caller:
 *
 * ```
 * <file annotations, package and imports>
 * <preamble of the dependency accessors>
 * <declarations which cannot be local>
 * _script {
 * <the rest of the script>
 * }
 * ```
 *
 * Declarations are moved by blanking them out with spaces in one copy of their lines,
 * and everything else in the other, so the columns of the script are retained,
 * while each line of the [text] maps back to the line of the script.
 */
internal class ScriptSource(
    script: String,
    /** The declarations of the dependency accessors, by the names of the dependencies. */
    preamble: Map<String, String> = emptyMap()
) : SourceCode {

    val scriptLines: List<String>

    override val text: String

    override val name: String? = null

    override val locationId: String

    /** The first `}` closing the script block, which would otherwise end the script early. */
    val unmatchedClosingBrace: ScriptPosition?

    private val lineStarts: List<Int>

    /** For each line of the [text], the line of the script, or `null` for the lines of the block and the preamble. */
    private val lineMapping: List<Int?>

    /** The 0-based line of the [text] where the preamble starts. */
    private val preambleStart: Int

    private val preambleDependencies: List<String> = preamble.keys.toList()

    private val movedDeclarations: List<Declaration>

    private val bodyDeclarations: List<Declaration>

    init {
        val lineBounds = lineBounds(script)
        lineStarts = lineBounds.map { it.first }
        scriptLines = lineBounds.map { script.substring(it.first, it.last + 1) }
        // the parser recognizes only `\n` as a line terminator, replacing each character keeps the offsets
        val structure = ScriptStructure(script.replace('\r', '\n'))
        unmatchedClosingBrace = structure.unmatchedClosingBrace?.let { position(it) }
        movedDeclarations = structure.movedDeclarations
        bodyDeclarations = structure.bodyDeclarations

        val lines = mutableListOf<String>()
        val mapping = mutableListOf<Int?>()
        fun addLines(
            range: IntRange,
            keep: (offset: Int) -> Boolean
        ) = range.forEach { line ->
            val bounds = lineBounds[line]
            lines += buildString {
                bounds.forEach { offset ->
                    +(if (keep(offset)) script[offset] else ' ')
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
        preambleStart = lines.size
        lines += preamble.values
        mapping += preamble.map { null }
        movedDeclarations.forEach { declaration ->
            addLines(lineOf(declaration.start)..lineOf(declaration.end - 1)) {
                it in declaration.start until declaration.end
            }
        }
        lines += "_script {"
        mapping += null
        addLines(lineOf(headerEnd)..lineBounds.lastIndex) { offset ->
            offset >= headerEnd && !excluded[offset]
        }
        if (structure.endsWithMovedDeclaration) {
            // the block would return the value of the statement before the declaration, unlike the script
            lines += "Unit"
            mapping += null
        }
        lines += "}"
        mapping += null

        text = lines.joinToString(separator = "\n")
        lineMapping = mapping
        locationId = "\$${Integer.toHexString(text.hashCode())}.kts"
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
    ): String? = preambleDependencies.getOrNull(line - 1 - preambleStart)

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
     * The start and the end of the name of the declaration moved out of the script body.
     */
    fun movedDeclarationName(
        name: String
    ): Pair<ScriptPosition, ScriptPosition>? = movedDeclarations.find {
        it.name == name
    }?.nameRange?.let {
        position(it.first) to position(it.last + 1)
    }

    /**
     * The 0-based line of the declaration of the [name] in the script body.
     */
    fun bodyDeclarationLine(
        name: String
    ): Int? = bodyDeclarations.find { it.name == name || name in it.destructuredNames }?.let {
        position(it.start).line
    }

    private fun position(
        offset: Int
    ): ScriptPosition {
        val line = lineOf(lineStarts, offset)
        return ScriptPosition(line, offset - lineStarts[line])
    }

}

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
    val start: Int,
    val end: Int,
    val nameRange: IntRange?,
    /** The names of a destructuring declaration, like `val (a, b) = pair`. */
    val destructuredNames: List<String> = emptyList()
)

private enum class DeclarationKind {
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
    val isQualified: Boolean,
    val isBound: Boolean
)

/**
 * The references by name, in the order of the script, without the labels and the names of the arguments,
 * bound by the declarations within the [scope], exclusive.
 */
private fun PsiElement.references(
    scope: PsiElement
): List<Reference> = collectDescendantsOfType<KtSimpleNameExpression> {
    it !is KtLabelReferenceExpression && it.parent !is KtValueArgumentName
}.map { expression ->
    val callableReference = expression.parent as? KtCallableReferenceExpression
    val name = expression.getReferencedName()
    Reference(
        name = name,
        offset = expression.startOffset,
        isQualified = expression.getReceiverExpression() != null || (
                callableReference?.callableReference == expression && callableReference.receiverExpression != null
        ),
        isBound = isBound(expression, name, scope)
    )
}

/**
 * Whether the [name] referred to by the [expression] is declared in the enclosing elements within the [scope], exclusive,
 * recognized by the names only, without the declarations inherited from the supertypes.
 */
private fun isBound(
    expression: PsiElement,
    name: String,
    scope: PsiElement
): Boolean {
    var child = expression
    var parent = expression.parent
    // the plain parameters of the primary constructor are not visible in the functions of the class
    var inFunction = false
    while (parent != null && parent !== scope) {
        val declares = when (parent) {
            is KtDeclarationWithBody -> parent.valueParameters.any { it.declares(name) }
            is KtBlockExpression -> parent.statements.takeWhile { it !== child }.any { it.declares(name) }
            is KtForExpression -> child === parent.body && parent.loopParameter?.declares(name) == true
            is KtCatchClause -> child === parent.catchBody && parent.catchParameter?.name == name
            is KtWhenExpression -> child !== parent.subjectVariable && parent.subjectVariable?.name == name
            is KtClassBody -> parent.declarations.any { it.declares(name) }
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
): Boolean = when (this) {
    is KtParameter -> this.name == name || destructuringDeclaration?.declares(name) == true
    is KtDestructuringDeclaration -> entries.any { it.name == name }
    is KtNamedDeclaration -> this.name == name
    else -> false
}

/**
 * The top-level structure of the script, recognized with the Kotlin parser:
 * the header of file annotations, package and imports, and the declarations
 * which cannot be local to the script block, see [ScriptSource].
 */
private class ScriptStructure(
    private val script: String
) {

    val headerEnd: Int

    val unmatchedClosingBrace: Int? = unmatchedClosingBrace(script)

    val movedDeclarations: List<Declaration>

    val bodyDeclarations: List<Declaration>

    /** Whether the last statement of the script is a declaration moved out of the script body. */
    val endsWithMovedDeclaration: Boolean

    /** The modifiers which are not applicable to local declarations. */
    val blankedRanges = mutableListOf<IntRange>()

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
        val moved = selectMovedDeclarations(declarations, block?.references(scope = block).orEmpty())
        movedDeclarations = moved.map { it.toDeclaration() }
        endsWithMovedDeclaration = moved.any { it.psi == statements.lastOrNull() }
        bodyDeclarations = declarations
            .filter { it !in moved && (it.kind == DeclarationKind.PROPERTY || it.kind == DeclarationKind.FUNCTION) }
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
        val destructuredNames: List<String>,
        val isSealed: Boolean,
        /** Whether it is a function with a receiver, like `fun Int.double()`. */
        val isExtension: Boolean,
        /** The names of the supertypes, like `Shape` in `class Circle(val r: Int) : Shape`. */
        val supertypeNames: List<String>
    ) {
        /** Without the comments preceding the declaration, which are left in the script body. */
        val start = psi.startOffsetSkippingComments
        val references = psi.references(scope = psi.parent)
        fun toDeclaration() = Declaration(
            name = name,
            start = start,
            end = end,
            nameRange = nameRange,
            destructuredNames = destructuredNames
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
            // so instead its annotations are blanked out, unless the order does not matter
            is KtProperty -> declaration.receiverTypeReference != null
                    || accessors.isNotEmpty()
                    || (hasMemberOnlyAnnotation && declaration.initializer.isConstant())
            else -> false
        }
        return ParsedDeclaration(
            psi = declaration,
            end = end,
            kind = kind,
            name = nameIdentifier?.text,
            nameRange = nameIdentifier?.let { it.startOffset until it.endOffset },
            requiresMove = requiresMove,
            destructuredNames = (declaration as? KtDestructuringDeclaration)?.entries.orEmpty()
                .mapNotNull { it.name }
                .filter { it != "_" },
            isSealed = declaration.hasModifier(KtTokens.SEALED_KEYWORD),
            isExtension = declaration is KtNamedFunction && declaration.receiverTypeReference != null,
            supertypeNames = (declaration as? KtClassOrObject)?.superTypeListEntries.orEmpty().mapNotNull {
                (it.typeReference?.typeElement as? KtUserType)?.referencedName
            }
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
     * Local functions are not visible before their declaration, so the ones referred to earlier are moved as well.
     *
     * The references are recognized by the names only, which might be the names of members as well,
     * therefore a function is not moved if it, or the declarations it refers to, access the local properties of the script body.
     */
    private fun selectMovedDeclarations(
        declarations: List<ParsedDeclaration>,
        bodyReferences: List<Reference>
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
        val localProperties = declarations.filter {
            it.kind == DeclarationKind.PROPERTY && it !in required
        }.flatMap { listOfNotNull(it.name) + it.destructuredNames }.toSet()
        val accessingLocalProperties = declarations.filter { declaration ->
            declaration.references.any { !it.isQualified && !it.isBound && it.name in localProperties }
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
            declaration.kind == DeclarationKind.FUNCTION
                    && (firstReferences[declaration] ?: Int.MAX_VALUE) < declaration.start
        }.forEach { function ->
            // moving a function, which is not accessing, moves only the ones, which are not accessing either
            if (function !in moved && function !in accessing) {
                val queue = ArrayDeque(listOf(function))
                moved += function
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
