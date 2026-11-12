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
import org.jetbrains.kotlin.codegen.inline.SourcePosition
import org.jetbrains.org.objectweb.asm.ClassReader
import org.jetbrains.org.objectweb.asm.ClassVisitor
import org.jetbrains.org.objectweb.asm.Opcodes
import org.jetbrains.org.objectweb.asm.tree.AbstractInsnNode
import org.jetbrains.org.objectweb.asm.tree.ClassNode
import org.jetbrains.org.objectweb.asm.tree.FieldInsnNode
import org.jetbrains.org.objectweb.asm.tree.InsnNode
import org.jetbrains.org.objectweb.asm.tree.IntInsnNode
import org.jetbrains.org.objectweb.asm.tree.InvokeDynamicInsnNode
import org.jetbrains.org.objectweb.asm.tree.LineNumberNode
import org.jetbrains.org.objectweb.asm.tree.LookupSwitchInsnNode
import org.jetbrains.org.objectweb.asm.tree.MethodInsnNode
import org.jetbrains.org.objectweb.asm.tree.MethodNode
import org.jetbrains.org.objectweb.asm.tree.MultiANewArrayInsnNode
import org.jetbrains.org.objectweb.asm.tree.TableSwitchInsnNode
import org.jetbrains.org.objectweb.asm.tree.TypeInsnNode
import java.util.BitSet

private val logger = KotlinLogging.logger {}

/** The prefix of the local variable marking the inlined body of a function, like `$i$f$boom`. */
private const val INLINE_FUNCTION_MARKER = "\$i\$f\$"

/** The prefix of the local variable marking the inlined body of a lambda argument, like `$i$a$-forEach-Snippet1$check$1`. */
private const val INLINE_LAMBDA_MARKER = "\$i\$a\$"

/** The path of the `fake.kt` file of the SMAP, which the compiler may give to the initialization of an inline marker variable. */
private const val FAKE_PATH = "kotlin/jvm/internal/FakeKt"

