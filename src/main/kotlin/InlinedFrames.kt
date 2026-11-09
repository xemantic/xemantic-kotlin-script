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
import org.jetbrains.org.objectweb.asm.ClassReader
import org.jetbrains.org.objectweb.asm.tree.ClassNode
import org.jetbrains.org.objectweb.asm.tree.LineNumberNode
import org.jetbrains.org.objectweb.asm.tree.MethodNode

private val logger = KotlinLogging.logger {}

/** The prefix of the local variable marking the inlined body of a function, like `$i$f$boom`. */
private const val INLINE_FUNCTION_MARKER = "\$i\$f\$"

/** The function of inlined code without a marker in scope at its line. */
private const val UNKNOWN_INLINE_FUNCTION = "<inlined>"

private const val KOTLIN_STRATUM = "Kotlin"

private const val KOTLIN_DEBUG_STRATUM = "KotlinDebug"

/** A line mapping of an SMAP, like `2#1,3:37,2`: the input start line, with the optional file id and repeat count, the output start line, with the optional increment. */
private val lineMapping = Regex("(\\d+)(?:#(\\d+))?(?:,(\\d+))?:(\\d+)(?:,(\\d+))?")

/** A file of an SMAP, like `+ 1 snippet1.kts`, where the `+` marks the path following on the next line. */
private val fileInfo = Regex("(\\+ )?(\\d+) (.+)")

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
 */
internal class InlinedFrames {

    private var classLoader: ClassLoader? = null

    /** The classes of the frames resolved so far, `null` if the class file could not be read. */
    private val classes = HashMap<String, SnippetClass?>()

    /** Keeps the class loader of the [scriptClass] of the latest evaluated snippet. */
    fun keep(scriptClass: Class<*>) {
        classLoader = scriptClass.classLoader
    }

    /**
     * The frames of the [frame] with a line beyond the end of its snippet:
     * the frame of the inlined function, followed by the frame of the call site,
     * which is the [frame] itself if the call site is unknown,
     * or `null` if the line is not mapped by the class of the [frame].
     */
    fun resolve(frame: StackTraceElement): List<StackTraceElement>? {
        // not getOrPut, which would read again a class which could not be read
        val snippetClass = (if (frame.className in classes) classes[frame.className] else read(frame.className).also {
            classes[frame.className] = it
        }) ?: return null
        val inlined = snippetClass.kotlin?.map(frame.lineNumber) ?: return null
        val function = snippetClass.inlineFunction(frame.methodName, frame.lineNumber) ?: UNKNOWN_INLINE_FUNCTION
        val callSite = snippetClass.kotlinDebug?.map(frame.lineNumber)
        return listOf(
            StackTraceElement(inlined.path?.replace('/', '.') ?: frame.className, function, inlined.file, inlined.line),
            if (callSite != null) StackTraceElement(frame.className, frame.methodName, callSite.file, callSite.line) else frame
        )
    }

    /**
     * Reads the class of this [className] from the class loader of the latest snippet.
     *
     * @throws VirtualMachineError thrown by reading the class, other than [StackOverflowError].
     */
    private fun read(className: String): SnippetClass? = try {
        classLoader?.getResourceAsStream(className.replace('.', '/') + ".class")?.use { it.readBytes() }?.let { bytes ->
            val node = ClassNode()
            ClassReader(bytes).accept(node, ClassReader.SKIP_FRAMES)
            SnippetClass(node)
        }
    } catch (e: Throwable) {
        if (e.isFatal) throw e
        logger.debug(e) { "cannot read the class $className of a frame" }
        null
    }

}

/** The strata of the SMAP of a snippet class, and the inline functions by the lines of their inlined bodies. */
private class SnippetClass(node: ClassNode) {

    private val strata = node.sourceDebug?.let { parseSmap(it) }.orEmpty()

    val kotlin: Stratum? = strata[KOTLIN_STRATUM]

    val kotlinDebug: Stratum? = strata[KOTLIN_DEBUG_STRATUM]

    /** The names of the inline functions by the method and the line of their inlined bodies, the innermost one of nested inlining. */
    private val inlineFunctions: Map<Pair<String, Int>, String> = buildMap {
        node.methods.forEach { method -> method.collectInlineFunctions(this) }
    }

