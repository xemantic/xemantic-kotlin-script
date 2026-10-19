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
import org.jetbrains.kotlin.com.intellij.psi.tree.IElementType
import org.jetbrains.kotlin.lexer.KotlinLexer
import org.jetbrains.kotlin.lexer.KtTokens
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
        // the lexer recognizes only `\n` as a line terminator, replacing each character keeps the offsets
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

private class Token(
    val type: IElementType,
    val start: Int,
    val end: Int,
    val text: String
)

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

private val modifiers = setOf(
    "public", "private", "internal", "protected",
    "enum", "sealed", "data", "open", "abstract", "final", "inner", "value", "annotation",
    "inline", "suspend", "tailrec", "operator", "infix", "external",
    "const", "lateinit", "override", "expect", "actual"
)

private val visibilityModifiers = setOf("public", "private", "internal", "protected")

/**
 * The modifiers of top-level functions and properties, which are not applicable to local ones,
 * while the declarations moved out of the script body are the members of the script class, where `const` is not allowed either.
 */
private val memberOnlyModifiers = setOf("open", "final", "const")

/** The annotations, which are not applicable to local declarations. */
private val memberOnlyAnnotations = setOf("Volatile", "Transient", "JvmField", "JvmName", "JvmOverloads")

/** Tokens which cannot end a statement, so the line after them continues it. */
private val continuedAfter = setOf(
    "=", "+=", "-=", "*=", "/=", "%=", "+", "-", "*", "/", "%", "!",
    "&&", "||", "?:", ".", "?.", ",", ":", "::", "->", "(", "[", "{", "<",
    "==", "!=", "===", "!==", ">=", "<=", "..", "..<",
    "in", "!in", "is", "!is", "as", "as?", "else", "@"
)

/** Tokens which cannot start a statement, so the line starting with them continues the previous one. */
private val continuingWith = setOf(
    ".", "?.", "?:", "&&", "||", ":", "=", "->", "as", "as?", "where", "by"
)

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

private val closingTokens = openingOf.keys

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
 * The top-level structure of the script, recognized with the Kotlin lexer:
 * the header of file annotations, package and imports, and the declarations
 * which cannot be local to the script block, see [ScriptSource].
 */