/** The field of the continuation of a suspend function, which its state machine switches on to resume. */
private const val STATE_MACHINE_LABEL = "label"

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

    /** The names of the classes of the frames which cannot be read by the class loader of the latest snippet, until the next one is kept. */
    private val unreadableClasses = HashSet<String>()

    /**
     * The analyses of the frames so far, by the class, the method and the line of a frame,
     * wrapped, so that an analysis resolving no frames, or failing, is told apart from a frame not analyzed yet.
     */
    private val analyses = HashMap<FrameLine, Analysis>()

    /** Called before each analysis of a frame of inlined code, a seam of the tests of its memoization and its failure. */
    internal var analyzing: (frame: StackTraceElement) -> Unit = {}

    /** Keeps the class loader of the [scriptClass] of the latest evaluated snippet. */
    fun keep(scriptClass: Class<*>) {
        classLoader = scriptClass.classLoader
        unreadableClasses.clear()
    }

    /**
     * The frames of the [frame] of inlined code, its line beyond the end of its snippet, or within a lambda inlined into it, see [resolveLambda]:
     * the frame of the inlined function, followed by the frames of the inline functions calling it, if nested,
     * and by the frame of the outermost call site in the method of the [frame],
     * which is the [frame] itself if the call site is unknown,
     * or `null` if the [frame] is not of a snippet, its line is unknown, or a line of the snippet itself, not inlined,
     * or the class of the [frame] cannot be read, or its bytecode cannot be analyzed.
     *
     * The body of a lambda passed to an inline function keeps the lines of the snippet,
     * so the class is read for the frame of any line of the snippet, which is resolved if the line lies within an inlined lambda.
     *
     * The `KotlinDebug` stratum flattens nested inlining to the call site in the method,
     * so the calls of the nested inline functions are read from the ranges of their inline markers instead, see [SnippetClass.inlinedCalls].
     * The frames are memoized by the class, the method and the line of the [frame],
     * as a recursion through inlined code, like the one overflowing the stack, repeats the same frame many times,
     * and so is a failure of the analysis on unexpected bytecode, which would fail again,
     * but not a [StackOverflowError], which depends on the depth of the stack the analysis starts on, not on the bytecode.
     *
     * @throws VirtualMachineError thrown by the analysis, other than [StackOverflowError].
     */
    fun resolve(frame: StackTraceElement): List<StackTraceElement>? {
        val lines = snippets[frame.fileName] ?: return null
        // the class is not read for a frame without a line
        if (frame.lineNumber < 1) return null
        val snippetClass = snippetClass(frame.className) ?: return null
        val key = FrameLine(frame.className, frame.methodName, frame.lineNumber)
        analyses[key]?.let { return it.frames }
        val analysis = try {
            analyzing(frame)
            Analysis(snippetClass.resolve(frame, lines.size))
        } catch (e: Throwable) {
            if (e.isFatal) throw e
            // a failure on unexpected bytecode loses only the frames of the inlined code, not the report of the error
            logger.debug(e) { "cannot resolve the inlined frames of $frame" }
            if (e is StackOverflowError) return null
            Analysis(frames = null)
        }
        analyses[key] = analysis
        return analysis.frames
    }

    /** The frames of the [frame] of the code inlined into this class, see [resolve]. */
    private fun SnippetClass.resolve(frame: StackTraceElement, snippetSize: Int): List<StackTraceElement>? {
        if (frame.lineNumber <= snippetSize) return resolveLambda(frame)
        val smap = smap ?: return null
        val range = smap.findRange(frame.lineNumber) ?: return null
        val inlined = range.mapDestToSource(frame.lineNumber)
        // the lines of the snippet itself are mapped to themselves
        if (inlined.file == frame.fileName && inlined.line == frame.lineNumber) return null
        // the line the compiler may give to the initialization of an inline marker variable, which cannot throw
        if (inlined.path == FAKE_PATH) return null
        // the code of a line out of the scope of every marker, like the prologue of an inline function called by the method itself
        val calls = inlinedCalls(frame.methodName, frame.lineNumber)
            ?: InlinedCalls(listOf((prologueOf(frame.lineNumber) ?: UNKNOWN_INLINE_FUNCTION) to frame.lineNumber), methodLine = null)
        if (calls === OUT_OF_SCOPES) return null
        val inlinedFrames = calls.functions.mapNotNull { (function, line) -> smap.frameOf(function, line) }
        val debugCallSite = range.callSite?.line
        // the call site of the KotlinDebug stratum is the line of a lambda of the method, if the inlined code is called by it,
        // and only then the line of the method in effect before the outermost inline function is preferred
        val callSite = calls.methodLine?.takeIf { methodLine ->
            methodLine in 1..snippetSize && inlinedFrames.any {
                it.methodName == INLINE_LAMBDA && it.fileName == frame.fileName && it.lineNumber == debugCallSite
            }
        } ?: debugCallSite
        return inlinedFrames + if (callSite != null) StackTraceElement(frame.className, frame.methodName, frame.fileName, callSite) else frame
    }

    /**
     * The frames of the [frame] at a line of the snippet within the body of a lambda passed to an inline function:
     * the frame of the lambda, followed by the frame of the inline function calling it,
     * and by the frames of the enclosing inline functions and lambdas, if nested,
     * and by the frame of the outermost call site in the method of the [frame],
     * told by the `KotlinDebug` stratum of the line of the outermost inline function,
     * or `null` if the line is not within such a lambda, or it is not known which code of the line throws,
     * see [SnippetClass.lambdaCalls].
     *
     * Like the frames of a line beyond the end of the snippet, an enclosing frame of a line the SMAP does not map is omitted,
     * and the [frame] itself stands for the call site the `KotlinDebug` stratum does not tell.
     * The copies of the same lambda, like of a lambda inlined twice, or of a `finally` block duplicated by the compiler,
     * share the line of the frame, so their frames must agree, or only the frame of the lambda and of the call site are given,
     * like the frames of an inline function inlined twice, see [SnippetClass.inlinedCalls].
     */
    private fun SnippetClass.resolveLambda(frame: StackTraceElement): List<StackTraceElement>? {
        val smap = smap ?: return null
        val copies = lambdaCalls(frame.methodName, frame.lineNumber) ?: return null
        val resolved = copies.map { functions ->
            val (lambda, line) = functions.first()
            val lambdaFrame = smap.frameOf(lambda, line) ?: return null
            val callSite = smap.findRange(functions.last().second)?.callSite?.line
            listOf(lambdaFrame) + functions.drop(1).mapNotNull { (function, line) -> smap.frameOf(function, line) } +
                if (callSite != null) StackTraceElement(frame.className, frame.methodName, frame.fileName, callSite) else frame
        }.distinct()
        return resolved.singleOrNull()
            ?: listOf(resolved.first().first(), resolved.map { it.last() }.distinct().singleOrNull() ?: frame)
    }

    /**
     * The class of this [className] read so far, or read from the class loader of the latest snippet,
     * `null` if it cannot be read, which is kept only until the next snippet, see [keep],
     * as the class loader might not be known yet, or not load the class yet.
     *
     * @throws VirtualMachineError thrown by reading the class, other than [StackOverflowError].
     */
    private fun snippetClass(className: String): SnippetClass? {
        classes[className]?.let { return it }
        if (className in unreadableClasses) return null
        val snippetClass = logger.readOr(null, { "cannot read the class $className of a frame" }) {
            classLoader?.getResourceAsStream(className.replace('.', '/') + ".class")?.use { it.readBytes() }?.let { bytes ->
                SnippetClass(bytes, ::declarationsOf).also { classes[className] = it }
            }
        }
        if (snippetClass == null) unreadableClasses += className
        return snippetClass
    }

    /**
     * The declarations of the class of this [position] of inlined code, see [Declarations],
     * `null` if it is not a class of a snippet, like of the standard library, or it cannot be read.
     *
     * @throws VirtualMachineError thrown by reading the class, other than [StackOverflowError].
     */
    private fun declarationsOf(position: SourcePosition): Declarations? =
        if (position.file in snippets) snippetClass(position.path.replace('/', '.'))?.declarations else null

}

/** The line of a method of a class, of the frames analyzed, see [InlinedFrames.resolve]. */
private data class FrameLine(val className: String, val method: String, val line: Int)

/** The [frames] resolved by an analysis of a frame, `null` if none, see [InlinedFrames.resolve]. */
private class Analysis(val frames: List<StackTraceElement>?)

/**
 * The calls of a line whose instructions which may throw are all out of the scopes of its markers, see [THROWS_OUT_OF_SCOPES],
 * so that it gives no frames, unlike a line without a marker in scope.
 */
private val OUT_OF_SCOPES = InlinedCalls(emptyList(), methodLine = null)

/**
 * The calls of nested inline functions inlined at a line of a method, see [SnippetClass.inlinedCalls].
 *
 * @param functions the inline functions, innermost first, each with the line of the method in effect within it:
 * the line of the frame within the innermost one, the line of its call within the next one, and so on.
 * @param methodLine the line of the method calling the outermost inline function, `null` if unknown.
 */
private data class InlinedCalls(
    val functions: List<Pair<String, Int>>,
    val methodLine: Int?
)