    fun inlineFunction(method: String, line: Int): String? = inlineFunctions[method to line]

    private fun MethodNode.collectInlineFunctions(
        functions: MutableMap<Pair<String, Int>, String>
    ) {
        val markers = localVariables.orEmpty().filter { it.name.startsWith(INLINE_FUNCTION_MARKER) }
        if (markers.isEmpty()) return
        val ranges = markers.map { Triple(it.name, instructions.indexOf(it.start), instructions.indexOf(it.end)) }
        instructions.filterIsInstance<LineNumberNode>().forEach { lineNumber ->
            val index = instructions.indexOf(lineNumber.start)
            ranges.filter { (_, start, end) -> index in start..<end }
                .maxWithOrNull(compareBy({ it.second }, { -it.third }))
                ?.let { functions.putIfAbsent(name to lineNumber.line, it.first.removePrefix(INLINE_FUNCTION_MARKER)) }
        }
    }

}

/** A location in the source [file], like `snippet1.kts`, of the class of the [path], like `Snippet1`, if given. */
private class Location(
    val file: String,
    val path: String?,
    val line: Int
)

/** A stratum of an SMAP, its files by the ids, and its line mappings. */
private class Stratum(
    private val files: Map<Int, Pair<String, String?>>,
    private val lines: List<LineMapping>
) {

    /** The source location of the output [line], or `null` if it is not mapped. */
    fun map(line: Int): Location? = lines.firstNotNullOfOrNull { it.map(line) }?.let { (fileId, inputLine) ->
        files[fileId]?.let { (file, path) -> Location(file, path, inputLine) }
    }

}

/** A line mapping of an SMAP: the [repeat] input lines from the [inputStart] of the [fileId], each to [increment] output lines from the [outputStart]. */
private class LineMapping(
    private val inputStart: Int,
    private val fileId: Int,
    private val repeat: Int,
    private val outputStart: Int,
    private val increment: Int
) {

    /** The file id and the input line of the [output] line, or `null` if it is outside this mapping. */
    fun map(output: Int): Pair<Int, Int>? {
        val offset = output - outputStart
        if (offset < 0 || offset >= repeat * increment) return null
        return fileId to inputStart + offset / increment
    }

}

/** The strata of the [smap] by their names, like `Kotlin`, empty if it is not an SMAP. */
private fun parseSmap(smap: String): Map<String, Stratum> {
    val lines = smap.lines()
    if (lines.firstOrNull() != "SMAP") return emptyMap()
    val strata = HashMap<String, Stratum>()
    var stratum: String? = null
    var files = HashMap<Int, Pair<String, String?>>()
    var mappings = ArrayList<LineMapping>()
    var section: Char? = null
    var lastFileId = 1
    fun endStratum() {
        stratum?.let { strata[it] = Stratum(files, mappings) }
    }
    var index = 1
    while (index < lines.size) {
        val line = lines[index++]
        when {
            line.startsWith("*S ") -> {
                endStratum()
                stratum = line.substring(3).trim()
                files = HashMap()
                mappings = ArrayList()
                section = null
            }
            line == "*E" -> {
                endStratum()
                return strata
            }
            line.startsWith("*") -> section = line.getOrNull(1)
            stratum == null -> {}
            section == 'F' -> fileInfo.matchEntire(line)?.let { match ->
                val (plus, id, name) = match.destructured
                val path = if (plus.isNotEmpty() && index < lines.size) lines[index++] else null
                files[id.toInt()] = name to path
            }
            section == 'L' -> lineMapping.matchEntire(line)?.let { match ->
                val (inputStart, fileId, repeat, outputStart, increment) = match.destructured
                lastFileId = fileId.toIntOrNull() ?: lastFileId
                mappings += LineMapping(
                    inputStart = inputStart.toInt(),
                    fileId = lastFileId,
                    repeat = repeat.toIntOrNull() ?: 1,
                    outputStart = outputStart.toInt(),
                    increment = increment.toIntOrNull() ?: 1
                )
            }
        }
    }
    endStratum()
    return strata
}
