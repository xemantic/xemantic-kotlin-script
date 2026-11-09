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
 * while the name of the inlined function is given by the local variable marking its inlined body, like `$i$f$boom`.
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
     * the frame of the inlined function, followed by the frame of its outermost call site in the method of the [frame],
     * as the `KotlinDebug` stratum flattens nested inlining to the call site in the method,
     * which is the [frame] itself if the call site is unknown,
     * or `null` if the [frame] is not of a snippet, its line is a line of the snippet itself or unknown,
     * or the class of the [frame] cannot be read.
     */
    fun resolve(frame: StackTraceElement): List<StackTraceElement>? {
        val lines = snippets[frame.fileName] ?: return null
        // the class is not read for a frame which cannot be of inlined code
        if (frame.lineNumber <= lines.size) return null
        val snippetClass = snippetClass(frame.className) ?: return null
        val range = snippetClass.smap?.findRange(frame.lineNumber) ?: return null
        val inlined = range.mapDestToSource(frame.lineNumber)
        // the lines of the snippet itself are mapped to themselves
        if (inlined.file == frame.fileName && inlined.line == frame.lineNumber) return null
        val function = snippetClass.inlineFunction(frame.methodName, frame.lineNumber) ?: UNKNOWN_INLINE_FUNCTION
        val callSite = range.callSite
        return listOf(
            StackTraceElement(inlined.path.replace('/', '.'), function, inlined.file, inlined.line),
            if (callSite != null) StackTraceElement(frame.className, frame.methodName, callSite.file, callSite.line) else frame
        )
    }

    /**
     * The class of this [className] read so far, or read from the class loader of the latest snippet,
     * `null` if it cannot be read, which is not kept, as the class loader might not be known yet, or not load the class yet.
     *
     * @throws VirtualMachineError thrown by reading the class, other than [StackOverflowError].
     */
    private fun snippetClass(className: String): SnippetClass? = classes[className] ?: try {
        classLoader?.getResourceAsStream(className.replace('.', '/') + ".class")?.use { it.readBytes() }?.let { bytes ->
            SnippetClass(bytes).also { classes[className] = it }
        }
    } catch (e: Throwable) {
        if (e.isFatal) throw e
        logger.debug(e) { "cannot read the class $className of a frame" }
        null
    }

}

/** The SMAP of a snippet class, and its inline functions by the lines of their inlined bodies. */
private class SnippetClass(bytes: ByteArray) {

    /** The SMAP of the class, with the call sites of the `KotlinDebug` stratum, `null` if the class has none. */
    val smap: SMAP?

    /** The names of the inline functions by the method and the line of their inlined bodies, the innermost one of nested inlining. */
    private val inlineFunctions: Map<Pair<String, Int>, String>

    init {
        // the node is not kept, only what the frames need
        val node = ClassNode().also { ClassReader(bytes).accept(it, ClassReader.SKIP_FRAMES) }
        smap = node.sourceDebug?.let { SMAPParser.parseOrNull(it) }
        inlineFunctions = buildMap {
            node.methods.forEach { method -> method.collectInlineFunctions(this) }
        }
    }

    fun inlineFunction(method: String, line: Int): String? = inlineFunctions[method to line]

    private fun MethodNode.collectInlineFunctions(
        functions: MutableMap<Pair<String, Int>, String>
    ) {
        val markers = localVariables.orEmpty().filter {
            it.name.startsWith(INLINE_FUNCTION_MARKER) || it.name.startsWith(INLINE_LAMBDA_MARKER)
        }
        if (markers.isEmpty()) return
        val ranges = markers.map { Triple(it.name, instructions.indexOf(it.start), instructions.indexOf(it.end)) }
        instructions.filterIsInstance<LineNumberNode>().forEach { lineNumber ->
            val index = instructions.indexOf(lineNumber.start)
            ranges.filter { (_, start, end) -> index in start..<end }
                .maxWithOrNull(compareBy({ it.second }, { -it.third }))
                ?.let { functions.putIfAbsent(name to lineNumber.line, it.first.inlineFunctionName()) }
        }
    }

}

/** The name of the function of this inline marker variable, `<lambda>` for a lambda argument. */
private fun String.inlineFunctionName(): String =
    if (startsWith(INLINE_LAMBDA_MARKER)) INLINE_LAMBDA else removePrefix(INLINE_FUNCTION_MARKER)