/**
 * A range of the inline marker variable of this [name] in this [slot], in scope from the instruction at the [start] index until the [end] one.
 *
 * The ranges of the same name and slot are not merged, as they are also of the copies of an inline function called twice by another one,
 * while a single inlined body may be split into several ranges as well, see [SnippetClass.callLine].
 */
private class InlineMarker(
    val name: String,
    val slot: Int,
    val start: Int,
    val end: Int
) {
    operator fun contains(index: Int): Boolean = index in start..<end

    /** Whether this marker is of a lambda argument, not of an inline function. */
    val isLambda: Boolean get() = name.startsWith(INLINE_LAMBDA_MARKER)
}

/**
 * The [markers] of the inlined bodies of a method, and its line numbers in the order of the instructions:
 * the [lineNumbers], the [lineStarts], the indices of the instructions starting them,
 * of the [instructionCount] instructions of the method, of which the [throwing] ones may throw, see [mayThrow],
 * and the [allocations], the indices of the constructor calls of the `NEW` instructions by their indices,
 * and the [resumeLabels], the indices of the labels the state machine of a suspend function resumes at,
 * the targets of a switch on a field named `label`, so also of a `when` on such a field accessed directly, which is not told apart.
 * The scopes of the lines are computed on the first frame of the method resolved.
 */
private class InlinedMethod(
    val markers: List<InlineMarker>,
    val lineNumbers: IntArray,
    val lineStarts: IntArray,
    private val throwing: BitSet,
    private val allocations: Map<Int, Int>,
    val resumeLabels: BitSet,
    private val instructionCount: Int
) {

    /**
     * The index of the instruction telling the scope of each line:
     * the first instruction which may throw while the line is in effect, until the start of the next line,
     * or the start of the line, if none may throw.
     *
     * The line may start outside the scope of the code it is given to,
     * like at the label of the state machine of a suspend function resuming into an inlined body, before the initialization of its marker.
     *
     * A later instruction of the line which may throw in another scope does not make the line ambiguous for an inlined call, see [SnippetClass.inlinedCalls]:
     * in an enclosing scope, it is the code following a nested inlined body without a line of its own,
     * like the unboxing of its result, or an exception handler of the method,
     * so treating it as ambiguous would lose the frames of about any inline function throwing at its end;
     * in a nested scope, it can only be the body of a lambda on the same line, as an inline function has lines of its own,
     * and the compiler starts the body of a lambda with a line of its own —
     * even if it did not, the frames would only miss the lambda and the function calling it, not name a wrong one.
     */
    val linePositions: IntArray get() = lines.positions

    /**
     * Whether the instructions of each line may throw: [THROWS_NOWHERE], [THROWS_IN_ONE_SCOPE], [THROWS_IN_SCOPES], or [THROWS_OUT_OF_SCOPES].
     *
     * A `NEW` is not considered on its own: the compiler may allocate an object before the line of the code creating it starts,
     * like the exception of a lambda starting with `error("…")`, allocated within the line of the inline function calling the lambda,
     * or the exception of `error("…")` following an inline function, allocated within the line of the closing brace of its inlined body —
     * it is in the scope of the constructor called after it, see [allocationOutOfScopes].
     */
    val lineThrows: ByteArray get() = lines.throws

    /** The markers in scope at the [linePositions] of each line, innermost first. */
    val lineScopes: Array<List<InlineMarker>> get() = lines.scopes

    private val lines: Lines by lazy(LazyThreadSafetyMode.NONE) {
        val positions = IntArray(lineStarts.size)
        val throws = ByteArray(lineStarts.size)
        val scopes = Array(lineStarts.size) { line ->
            val range = lineRange(line)
            val throwingIndices = range.filter { throwing[it] }
            val throwingScopes = throwingIndices.map { scopesAt(it) }.distinct()
            positions[line] = throwingIndices.firstOrNull() ?: range.first
            throws[line] = when {
                throwingScopes.size > 1 -> THROWS_IN_SCOPES
                throwingScopes.size == 1 -> THROWS_IN_ONE_SCOPE
                allocationOutOfScopes(range) -> THROWS_OUT_OF_SCOPES
                else -> THROWS_NOWHERE
            }
            throwingScopes.firstOrNull() ?: scopesAt(range.first)
        }
        Lines(positions, throws, scopes)
    }

    /** The [positions], the [throws] and the [scopes] of the lines, computed in a single pass. */
    private class Lines(val positions: IntArray, val throws: ByteArray, val scopes: Array<List<InlineMarker>>)

    /**
     * Whether an object allocated within the [range] of a line, by a `NEW`, is created by the code of another line out of the scopes of the line:
     * a marker in scope at its start is not in scope at the constructor call, so the code of the line ended before it.
     * An allocation by the code of a scope nested in the line, like of a lambda called on it, is in the scopes of the line,
     * which the frame of the line, thrown by the allocation, is reported in.
     */
    private fun allocationOutOfScopes(range: IntRange): Boolean = allocations.any { (new, init) ->
        new in range && init !in range && markers.any { range.first in it && init !in it }
    }

    /**
     * Whether each line is a line of the prologue of an inline function, positioned before its marker:
     * the evaluation of a default argument, of a call of its `$default` method, is inlined with a line of the function before its marker,
     * the line also positioned directly in the scope of the function, not in a lambda nested in it —
     * the lines of each copy of an inline function are its own, while a lambda on the line of its call shares it, nested in the function.
     * The prologue is positioned directly in the scopes enclosing the function, unlike an earlier copy of the same function,
     * like of a function inlined twice, of a duplicated `finally` block, or a range of an inlined body split by a suspension point.
     * The prologue of a function declaring its default arguments on a line its body does not use, or whose body is dead code, without a marker,
     * is not told apart by the positions, but by the lines the functions declare, see [SnippetClass.isLineOf].
     */
    private val prologues: BitSet by lazy(LazyThreadSafetyMode.NONE) {
        val entriesByLine = lineNumbers.indices.groupBy { lineNumbers[it] }
        BitSet(lineNumbers.size).apply {
            lineNumbers.indices.forEach { line ->
                val prologue = linePositions[line]
                val isPrologue = markers.any { function ->
                    !function.isLambda && prologue < function.start
                        && markers.all { prologue !in it || function.start in it }
                        && entriesByLine.getValue(lineNumbers[line]).any { j ->
                            val position = linePositions[j]
                            j != line && position in function
                                && markers.none { it !== function && it.start >= function.start && position in it }
                        }
                }
                if (isPrologue) set(line)
            }
        }
    }

    /** Whether the line of the [line] index is a line of the prologue of an inline function, see [prologues]. */
    fun isPrologue(line: Int): Boolean = prologues[line]

    /**
     * Whether an instruction of the [line] index may throw, other than only by an allocation of the code of another line, see [lineThrows],
     * also out of every marker, which tells that the line is not of a lambda alone, see [SnippetClass.lambdaCalls].
     */
    fun throwsByItsCode(line: Int): Boolean =
        lineThrows[line] == THROWS_IN_ONE_SCOPE || lineThrows[line] == THROWS_IN_SCOPES

    /** The markers in scope at the instruction of this [position], innermost first. */
    private fun scopesAt(position: Int): List<InlineMarker> =
        markers.filter { position in it }.sortedWith(
            compareByDescending<InlineMarker> { it.start }.thenBy { it.end }.thenByDescending { it.slot }
        )

    /** The indices of the instructions of the [line] index, until the start of the next line. */
    private fun lineRange(line: Int): IntRange {
        val start = lineStarts[line]
        val until = (line + 1..<lineStarts.size).firstNotNullOfOrNull { next ->
            lineStarts[next].takeIf { it > start }
        } ?: instructionCount
        return start..<until
    }

}