private class ScriptStructure(
    private val script: String
) {

    /** The significant tokens, without whitespaces and comments. */
    private val tokens: List<Token>

    /** The nesting depth of brackets, braces, parentheses and strings, before each token. */
    private val depths: IntArray

    var headerEnd: Int = 0
        private set

    var unmatchedClosingBrace: Int? = null
        private set

    val movedDeclarations: List<Declaration>

    val bodyDeclarations: List<Declaration>

    /** The modifiers which are not applicable to local declarations. */
    val blankedRanges = mutableListOf<IntRange>()

    init {
        val allTokens = buildList {
            val lexer = KotlinLexer()
            lexer.start(script)
            while (lexer.tokenType != null) {
                add(Token(lexer.tokenType!!, lexer.tokenStart, lexer.tokenEnd, script.substring(lexer.tokenStart, lexer.tokenEnd)))
                lexer.advance()
            }
        }
        if (allTokens.firstOrNull()?.type == KtTokens.SHEBANG_COMMENT) {
            headerEnd = allTokens.first().end
        }
        tokens = allTokens.filterNot {
            it.type in KtTokens.WHITESPACES || it.type in KtTokens.COMMENTS
        }
        depths = IntArray(tokens.size)
        val opened = mutableListOf<IElementType>()
        tokens.forEachIndexed { index, token ->
            depths[index] = opened.size
            if (token.type in openingTokens) opened += token.type
            if (token.type in closingTokens) {
                val opening = opened.lastIndexOf(openingOf.getValue(token.type))
                val enclosing = opened.indexOfLast { it in enclosingOf.getValue(token.type) }
                if (opening > enclosing) {
                    opened.subList(opening, opened.size).clear()
                } else if (token.type == KtTokens.RBRACE && enclosing == -1 && unmatchedClosingBrace == null) {
                    unmatchedClosingBrace = token.start
                }
            }
        }

        val bodyStart = parseHeader()
        val declarations = parseBody(bodyStart)
        val moved = selectMovedDeclarations(declarations, bodyStart)
        movedDeclarations = moved.map { it.toDeclaration() }
        bodyDeclarations = declarations
            .filter { it !in moved && (it.kind == DeclarationKind.PROPERTY || it.kind == DeclarationKind.FUNCTION) }
            .map { it.toDeclaration() }
        declarations.filter { it !in moved }.forEach { declaration ->
            declaration.modifiers
                .filter {
                    it.text in visibilityModifiers || (it.text in memberOnlyModifiers
                            && (declaration.kind == DeclarationKind.FUNCTION || declaration.kind == DeclarationKind.PROPERTY))
                }
                .forEach { blankedRanges += it.start until it.end }
        }
    }

    private class ParsedDeclaration(
        val kind: DeclarationKind,
        val name: String?,
        val nameIndex: Int?,
        val modifiers: List<Token>,
        val first: Int,
        val last: Int,
        val tokens: List<Token>,
        /** The tokens before the body, e.g. the supertypes. */
        val headerTokens: List<Token>,
        val requiresMove: Boolean,
        val destructuredNames: List<String>
    ) {
        val isSealed = modifiers.any { it.text == "sealed" }
        val identifiers = tokens.filter { it.type == KtTokens.IDENTIFIER }.mapTo(HashSet()) { it.text }
        val headerIdentifiers = headerTokens.filter { it.type == KtTokens.IDENTIFIER }.mapTo(HashSet()) { it.text }
        fun toDeclaration() = Declaration(
            name = name,
            start = tokens.first().start,
            end = tokens.last().end,
            nameRange = nameIndex?.let { tokens[it - first] }?.let { it.start until it.end },
            destructuredNames = destructuredNames
        )
    }

    private fun parseHeader(): Int {
        var index = 0
        while (index < tokens.size) {
            val token = tokens[index]
            val isHeaderEntry = (token.type == KtTokens.AT
                    && tokens.getOrNull(index + 1)?.text == "file"
                    && tokens.getOrNull(index + 2)?.type == KtTokens.COLON
            ) || token.text == "package" || token.text == "import"
            if (!isHeaderEntry) break
            index = statementEnd(index)
            if (tokens.getOrNull(index)?.type == KtTokens.SEMICOLON) index++
            headerEnd = tokens[index - 1].end
        }
        return index
    }

    private fun parseBody(
        bodyStart: Int
    ): List<ParsedDeclaration> = buildList {
        var index = bodyStart
        while (index < tokens.size) {
            if (tokens[index].start == unmatchedClosingBrace) break
            if (depths[index] == 0) {
                if (isStatementStart(index, bodyStart)) {
                    val declaration = parseDeclaration(index)
                    if (declaration != null) {
                        add(declaration)
                        index = declaration.last + 1
                        continue
                    }
                }
            }
            index++
        }
    }

    private fun isStatementStart(
        index: Int,
        bodyStart: Int
    ): Boolean = index == bodyStart
            || tokens[index - 1].type == KtTokens.SEMICOLON
            || (isOnNewLine(index) && !continuesPreviousLine(index))

    private fun isOnNewLine(
        index: Int
    ): Boolean = index > 0 && script.substring(tokens[index - 1].end, tokens[index].start).contains('\n')

    private fun columnOf(
        offset: Int
    ): Int = offset - (script.lastIndexOf('\n', offset - 1) + 1)

    private fun continuesPreviousLine(
        index: Int
    ): Boolean = tokens[index - 1].text in continuedAfter || tokens[index].text in continuingWith

    /** The index after the last token of the statement starting at the [index], ignoring its kind. */
    private fun statementEnd(
        index: Int
    ): Int {
        val depth = depths[index]
        var current = index + 1
        while (current < tokens.size) {
            if (depths[current] <= depth) {
                if (depths[current] < depth) break
                if (tokens[current].type == KtTokens.SEMICOLON) break
                if (isOnNewLine(current) && !continuesPreviousLine(current)) break
            }
            current++
        }
        return current
    }

    private fun parseDeclaration(
        first: Int
    ): ParsedDeclaration? {
        var index = first
        val declarationModifiers = mutableListOf<Token>()
        var hasMemberOnlyAnnotation = false
        while (index < tokens.size) {
            val token = tokens[index]
            val next = tokens.getOrNull(index + 1) ?: return null
            when {
                token.type == KtTokens.AT -> {
                    if (isMemberOnlyAnnotation(index)) hasMemberOnlyAnnotation = true
                    index = annotationEnd(index)
                }
                token.type == KtTokens.IDENTIFIER
                        && token.text in modifiers
                        && (next.type == KtTokens.IDENTIFIER || next.type in KtTokens.KEYWORDS) -> {
                    declarationModifiers += token
                    index++
                }
                else -> break
            }
        }
        if (index >= tokens.size) return null
        val keywordToken = tokens[index]
        val next = tokens.getOrNull(index + 1)
        var keyword = index
        val kind = when (keywordToken.text) {
            "class" -> DeclarationKind.CLASS
            "interface" -> DeclarationKind.INTERFACE
            "object" -> if (next?.type == KtTokens.IDENTIFIER) DeclarationKind.OBJECT else return null
            "typealias" -> DeclarationKind.TYPE_ALIAS
            "fun" -> if (next?.text == "interface") {
                keyword++
                DeclarationKind.INTERFACE
            } else {
                DeclarationKind.FUNCTION
            }
            "val", "var" -> DeclarationKind.PROPERTY
            else -> return null
        }
        val last = declarationEnd(first, keyword, kind)
        val declarationTokens = tokens.subList(first, last + 1)
        val bodyStart = (keyword..last).firstOrNull {
            depths[it] == 0 && tokens[it].type == KtTokens.LBRACE
        } ?: (last + 1)
        val nameIndex = when (kind) {
            FUNCTION -> (keyword..last).firstOrNull {
                depths[it] == 0 && tokens[it].type == KtTokens.LPAR
            }?.let { it - 1 }
            else -> keyword + 1
        }?.takeIf { tokens.getOrNull(it)?.type == KtTokens.IDENTIFIER }
        val requiresMove = when (kind) {
            CLASS -> declarationModifiers.any {
                it.text in setOf("enum", "sealed", "annotation", "value", "inline")
            } || hasNestedDeclarations(bodyStart, last)
            INTERFACE,
            OBJECT,
            TYPE_ALIAS -> true
            FUNCTION -> declarationModifiers.any { it.text == "inline" } || hasMemberOnlyAnnotation
            PROPERTY -> isExtensionOrHasAccessor(keyword, last) || hasMemberOnlyAnnotation
        }
        val destructuredNames = if (kind == DeclarationKind.PROPERTY && tokens.getOrNull(keyword + 1)?.type == KtTokens.LPAR) {
            (keyword + 2 until closingIndex(keyword + 1)).filter {
                depths[it] == depths[keyword] + 1
                        && tokens[it].type == KtTokens.IDENTIFIER
                        && tokens[it - 1].type.let { previous -> previous == KtTokens.LPAR || previous == KtTokens.COMMA }
                        && tokens[it].text != "_"
            }.map { tokens[it].text }
        } else {
            emptyList()
        }
        return ParsedDeclaration(
            kind = kind,
            name = nameIndex?.let { tokens[it].text },
            nameIndex = nameIndex,
            modifiers = declarationModifiers,
            first = first,
            last = last,
            tokens = declarationTokens,
            headerTokens = tokens.subList(keyword, bodyStart),
            requiresMove = requiresMove,
            destructuredNames = destructuredNames
        )
    }

    /** Whether the annotation is not applicable to a local declaration, like `@Volatile` or `@get:JvmName`. */
    private fun isMemberOnlyAnnotation(
        at: Int
    ): Boolean {
        // a use-site target
        if (tokens.getOrNull(at + 2)?.type == KtTokens.COLON) return true
        var index = at + 1
        while (tokens.getOrNull(index + 1)?.type == KtTokens.DOT) index += 2
        return tokens.getOrNull(index)?.text in memberOnlyAnnotations
    }

    private fun annotationEnd(
        at: Int
    ): Int {
        var index = at + 1
        if (tokens.getOrNull(index)?.type == KtTokens.LBRACKET) {
            return closingIndex(index) + 1
        }
        // a use-site target, like in `@get:JvmName`
        if (tokens.getOrNull(index + 1)?.type == KtTokens.COLON) index += 2
        index++
        while (tokens.getOrNull(index)?.type == KtTokens.DOT) index += 2
        if (tokens.getOrNull(index)?.type == KtTokens.LT) {
            var angles = 0
            while (index < tokens.size) {
                if (tokens[index].type == KtTokens.LT) angles++
                if (tokens[index].type == KtTokens.GT) angles--
                index++
                if (angles == 0) break
            }
        }
        if (tokens.getOrNull(index)?.type == KtTokens.LPAR && !isOnNewLine(index)) {
            index = closingIndex(index) + 1
        }
        return index
    }

    private fun closingIndex(
        opening: Int
    ): Int {
        val depth = depths[opening]
        return (opening + 1 until tokens.size).firstOrNull { depths[it] == depth + 1 && tokens[it].type in closingTokens }
            ?: tokens.lastIndex
    }

    /** The index of the last token of the declaration. */
    private fun declarationEnd(
        first: Int,
        keyword: Int,
        kind: DeclarationKind
    ): Int {
        val column = columnOf(tokens[first].start)
        var hasBody = false
        var hasInitializer = false
        var index = keyword + 1
        while (index < tokens.size) {
            val token = tokens[index]
            if (depths[index] == 0) {
                if (token.type == KtTokens.SEMICOLON || token.type == KtTokens.RBRACE) break
                if (isOnNewLine(index) && !continuesPreviousLine(index)) {
                    val tokenColumn = columnOf(token.start)
                    val continues = when {
                        token.type == KtTokens.LBRACE -> !hasBody && !hasInitializer
                                && kind != DeclarationKind.PROPERTY && kind != DeclarationKind.TYPE_ALIAS
                        // the accessors of a property are indented
                        kind == DeclarationKind.PROPERTY && tokenColumn > column -> token.text in setOf("get", "set")
                                || (token.text in visibilityModifiers && tokens.getOrNull(index + 1)?.text == "set")
                        else -> false
                    }
                    if (!continues) break
                }
                if (token.type == KtTokens.EQ) hasInitializer = true
                if (token.type == KtTokens.LBRACE) hasBody = true
            }
            index++
        }
        return index - 1
    }

    private fun hasNestedDeclarations(
        bodyStart: Int,
        last: Int
    ): Boolean = (bodyStart + 1..last).any { index ->
        val token = tokens[index]
        depths[index] == 1 && tokens[index - 1].type != KtTokens.COLONCOLON && when (token.text) {
            "class", "interface", "typealias" -> true
            "object" -> tokens.getOrNull(index + 1)?.type == KtTokens.IDENTIFIER
            "companion" -> tokens.getOrNull(index + 1)?.text == "object"
            else -> false
        }
    }

    private fun isExtensionOrHasAccessor(
        keyword: Int,
        last: Int
    ): Boolean {
        var hasTypeOrInitializer = false
        var hasInitializer = false
        var angles = 0
        for (index in keyword + 1..last) {
            if (depths[index] != 0) continue
            val token = tokens[index]
            when (token.type) {
                KtTokens.LT -> angles++
                KtTokens.GT -> angles--
            }
            // the receiver of an extension, like in `val String.shouted`
            if (token.type == KtTokens.DOT && angles == 0 && !hasTypeOrInitializer) return true
            // the name of the property might be `get` or `set` as well
            if ((token.text == "get" || token.text == "set")
                && index != keyword + 1
                && tokens[index - 1].type != KtTokens.DOT
                && (isOnNewLine(index) || !hasInitializer)
            ) return true
            if (token.type == KtTokens.COLON || token.type == KtTokens.EQ || token.text == "by") {
                hasTypeOrInitializer = true
            }
            if (token.type == KtTokens.EQ) hasInitializer = true
        }
        return false
    }

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
        bodyStart: Int
    ): Set<ParsedDeclaration> {
        val named = declarations.filter { it.name != null && it.kind != DeclarationKind.PROPERTY }
        val namedByName = named.groupBy { it.name!! }
        val subtypesByName = mutableMapOf<String, MutableList<ParsedDeclaration>>()
        named.filter { it.kind != DeclarationKind.FUNCTION }.forEach { declaration ->
            declaration.headerIdentifiers.forEach { subtypesByName.getOrPut(it) { mutableListOf() } += declaration }
        }
        val references = declarations.associateWith { declaration ->
            buildSet {
                declaration.identifiers.forEach { addAll(namedByName[it].orEmpty()) }
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
            declaration.identifiers.any { it in localProperties }
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
        do {
            val accessing = accessingLocalProperties(moved)
            val added = moved.flatMap { references.getValue(it) }.filter { declaration ->
                declaration !in moved && (declaration.kind != DeclarationKind.FUNCTION || declaration !in accessing)
            }
            moved += added
        } while (added.isNotEmpty())
        val nameIndices = declarations.mapNotNull { it.nameIndex }.toSet()
        val firstReferences = mutableMapOf<String, Int>()
        (bodyStart until tokens.size).forEach {
            if (it !in nameIndices) firstReferences.putIfAbsent(tokens[it].text, it)
        }
        declarations.filter { declaration ->
            declaration.kind == DeclarationKind.FUNCTION
                    && (firstReferences[declaration.name] ?: Int.MAX_VALUE) < declaration.first
        }.forEach { function ->
            if (function !in moved && function !in accessingLocalProperties(moved)) {
                val queue = ArrayDeque(listOf(function))
                moved += function
                while (queue.isNotEmpty()) {
                    references.getValue(queue.removeFirst()).forEach {
                        if (moved.add(it)) queue += it
                    }
                }
            }
        }
        return moved.sortedBy { it.first }.toCollection(LinkedHashSet())
    }

}
