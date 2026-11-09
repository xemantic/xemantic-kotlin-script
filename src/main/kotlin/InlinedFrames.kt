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
import org.jetbrains.kotlin.codegen.inline.SMAP
import org.jetbrains.kotlin.codegen.inline.SMAPParser
import org.jetbrains.org.objectweb.asm.ClassReader
import org.jetbrains.org.objectweb.asm.tree.ClassNode
import org.jetbrains.org.objectweb.asm.tree.LineNumberNode
import org.jetbrains.org.objectweb.asm.tree.MethodNode

private val logger = KotlinLogging.logger {}

/** The prefix of the local variable marking the inlined body of a function, like `$i$f$boom`. */
private const val INLINE_FUNCTION_MARKER = "\$i\$f\$"

/** The prefix of the local variable marking the inlined body of a lambda argument, like `$i$a$-forEach-Snippet1$check$1`. */
private const val INLINE_LAMBDA_MARKER = "\$i\$a\$"

/** The path of the `fake.kt` file of the SMAP, which the compiler may give to the initialization of an inline marker variable. */
private const val FAKE_PATH = "kotlin/jvm/internal/FakeKt"

/** The function of inlined code without a marker in scope at its line. */
private const val UNKNOWN_INLINE_FUNCTION = "<inlined>"

/** The function of inlined code of a lambda argument, named like the lambda frames by `ScriptErrorReporter`. */
private const val INLINE_LAMBDA = "<lambda>"

/**
 * The frames of the code inlined into the methods of the classes of the snippets.
 *
 * The compiler gives the inlined code line numbers beyond the end of the snippet,
 * mapped back by the SMAP of the class, its `SourceDebugExtension` attribute of JSR-45:
 * the `Kotlin` stratum maps such a line to the line of the inlined function in its own file,
 * and the `KotlinDebug` stratum to the line of the call site in the method of the frame,
 * while the name of the inlined function is given by the local variable marking its inlined body, like `$i$f$boom`,
 * and the calls of nested inline functions by the lines in effect before the ranges of the nested markers.
 *
 * The class files are read from the class loader of the latest evaluated snippet, which loads the earlier ones as well.
 * Accessed on the thread of the session only, like the snippets.
 *
 * @param snippets the lines of the snippets by their names, like `snippet3.kts`.
 */
internal class InlinedFrames(
    private val snippets: Map<String, List<String>>
) {

    private var classLoader: ClassLoader? = null

    /** The classes of the frames read so far. */
    private val classes = HashMap<String, SnippetClass>()

    /** Keeps the class loader of the [scriptClass] of the latest evaluated snippet. */
    fun keep(scriptClass: Class<*>) {
        classLoader = scriptClass.classLoader
    }

    /**
     * The frames of the [frame] of inlined code, its line beyond the end of its snippet:
     * the frame of the inlined function, followed by the frames of the inline functions calling it, if nested,
     * and by the frame of the outermost call site in the method of the [frame],
     * which is the [frame] itself if the call site is unknown,
     * or `null` if the [frame] is not of a snippet, its line is a line of the snippet itself or unknown,
     * or the class of the [frame] cannot be read.
     *
     * The `KotlinDebug` stratum flattens nested inlining to the call site in the method,
     * so the calls of the nested inline functions are read from the ranges of their inline markers instead, see [SnippetClass.inlinedCalls].
     */
    fun resolve(frame: StackTraceElement): List<StackTraceElement>? {
        val lines = snippets[frame.fileName] ?: return null
        // the class is not read for a frame which cannot be of inlined code
        if (frame.lineNumber <= lines.size) return null
        val snippetClass = snippetClass(frame.className) ?: return null
        val smap = snippetClass.smap ?: return null
        val range = smap.findRange(frame.lineNumber) ?: return null
        val inlined = range.mapDestToSource(frame.lineNumber)
        // the lines of the snippet itself are mapped to themselves
        if (inlined.file == frame.fileName && inlined.line == frame.lineNumber) return null
        val calls = snippetClass.inlinedCalls(frame.methodName, frame.lineNumber)
        val inlinedFrames = calls?.functions?.mapNotNull { (function, line) ->
            smap.findRange(line)?.mapDestToSource(line)?.let {
                StackTraceElement(it.path.replace('/', '.'), function, it.file, it.line)
            }
        } ?: listOf(StackTraceElement(inlined.path.replace('/', '.'), UNKNOWN_INLINE_FUNCTION, inlined.file, inlined.line))
        // the line of the method calling the outermost inline function is more precise than the call site of the KotlinDebug stratum,
        // which is the line of a lambda of the method, if the inlined code is called by it
        val callSite = calls?.methodLine?.takeIf { it in 1..lines.size }
            ?: range.callSite?.line
        return inlinedFrames + if (callSite != null) StackTraceElement(frame.className, frame.methodName, frame.fileName, callSite) else frame
    }

    /**
     * The class of this [className] read so far, or read from the class loader of the latest snippet,
     * `null` if it cannot be read, which is not kept, as the class loader might not be known yet, or not load the class yet.
     *
     * @throws VirtualMachineError thrown by reading the class, other than [StackOverflowError].
     */
    private fun snippetClass(className: String): SnippetClass? = classes[className]
        ?: logger.readOr(null, { "cannot read the class $className of a frame" }) {
            classLoader?.getResourceAsStream(className.replace('.', '/') + ".class")?.use { it.readBytes() }?.let { bytes ->
                SnippetClass(bytes).also { classes[className] = it }
            }
        }

}