/** No instruction of the line may throw, see [InlinedMethod.lineThrows]. */
private const val THROWS_NOWHERE: Byte = 0

/** The instructions of the line which may throw are all in scope of the same markers, see [InlinedMethod.lineThrows]. */
private const val THROWS_IN_ONE_SCOPE: Byte = 1

/** The instructions of the line which may throw are in different scopes, see [InlinedMethod.lineThrows]. */
private const val THROWS_IN_SCOPES: Byte = 2

/**
 * Only an allocation of an object created by the code of another line, out of the scopes of the line, may throw,
 * an [OutOfMemoryError], see [InlinedMethod.allocationOutOfScopes]:
 * its frame is not of the code of the line, so it is not resolved, unlike the frame of a line which cannot throw.
 */
private const val THROWS_OUT_OF_SCOPES: Byte = 3

/**
 * The SMAP of a snippet class, and the inline markers and the lines of its methods.
 *
 * They are kept for the session, rather than a small map of the frames of every line precomputed on reading the class,
 * as only the lines of the frames reported are analyzed, on demand, while precomputing would analyze every line of every method with a marker —
 * and they take only the arrays of the line numbers of these methods, and their markers, of the classes of the frames reported.
 * The methods of a class without an SMAP, which has no inlined code, are not read.
 */
