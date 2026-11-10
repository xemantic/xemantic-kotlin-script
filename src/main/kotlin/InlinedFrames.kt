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
        val calls = inlinedCalls(frame.methodName, frame.lineNumber)
            ?: InlinedCalls(listOf(UNKNOWN_INLINE_FUNCTION to frame.lineNumber), methodLine = null)
        val inlinedFrames = calls.functions.mapNotNull { (function, line) ->
            smap.sourceOf(line)?.let { StackTraceElement(it.path.replace('/', '.'), function, it.file, it.line) }
        }
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
     */
    private fun SnippetClass.resolveLambda(frame: StackTraceElement): List<StackTraceElement>? {
        val smap = smap ?: return null
        val calls = lambdaCalls(frame.methodName, frame.lineNumber) ?: return null
        val callSite = smap.findRange(calls.functions.last().second)?.callSite?.line ?: return null
        val inlinedFrames = calls.functions.map { (function, line) ->
            val source = smap.sourceOf(line) ?: return null
            StackTraceElement(source.path.replace('/', '.'), function, source.file, source.line)
        }
        return inlinedFrames + StackTraceElement(frame.className, frame.methodName, frame.fileName, callSite)
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

/** The line of a method of a class, of the frames analyzed, see [InlinedFrames.resolve]. */
private data class FrameLine(val className: String, val method: String, val line: Int)

/** The [frames] resolved by an analysis of a frame, `null` if none, see [InlinedFrames.resolve]. */
private class Analysis(val frames: List<StackTraceElement>?)

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
 * and the [resumeLabels], the indices of the labels the state machine of a suspend function resumes at,
 * the targets of a switch on a field named `label`, so also of a `when` on such a field accessed directly, which is not told apart.
 * The scopes of the lines are computed on the first frame of the method resolved.
 */
private class InlinedMethod(
    val markers: List<InlineMarker>,
    val lineNumbers: IntArray,
    val lineStarts: IntArray,
    private val throwing: BitSet,
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
    val linePositions: IntArray by lazy(LazyThreadSafetyMode.NONE) {
        IntArray(lineStarts.size) { line ->
            val range = lineRange(line)
            throwing.nextSetBit(range.first).takeIf { it in range } ?: range.first
        }
    }

    /**
     * Whether the instructions of each line may throw:
     * [THROWS_NOWHERE] if none, [THROWS_IN_ONE_SCOPE] if all of them are in scope of the same [markers], or [THROWS_IN_SCOPES].
     */
    val lineThrows: ByteArray by lazy(LazyThreadSafetyMode.NONE) {
        ByteArray(lineStarts.size) { line ->
            val scopes = lineRange(line).filter { throwing[it] }
                .map { position -> markers.filter { position in it } }
                .distinct()
            when (scopes.size) {
                0 -> THROWS_NOWHERE
                1 -> THROWS_IN_ONE_SCOPE
                else -> THROWS_IN_SCOPES
            }
        }
    }

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
 * The SMAP of a snippet class, and the inline markers and the lines of its methods.
 *
 * They are kept for the session, rather than a small map of the frames of every line precomputed on reading the class,
 * as only the lines of the frames reported are analyzed, on demand, while precomputing would analyze every line of every method with a marker —
 * and they take only the arrays of the line numbers of these methods, and their markers, of the classes of the frames reported.
 */
private class SnippetClass(bytes: ByteArray) {

    /** The SMAP of the class, with the call sites of the `KotlinDebug` stratum, `null` if the class has none. */
    val smap: SMAP?

    /** The methods with inlined bodies by their names, several of overloads. */
    private val methods: Map<String, List<InlinedMethod>>

    /** The names of the methods without inlined bodies, which might be overloads of the [methods]. */
    private val methodsWithoutMarkers: Set<String>

    /** The path of the snippet in the SMAP, of its first line, which is never inlined code. */
    private val snippetPath: String? by lazy(LazyThreadSafetyMode.NONE) { smap?.sourceOf(1)?.path }

    init {
        // the node is not kept, only what the frames need
        val node = ClassNode().also { ClassReader(bytes).accept(it, ClassReader.SKIP_FRAMES) }
        smap = node.sourceDebug?.let { SMAPParser.parseOrNull(it) }
        val inlinedMethods = node.methods.map { it to it.inlinedMethod() }
        methods = inlinedMethods.mapNotNull { (method, inlined) -> inlined?.let { method.name to it } }
            .groupBy({ it.first }, { it.second })
        methodsWithoutMarkers = inlinedMethods.filter { it.second == null }.mapTo(HashSet()) { it.first.name }
    }

    /**
     * The calls of the lambda and of the inline functions enclosing the [line] of the snippet in the [method],
     * which must be the only method of its name with an instruction at the [line] which may throw, see [lambdaCalls].
     */
    fun lambdaCalls(method: String, line: Int): InlinedCalls? {
        if (method in methodsWithoutMarkers) return null
        return methods[method].orEmpty().filter { it.throwsAt(line) }.singleOrNull()?.lambdaCalls(line)
    }

    /** The calls of the nested inline functions inlined at the [line] of the [method], of its first overload inlining at the [line]. */
    fun inlinedCalls(method: String, line: Int): InlinedCalls? =
        methods[method]?.firstNotNullOfOrNull { it.inlinedCalls(line) }

    /** Whether an instruction of this method at the [line] may throw. */
    private fun InlinedMethod.throwsAt(line: Int): Boolean =
        lineNumbers.indices.any { lineNumbers[it] == line && lineThrows[it] != THROWS_NOWHERE }

    /**
     * The calls of the lambda and of the inline functions enclosing the [line] of the snippet in this method:
     * the innermost scope of the [line] must be a lambda passed to an inline function, and the outermost one an inline function,
     * each of them with its line found, see [inlinedCalls], so that the outermost one tells its call site in the `KotlinDebug` stratum.
     *
     * A frame tells only its line, while the code of the line outside of the lambda, like the call of the inline function receiving it,
     * may keep the same line, so all the instructions of the [line] which may throw must be in the same scopes,
     * while the ones which cannot, like the initialization of the inline markers, are not considered.
     *
     * @return `null` if the [line] is not within such a lambda, or it is not known which of its scopes throws.
     */
    private fun InlinedMethod.lambdaCalls(line: Int): InlinedCalls? {
        val entries = lineNumbers.indices.filter { lineNumbers[it] == line && lineThrows[it] != THROWS_NOWHERE }
        if (entries.isEmpty() || entries.any { lineThrows[it] == THROWS_IN_SCOPES }) return null
        val scopes = scopesAt(linePositions[entries.first()])
        if (entries.any { scopesAt(linePositions[it]) != scopes }) return null
        if (scopes.size < 2 || !scopes[0].isLambda || scopes[1].isLambda || scopes.last().isLambda) return null
        return inlinedCalls(line, scopes).takeIf { it.functions.size == scopes.size }
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
     * @return `null` if the [line] is not in scope of any marker.
     */
    private fun InlinedMethod.inlinedCalls(line: Int): InlinedCalls? {
        val candidates = lineNumbers.indices.filter { lineNumbers[it] == line }
            .mapNotNull { inlinedCalls(line, linePositions[it]) }
        val first = candidates.firstOrNull() ?: return null
        val source = first.inSource()
        return first.takeIf { candidates.all { it.inSource() == source } }
            ?: InlinedCalls(first.functions.take(1), methodLine = null)
    }

    /** These calls by the functions and the positions in the source of their lines, to tell whether the calls of several scopes agree. */
    private fun InlinedCalls.inSource(): List<Any?> =
        functions.map { (function, line) ->
            smap?.sourceOf(line)?.let { Triple(function, it.path, it.line) } ?: (function to line)
        } + methodLine

    private fun InlinedMethod.inlinedCalls(line: Int, position: Int): InlinedCalls? =
        scopesAt(position).takeIf { it.isNotEmpty() }?.let { inlinedCalls(line, it) }

    /** The markers in scope at the instruction of this [position], innermost first. */
    private fun InlinedMethod.scopesAt(position: Int): List<InlineMarker> =
        markers.filter { position in it }.sortedWith(
            compareByDescending<InlineMarker> { it.start }.thenBy { it.end }.thenByDescending { it.slot }
        )

    /** The calls of the inline functions and lambdas of these [scopes], innermost first, at the [line] of the innermost one. */
    private fun InlinedMethod.inlinedCalls(line: Int, scopes: List<InlineMarker>): InlinedCalls {
        val functions = buildList {
            add(scopes.first().name.inlineFunctionName() to line)
            scopes.zipWithNext { nested, scope ->
                callLine(nested, scope)?.let { add(scope.name.inlineFunctionName() to it) }
            }
        }
        return InlinedCalls(functions, lineBefore(scopes.last().start, scope = null))
    }

    /**
     * The line of the [scope] calling the [nested] one, in effect right before its start,
     * or, if the range of the [scope] has none, the last line of the closest earlier range of the same inlined body, see [splitRangeBefore]:
     * the state machine of a suspend function splits the range of an inlined body at its suspension points,
     * and the range resuming into the [nested] scope may have no line of the [scope] of its own,
     * nor the range before it, like the one resuming from a suspension point within an earlier nested scope.
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
     */
    private fun InlinedMethod.lineBefore(index: Int, scope: InlineMarker?): Int? {
        val from = scope?.start ?: 0
        return lineNumbers.indices.lastOrNull { i ->
            val position = linePositions[i]
            lineStarts[i] < index
                && (scope == null || position in scope)
                && markers.none { it !== scope && it.start >= from && position in it }
                && smap?.sourceOf(lineNumbers[i])?.path != FAKE_PATH
                && (scope == null || scope.isLambda || !isMethodLine(lineNumbers[i]))
        }?.let { lineNumbers[it] }
    }

    /** Whether the [line] is a line of the snippet itself, mapped to itself by the SMAP, not a line of inlined code. */
    private fun isMethodLine(line: Int): Boolean =
        smap?.sourceOf(line)?.let { it.line == line && it.path == snippetPath } ?: true

}

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
    instructions.forEachIndexed { index, instruction ->
        if (instruction.mayThrow()) throwing.set(index)
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
        markers, IntArray(lineNodes.size) { lineNodes[it].line }, lineStarts, throwing, resumeLabels, instructions.size()
    )
}

/**
 * Whether this instruction may throw, or call a method which may throw, so its line may be the line of a frame.
 *
 * A `NEW` is not considered: the compiler may allocate an object before the line of the code creating it starts,
 * like the exception of a lambda starting with `error("…")`, allocated within the line of the inline function calling the lambda,
 * which would put that line in the scope of the lambda — while the constructor called after the `NEW` has the line of the code creating it.
 * Neither is an `INSTANCEOF`, nor an `LDC` of a class: they throw only a `LinkageError` of a class which cannot be loaded,
 * while a class literal or a type check passed to an inline function on the line of the lambda it receives,
 * like `call(Checked::class.java) { error("boom") }`, would put the line in two scopes, losing the frames of the lambda.
 */
private fun AbstractInsnNode.mayThrow(): Boolean = when (this) {
    is MethodInsnNode, is InvokeDynamicInsnNode, is FieldInsnNode, is MultiANewArrayInsnNode -> true
    is TypeInsnNode -> opcode != Opcodes.INSTANCEOF && opcode != Opcodes.NEW
    is IntInsnNode -> opcode == Opcodes.NEWARRAY
    is InsnNode -> opcode in THROWING_OPCODES
        || opcode in Opcodes.IALOAD..Opcodes.SALOAD
        || opcode in Opcodes.IASTORE..Opcodes.SASTORE
    else -> false
}

/** The opcodes of an [InsnNode] which may throw, other than of an array access. */
private val THROWING_OPCODES = setOf(
    Opcodes.ATHROW, Opcodes.IDIV, Opcodes.LDIV, Opcodes.IREM, Opcodes.LREM,
    Opcodes.ARRAYLENGTH, Opcodes.MONITORENTER, Opcodes.MONITOREXIT
)

/** The position in the source of this destination [line], `null` if the SMAP does not map it. */
private fun SMAP.sourceOf(line: Int): SourcePosition? = findRange(line)?.mapDestToSource(line)

/** The name of the function of this inline marker variable, `<lambda>` for a lambda argument. */
private fun String.inlineFunctionName(): String =
    if (startsWith(INLINE_LAMBDA_MARKER)) INLINE_LAMBDA else removePrefix(INLINE_FUNCTION_MARKER)