/**
 * The calls of nested inline functions inlined at a line of a method, see [SnippetClass.inlinedCalls].
 *
 * @param functions the inline functions, innermost first, each with the line of the method in effect within it:
 * the line of the frame within the innermost one, the line of its call within the next one, and so on.
 * @param methodLine the line of the method calling the outermost inline function, `null` if unknown.
 */
private class InlinedCalls(
    val functions: List<Pair<String, Int>>,
    val methodLine: Int?
)

/** The inline marker variable of this [name], in scope from the instruction at the [start] index until the [end] one. */
private class InlineMarker(
    val name: String,
    val start: Int,
    val end: Int
) {
    operator fun contains(index: Int): Boolean = index in start..<end
}

/** The [markers] of the inlined bodies of a method, and its line numbers by their instruction indices, in the order of the instructions. */
private class InlinedMethod(
    val markers: List<InlineMarker>,
    val lines: List<Pair<Int, Int>>
)

/** The SMAP of a snippet class, and the inline markers and the lines of its methods. */
private class SnippetClass(bytes: ByteArray) {

    /** The SMAP of the class, with the call sites of the `KotlinDebug` stratum, `null` if the class has none. */
    val smap: SMAP?

    /** The methods with inlined bodies by their names, several of overloads. */
    private val methods: Map<String, List<InlinedMethod>>

    init {
        // the node is not kept, only what the frames need
        val node = ClassNode().also { ClassReader(bytes).accept(it, ClassReader.SKIP_FRAMES) }
        smap = node.sourceDebug?.let { SMAPParser.parseOrNull(it) }
        methods = node.methods.mapNotNull { method -> method.inlinedMethod()?.let { method.name to it } }
            .groupBy({ it.first }, { it.second })
    }

    /** The calls of the nested inline functions inlined at the [line] of the [method], of its first overload inlining at the [line]. */
    fun inlinedCalls(method: String, line: Int): InlinedCalls? =
        methods[method]?.firstNotNullOfOrNull { it.inlinedCalls(line) }

    /**
     * The calls of the nested inline functions inlined at the [line] of this method, the innermost one named by the innermost marker in scope
     * at the first instruction of the [line], as the same line may be inlined in several scopes,
     * and each enclosing one calling it at its own line in effect right before the start of the nested scope,
     * not a line of another scope nested in it, like of an inline function called before, or of a lambda passed to it,
     * nor the `fake.kt` line, which the compiler may give to the initialization of an inline marker variable.
     * The line of an enclosing scope not found, like of an inline function without a line before the call of the nested one,
     * gives it no frame, and the call of the outermost scope gives the [InlinedCalls.methodLine].
     *
     * A lambda argument is a scope as well, called by the inline function receiving it, and calling the nested one in its body,
     * while an `@InlineOnly` function of the standard library, like `require`, has no marker, its body inlined with the lines of its call site.
     *
     * @return `null` if the [line] is not in scope of any marker.
     */
    private fun InlinedMethod.inlinedCalls(line: Int): InlinedCalls? {
        val index = lines.firstOrNull { it.first == line }?.second ?: return null
        val scopes = markers.filter { index in it }.sortedWith(
            compareByDescending<InlineMarker> { it.start }.thenBy { it.end }
        ).takeIf { it.isNotEmpty() } ?: return null
        val functions = buildList {
            add(scopes.first().name.inlineFunctionName() to line)
            scopes.zipWithNext { nested, scope ->
                lineBefore(nested.start, scope)?.let { add(scope.name.inlineFunctionName() to it) }
            }
        }
        return InlinedCalls(functions, lineBefore(scopes.last().start, scope = null))
    }

    /**
     * The line in effect within the [scope], the method itself if `null`, right before the instruction at the [index],
     * not a line of another scope nested in it, nor a `fake.kt` line.
     */
    private fun InlinedMethod.lineBefore(index: Int, scope: InlineMarker?): Int? {
        val from = scope?.start ?: 0
        return lines.lastOrNull { (line, lineIndex) ->
            lineIndex in from..<index
                && markers.none { it !== scope && it.start >= from && lineIndex in it }
                && smap?.findRange(line)?.mapDestToSource(line)?.path != FAKE_PATH
        }?.first
    }

}

/** The [InlinedMethod] of this method, `null` if it has no inline markers. */
private fun MethodNode.inlinedMethod(): InlinedMethod? {
    val markers = localVariables.orEmpty().filter {
        it.name.startsWith(INLINE_FUNCTION_MARKER) || it.name.startsWith(INLINE_LAMBDA_MARKER)
    }.map { InlineMarker(it.name, instructions.indexOf(it.start), instructions.indexOf(it.end)) }
    if (markers.isEmpty()) return null
    val lines = instructions.filterIsInstance<LineNumberNode>().map { it.line to instructions.indexOf(it.start) }
    return InlinedMethod(markers, lines)
}

/** The name of the function of this inline marker variable, `<lambda>` for a lambda argument. */
private fun String.inlineFunctionName(): String =
    if (startsWith(INLINE_LAMBDA_MARKER)) INLINE_LAMBDA else removePrefix(INLINE_FUNCTION_MARKER)