private class SnippetClass(
    bytes: ByteArray,
    private val declarationsOf: (SourcePosition) -> Declarations?
) {

    /** The SMAP of the class, with the call sites of the `KotlinDebug` stratum, `null` if the class has none. */
    val smap: SMAP?

    /** The declarations of the class, read only once a frame of inlined code is positioned in it, see [Declarations]. */
    val declarations: Declarations by lazy(LazyThreadSafetyMode.NONE) {
        val node = ClassNode().also { ClassReader(bytes).accept(it, ClassReader.SKIP_FRAMES) }
        Declarations(node.methods, ::isMethodLine)
    }

    /** The methods with inlined bodies by their names, several of overloads. */
    private val methods: Map<String, List<InlinedMethod>>

    /** The lines of the methods without inlined bodies by their names, which might be overloads of the [methods]. */
    private val linesWithoutMarkers: Map<String, Set<Int>>

    /** The path of the snippet in the SMAP, of its first line, which is never inlined code. */
    private val snippetPath: String? by lazy(LazyThreadSafetyMode.NONE) { smap?.sourceOf(1)?.path }

    init {
        val reader = ClassReader(bytes)
        val sourceDebug = SourceDebugVisitor().also { reader.accept(it, ClassReader.SKIP_CODE or ClassReader.SKIP_FRAMES) }.debug
        smap = sourceDebug?.let { SMAPParser.parseOrNull(it) }
        if (smap == null) {
            methods = emptyMap()
            linesWithoutMarkers = emptyMap()
        } else {
            // the node is not kept, only what the frames need
            val node = ClassNode().also { reader.accept(it, ClassReader.SKIP_FRAMES) }
            val inlinedMethods = node.methods.map { it to it.inlinedMethod() }
            methods = inlinedMethods.mapNotNull { (method, inlined) -> inlined?.let { method.name to it } }
                .groupBy({ it.first }, { it.second })
            linesWithoutMarkers = inlinedMethods.filter { it.second == null }
                .groupBy({ it.first.name }) { (method, _) -> method.instructions.filterIsInstance<LineNumberNode>().map { it.line } }
                .mapValues { (_, lines) -> lines.flatten().toSet() }
        }
    }

    /**
     * The calls of the lambda and of the inline functions enclosing the [line] of the snippet in the [method], of each copy of the lambda,
     * see [lambdaCalls], in the only method of its name with an instruction at the [line] which may throw, see [InlinedMethod.lineThrows],
     * also only by an allocation out of the scopes of the line, as its frame would tell the same line,
     * while no overload without inlined bodies has the [line].
     */
    fun lambdaCalls(method: String, line: Int): List<List<Pair<String, Int>>>? {
        if (linesWithoutMarkers[method]?.contains(line) == true) return null
        return methods[method].orEmpty().filter { it.mayThrowAt(line) }.singleOrNull()?.lambdaCalls(line)
    }

    /**
     * The inline function whose default arguments are evaluated at this destination [line] of the class, see [Declarations.prologueOf],
     * `null` if none, or if it is not known.
     */
    fun prologueOf(line: Int): String? = smap?.sourceOf(line)?.let { position ->
        declarationsOf(position)?.prologueOf(position.line)
    }

    /**
     * Whether the code at this destination [line] of the class may be the code of the [scope], as far as the [Declarations] of its class tell:
     * an inline function must declare the line, and a lambda must not be at a line of the prologue of an inline function, see [Declarations.prologueOf] —
     * the evaluation of a default argument is inlined with a line of the function declaring it, before its marker,
     * so also before its body is in the scopes of the line.
     */
    private fun isLineOf(scope: InlineMarker, line: Int): Boolean {
        val position = smap?.sourceOf(line) ?: return true
        val declarations = declarationsOf(position) ?: return true
        val prologue = declarations.prologueOf(position.line)
        if (scope.isLambda) return prologue == null
        val function = scope.name.inlineFunctionName()
        return (prologue == null || prologue == function) && declarations.declares(function, position.line) != false
    }

    /**
     * The calls of the nested inline functions inlined at the [line] of the [method], of its first overload inlining at the [line],
     * [OUT_OF_SCOPES] if the [line] may throw only out of the scopes of its markers.
     */
    fun inlinedCalls(method: String, line: Int): InlinedCalls? =
        methods[method]?.firstNotNullOfOrNull { it.inlinedCalls(line) }

    /** Whether an instruction of this method at the [line] may throw, see [InlinedMethod.lineThrows]. */
    private fun InlinedMethod.mayThrowAt(line: Int): Boolean =
        lineNumbers.indices.any { lineNumbers[it] == line && lineThrows[it] != THROWS_NOWHERE }

    /**
     * The calls of the lambda and of the inline functions enclosing the [line] of the snippet in this method, innermost first, of each copy of the lambda:
     * the innermost scope of the [line] must be a lambda passed to an inline function, and the outermost one an inline function,
     * each of them with its line found, see [callChain], so that the outermost one tells its call site in the `KotlinDebug` stratum.
     *
     * A frame tells only its line, while the code of the line outside of the lambda, like the call of the inline function receiving it,
     * may keep the same line, so all the instructions of the [line] which may throw must be in scopes of the same names,
     * while the ones which cannot, like the initialization of the inline markers, are not considered.
     * The scopes of the same names are the copies of the same lambda, like of a lambda inlined twice,
     * or of a `finally` block duplicated by the compiler for its normal and its exceptional path.
     *
     * The lambda of an `@InlineOnly` function of the standard library, like `run`, has a marker, unlike the function,
     * so it is a part of the scope enclosing it, see [withoutInlineOnlyLambdas].
     * The scope enclosing the lambda may be another lambda, calling it in its body, like a lambda of an inline function passing its own lambda argument
     * to another inline function, as in `inner { block() }`.
     *
     * @return `null` if the [line] is not within such a lambda, or it is not known which of its scopes throws.
     */
    private fun InlinedMethod.lambdaCalls(line: Int): List<List<Pair<String, Int>>>? {
        val entries = lineNumbers.indices.filter { lineNumbers[it] == line && throwsByItsCode(it) }
        if (entries.isEmpty() || entries.any { lineThrows[it] == THROWS_IN_SCOPES }) return null
        val copies = entries.map { lineScopes[it].withoutInlineOnlyLambdas() }.distinct()
        val scopes = copies.first()
        if (copies.any { copy -> copy.map { it.name } != scopes.map { it.name } }) return null
        if (scopes.size < 2 || !scopes[0].isLambda || scopes.last().isLambda) return null
        return copies.map { copy -> callChain(line, copy).takeIf { it.size == copy.size } ?: return null }
    }

    /**
     * These scopes without the lambdas of the `@InlineOnly` functions, which have no marker of their own,
     * like `run`, whose lambda is inlined with the lines of the scope enclosing it:
     * a lambda marker, like `$i$a$-run-Snippet1$frame$1`, names the function receiving it, and no enclosing marker is of that function.
     *
     * The name may be decorated, like `$i$a$-call$default-Snippet1$frame$1` of a call with a default argument next to `$i$f$call`,
     * so the match is loose — a lambda wrongly kept is rejected as before, while a lambda wrongly dropped would lose its frames.
     */
    private fun List<InlineMarker>.withoutInlineOnlyLambdas(): List<InlineMarker> = filterIndexed { index, marker ->
        !marker.isLambda || subList(index + 1, size).any { scope -> !scope.isLambda && marker.isLambdaOf(scope) }
    }

    /**
     * Whether this lambda marker names the function of the [function] marker, followed by a `-` or a decoration starting with a `$`,
     * also by the name without a suffix of a mangled one, like `name-a1b2c3d`.
     */
    private fun InlineMarker.isLambdaOf(function: InlineMarker): Boolean {
        val name = function.name.removePrefix(INLINE_FUNCTION_MARKER)
        return listOf(name, name.substringBefore('-')).any { prefix ->
            val rest = this.name.removePrefix("$INLINE_LAMBDA_MARKER-")
            rest.startsWith(prefix) && rest.getOrNull(prefix.length).let { it == '-' || it == '$' }
        }
    }

    /**
     * The calls of the nested inline functions inlined at the [line] of this method,
     * the innermost one named by the innermost marker in scope at the position of the [line],
     * and each enclosing one calling it at its own line in effect right before the start of the nested scope,
     * not a line of another scope nested in it, like of an inline function called before, or of a lambda passed to it,
     * nor the `fake.kt` line, which the compiler may give to the initialization of an inline marker variable.
     * The line of an enclosing scope not found, like of an inline function without a line before the call of the nested one,
     * gives it no frame, and the call of the outermost scope gives the [InlinedCalls.methodLine].
     *
     * The same line may be inlined in several scopes, like the copies of an inline function called twice by another one,
     * which share the lines of the same outermost call site, while the frame tells only its line, not its instruction,
     * so the enclosing calls are given only if all the scopes of the [line] agree on them —
     * on the lines of the source they are mapped to, as each copy of an inline function inlined into another one has lines of its own.
     *
     * A lambda argument is a scope as well, called by the inline function receiving it, and calling the nested one in its body,
     * while an `@InlineOnly` function of the standard library, like `require`, has no marker, its body inlined with the lines of its call site.
     *
     * The innermost scope must be able to have the [line], see [isLineOf], otherwise it is not the scope of its code:
     * the line of a prologue of a nested inline function, evaluating its default arguments before its marker, see [Declarations.prologueOf],
     * gives the frame of that function, followed by the frame of the scope at its own line in effect before,
     * unless the function is in the scopes already, so that it is not known which copy of it the line is of,
     * while the line of any other code not told apart gives only the frame of an unknown inline function.
     *
     * @return `null` if the [line] is not in scope of any marker, or [OUT_OF_SCOPES] if it may throw only out of their scopes.
     */
    private fun InlinedMethod.inlinedCalls(line: Int): InlinedCalls? {
        val entries = lineNumbers.indices.filter { lineNumbers[it] == line }
        // the code of a prologue is the code of the function it is inlined before, like its body on the same line
        val candidates = entries.filter { lineThrows[it] != THROWS_OUT_OF_SCOPES && !isPrologue(it) }.mapNotNull { entry ->
            lineScopes[entry].takeIf { it.isNotEmpty() }?.let { scopes ->
                val methodLine = lineBefore(scopes.last().start, scope = null)
                val innermost = scopes.first()
                if (isLineOf(innermost, line)) return@let InlinedCalls(callChain(line, scopes), methodLine)
                val prologue = prologueOf(line)?.takeIf { function ->
                    scopes.none { !it.isLambda && it.name.inlineFunctionName() == function }
                }
                if (prologue != null) {
                    InlinedCalls(listOf(prologue to line) + callChain(lineBefore(lineStarts[entry], innermost), scopes), methodLine)
                } else {
                    InlinedCalls(listOf(UNKNOWN_INLINE_FUNCTION to line), methodLine = null)
                }
            }
        }
        val first = candidates.firstOrNull()
            ?: return OUT_OF_SCOPES.takeIf { entries.isNotEmpty() && entries.all { lineThrows[it] == THROWS_OUT_OF_SCOPES } }
        val source = first.inSource()
        return first.takeIf { candidates.all { it.inSource() == source } }
            ?: InlinedCalls(first.functions.take(1), methodLine = null)
    }

    /** These calls by the functions and the positions in the source of their lines, to tell whether the calls of several scopes agree. */
    private fun InlinedCalls.inSource(): List<Any?> =
        functions.map { (function, line) ->
            smap?.sourceOf(line)?.let { Triple(function, it.path, it.line) } ?: (function to line)
        } + methodLine

    /**
     * The calls of the inline functions and lambdas of these [scopes], innermost first, at the [line] of the innermost one,
     * which gives it no frame if `null`.
     */
    private fun InlinedMethod.callChain(line: Int?, scopes: List<InlineMarker>): List<Pair<String, Int>> = buildList {
        line?.let { add(scopes.first().name.inlineFunctionName() to it) }
        scopes.zipWithNext { nested, scope ->
            callLine(nested, scope)?.let { add(scope.name.inlineFunctionName() to it) }
        }
    }

    /**
     * The line of the [scope] calling the [nested] one, in effect right before its start,
     * or, if the range of the [scope] has none, the last line of the closest earlier range of the same inlined body, see [splitRangeBefore]:
     * the state machine of a suspend function splits the range of an inlined body at its suspension points,
     * and the range resuming into the [nested] scope may have no line of the [scope] of its own,
     * nor the range before it, like the one resuming from a suspension point within an earlier nested scope.
     *
     * The line of a prologue of a nested function, inlined before its marker, is not a line of the [scope], see [InlinedMethod.isPrologue].
     */
    private fun InlinedMethod.callLine(nested: InlineMarker, scope: InlineMarker): Int? =
        lineBefore(nested.start, scope) ?: generateSequence(splitRangeBefore(scope)) { splitRangeBefore(it) }
            .firstNotNullOfOrNull { lineBefore(it.end, it) }

    /**
     * The range of the same marker as the [scope] right before it, of the same inlined body split at a suspension point,
     * `null` if there is none:
     * between the two ranges the state machine resumes, without a line of its own,
     * while an earlier range might be of another copy of the same inline function, which does not tell the line of this one —
     * the code between the copies has lines, and a copy does not start where a suspend function resumes.
     */
    private fun InlinedMethod.splitRangeBefore(scope: InlineMarker): InlineMarker? {
        val before = markers.filter { it.name == scope.name && it.slot == scope.slot && it.end <= scope.start }
            .maxByOrNull { it.start } ?: return null
        val between = before.end..<scope.start
        return before.takeIf {
            resumeLabels.nextSetBit(between.first) in between && lineStarts.none { it in between }
        }
    }

    /**
     * The line in effect within the [scope], the method itself if `null`, right before the instruction at the [index],
     * positioned in the [scope], not in another scope nested in it, nor a `fake.kt` line,
     * nor a line of the method itself, if the [scope] is an inline function.
     *
     * The line may start before the [scope], if positioned in it, see [InlinedMethod.linePositions]:
     * in a suspend function, the first line of an inlined body may start at the label the code continues at after a suspension point,
     * before the initialization of its marker, and be the only line of the [scope] in all the ranges of its body split by the state machine —
     * see `should resolve intermediate frames of nested suspend inline functions inlined twice`.
     * The state machine gives its own code within an inlined body, like returning when suspended, a line of the method,
     * which is not a line of an inline function.
     * Neither is a line of a prologue of a nested inline function a line of the [scope], see [isLineOf].
     */
    private fun InlinedMethod.lineBefore(index: Int, scope: InlineMarker?): Int? {
        val from = scope?.start ?: 0
        return lineNumbers.indices.lastOrNull { i ->
            val position = linePositions[i]
            lineStarts[i] < index
                && !isPrologue(i)
                && (scope == null || position in scope)
                && markers.none { it !== scope && it.start >= from && position in it }
                && smap?.sourceOf(lineNumbers[i])?.path != FAKE_PATH
                && (scope == null || scope.isLambda || !isMethodLine(lineNumbers[i]))
                && (scope == null || isLineOf(scope, lineNumbers[i]))
        }?.let { lineNumbers[it] }
    }

    /** Whether the [line] is a line of the snippet itself, mapped to itself by the SMAP, not a line of inlined code. */
    private fun isMethodLine(line: Int): Boolean =
        smap?.sourceOf(line)?.let { it.line == line && it.path == snippetPath } ?: true

}

/**
 * The lines the inline functions of a class declare, telling whether inlined code at a line may be the code of a scope, see [SnippetClass.isLineOf]:
 * the lines of each method of an inline function which are its own, see [isOwnLine], not of the code inlined into it,
 * by the name of the function, of its method and of its `$default` method alike.
 * The method of an inline function is told by its own marker, like `$i$f$boom` of `boom`,
 * while the lines of the other methods, like of the constructor starting at the line of the class, are not of inlined code.
 *
 * The prologue of an inline function, evaluating its default arguments, is inlined before its marker, so in the scope calling it,
 * with the lines its `$default` method gives the code before its own marker, see [prologueOf].
 *
 * @param methods the methods of the class.
 * @param isOwnLine whether a line of a method of the class is its own, mapped to itself by the SMAP of the class.
 */
private class Declarations(methods: List<MethodNode>, isOwnLine: (Int) -> Boolean) {

    /** The inline functions declaring each line. */
    private val functionsByLine = HashMap<Int, MutableSet<String>>()

    /** The lines of the prologue of each inline function with default arguments. */
    private val prologueLines = HashMap<String, MutableSet<Int>>()

    init {
        methods.forEach { method ->
            val function = method.name.removeSuffix(DEFAULT_SUFFIX)
            val marker = method.localVariables.orEmpty().firstOrNull { it.name == INLINE_FUNCTION_MARKER + function } ?: return@forEach
            val lineNodes = method.instructions.filterIsInstance<LineNumberNode>().filter { isOwnLine(it.line) }
            lineNodes.forEach { functionsByLine.getOrPut(it.line) { HashSet() } += function }
            if (!method.name.endsWith(DEFAULT_SUFFIX)) return@forEach
            val markerStart = method.instructions.indexOf(marker.start)
            lineNodes.filter { method.instructions.indexOf(it.start) < markerStart }
                .forEach { prologueLines.getOrPut(function) { HashSet() } += it.line }
        }
    }

    /** The inline functions declared. */
    private val functions: Set<String> = functionsByLine.values.flatten().toSet()

    /** Whether the inline [function] declares the [line], `null` if the [function] is not declared, like of a name not told by its marker. */
    fun declares(function: String, line: Int): Boolean? =
        if (function in functions) function in functionsByLine[line].orEmpty() else null

    /**
     * The inline function whose prologue, evaluating its default arguments, has the [line], `null` if none,
     * or if another function declares the [line] as well, so that it is not known which code the line is of.
     */
    fun prologueOf(line: Int): String? = functionsByLine[line]?.singleOrNull()?.takeIf { line in prologueLines[it].orEmpty() }

}

/** The suffix of the name of the method evaluating the default arguments of a function, then calling it, or inlining it if it is inline. */
private const val DEFAULT_SUFFIX = "\$default"

/** The [InlinedMethod] of this method, `null` if it has no inline markers. */
private fun MethodNode.inlinedMethod(): InlinedMethod? {
    val markers = localVariables.orEmpty().filter {
        it.name.startsWith(INLINE_FUNCTION_MARKER) || it.name.startsWith(INLINE_LAMBDA_MARKER)
    }.map { InlineMarker(it.name, it.index, instructions.indexOf(it.start), instructions.indexOf(it.end)) }
    if (markers.isEmpty()) return null
    val lineNodes = instructions.filterIsInstance<LineNumberNode>()
    val lineStarts = IntArray(lineNodes.size) { instructions.indexOf(lineNodes[it].start) }
    val throwing = BitSet(instructions.size())
    val resumeLabels = BitSet(instructions.size())
    val allocations = HashMap<Int, Int>()
    instructions.forEachIndexed { index, instruction ->
        if (instruction.mayThrow()) throwing.set(index)
        if (instruction.opcode == Opcodes.NEW) {
            instruction.constructorCall()?.let { allocations[index] = instructions.indexOf(it) }
        }
        // the state machine switches on the label field of its continuation, unlike a `when`, which reads a getter of a property
        val labels = when {
            (instruction.previous as? FieldInsnNode)?.name != STATE_MACHINE_LABEL -> emptyList()
            instruction is TableSwitchInsnNode -> instruction.labels
            instruction is LookupSwitchInsnNode -> instruction.labels
            else -> emptyList()
        }
        labels.forEach { resumeLabels.set(instructions.indexOf(it)) }
    }
    return InlinedMethod(
        markers, IntArray(lineNodes.size) { lineNodes[it].line }, lineStarts, throwing, allocations, resumeLabels, instructions.size()
    )
}

/**
 * Whether this instruction may throw, or call a method which may throw, so its line may be the line of a frame.
 *
 * A `NEW` is not considered: the compiler may allocate an object before the line of the code creating it starts,
 * like the exception of a lambda starting with `error("…")`, allocated within the line of the inline function calling the lambda,
 * which would put that line in the scope of the lambda — while the constructor called after the `NEW` has the line of the code creating it,
 * and its scope, see [InlinedMethod.lineThrows].
 * Neither is an `INSTANCEOF`, nor an `LDC` of a class: they throw only a `LinkageError` of a class which cannot be loaded,
 * while a class literal or a type check passed to an inline function on the line of the lambda it receives,
 * like `call(Checked::class.java) { error("boom") }`, would put the line in two scopes, losing the frames of the lambda.
 */
private fun AbstractInsnNode.mayThrow(): Boolean = when (this) {
    is MethodInsnNode, is InvokeDynamicInsnNode, is MultiANewArrayInsnNode -> true
    // the class literal of a primitive type, like Int::class.java, the field of a class of the platform loaded already
    is FieldInsnNode -> !(opcode == Opcodes.GETSTATIC && name == "TYPE" && owner in PRIMITIVE_WRAPPERS)
    is TypeInsnNode -> opcode != Opcodes.INSTANCEOF && opcode != Opcodes.NEW
    is IntInsnNode -> opcode == Opcodes.NEWARRAY
    is InsnNode -> opcode in THROWING_OPCODES
        || opcode in Opcodes.IALOAD..Opcodes.SALOAD
        || opcode in Opcodes.IASTORE..Opcodes.SASTORE
    else -> false
}

/**
 * The call of the constructor of the object allocated by this `NEW` instruction, the first one of its type not matching a nested `NEW`,
 * `null` if there is none.
 */
private fun AbstractInsnNode.constructorCall(): MethodInsnNode? {
    val type = (this as TypeInsnNode).desc
    var nested = 0
    return generateSequence(next) { it.next }.firstOrNull { instruction ->
        when {
            instruction.opcode == Opcodes.NEW && (instruction as TypeInsnNode).desc == type -> { nested++; false }
            instruction is MethodInsnNode && instruction.opcode == Opcodes.INVOKESPECIAL
                && instruction.name == "<init>" && instruction.owner == type -> nested-- == 0
            else -> false
        }
    } as MethodInsnNode?
}

/** The classes of the platform holding the classes of the primitive types in their `TYPE` fields. */
private val PRIMITIVE_WRAPPERS = setOf(
    "java/lang/Boolean", "java/lang/Byte", "java/lang/Character", "java/lang/Short",
    "java/lang/Integer", "java/lang/Long", "java/lang/Float", "java/lang/Double", "java/lang/Void"
)

/** The opcodes of an [InsnNode] which may throw, other than of an array access. */
private val THROWING_OPCODES = setOf(
    Opcodes.ATHROW, Opcodes.IDIV, Opcodes.LDIV, Opcodes.IREM, Opcodes.LREM,
    Opcodes.ARRAYLENGTH, Opcodes.MONITORENTER, Opcodes.MONITOREXIT
)

/** The position in the source of this destination [line], `null` if the SMAP does not map it. */
private fun SMAP.sourceOf(line: Int): SourcePosition? = findRange(line)?.mapDestToSource(line)

/**
 * The frame of the [function] at the position in the source of this destination [line],
 * `null` if the SMAP does not map it, or maps it to the `fake.kt` file, which is never the code of a frame.
 */
private fun SMAP.frameOf(function: String, line: Int): StackTraceElement? =
    sourceOf(line)?.takeIf { it.path != FAKE_PATH }?.let { StackTraceElement(it.path.replace('/', '.'), function, it.file, it.line) }

/** Reads the `SourceDebugExtension` of a class, its [debug], to tell whether its methods are worth reading. */
private class SourceDebugVisitor : ClassVisitor(Opcodes.ASM9) {

    var debug: String? = null

    override fun visitSource(source: String?, debug: String?) {
        this.debug = debug
    }

}

/** The name of the function of this inline marker variable, `<lambda>` for a lambda argument. */
private fun String.inlineFunctionName(): String =
    if (startsWith(INLINE_LAMBDA_MARKER)) INLINE_LAMBDA else removePrefix(INLINE_FUNCTION_MARKER)
