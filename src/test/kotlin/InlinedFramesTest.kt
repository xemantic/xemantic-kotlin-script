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

import com.xemantic.kotlin.test.assert
import kotlinx.coroutines.test.runTest
import org.jetbrains.org.objectweb.asm.ClassReader
import org.jetbrains.org.objectweb.asm.ClassWriter
import org.jetbrains.org.objectweb.asm.Opcodes
import org.jetbrains.org.objectweb.asm.tree.ClassNode
import org.jetbrains.org.objectweb.asm.tree.LineNumberNode
import org.jetbrains.org.objectweb.asm.tree.MethodNode
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.test.AfterTest
import kotlin.test.Test

class InlinedFramesTest {

    private val session = ScriptSession()

    @AfterTest
    fun close() {
        session.close()
    }

    /** Declares the inline function `boom`, its frame beyond the end of the snippet caught by `frame`, and the class `Marker` of the class loader of the snippet. */
    private val snippet = """
        inline fun boom(): Nothing = throw IllegalStateException("boom")
        class Marker
        fun frame(): StackTraceElement = try { boom() } catch (e: IllegalStateException) { e.stackTrace[0] }
    """.trimIndent()

    private val snippets = mapOf("snippet1.kts" to snippet.lines())

    private suspend fun execute(code: String): Any? = (session.execute(code) as ScriptResult.Value).value

    @Test
    fun `should read the class of a frame unresolved before its class loader was known`() = runTest {
        // given
        execute(snippet)
        val frame = execute("frame()") as StackTraceElement
        val marker = execute("Marker()")!!
        val inlinedFrames = InlinedFrames(snippets)
        val unresolved = inlinedFrames.resolve(frame)

        // when
        inlinedFrames.keep(marker.javaClass)
        val resolved = inlinedFrames.resolve(frame)

        // then
        assert(frame.lineNumber > snippets.getValue("snippet1.kts").size)
        assert(unresolved == null)
        assert(
            resolved == listOf(
                StackTraceElement("Snippet1", "boom", "snippet1.kts", 1),
                StackTraceElement("Snippet1", "frame", "snippet1.kts", 3)
            )
        )
    }

    /**
     * The frames resolved of the frame thrown within the function `frame` of this [snippet],
     * which returns the top frame of the exception thrown,
     * the snippet followed by the class `Marker`, of the class loader of the snippet.
     */
    private suspend fun resolveFrame(snippet: String): List<StackTraceElement>? {
        val source = "$snippet\nclass Marker"
        execute(source)
        val frame = execute("frame()") as StackTraceElement
        val marker = execute("Marker()")!!
        val inlinedFrames = InlinedFrames(mapOf("snippet1.kts" to source.lines()))
        inlinedFrames.keep(marker.javaClass)
        return inlinedFrames.resolve(frame)
    }

    /**
     * The frames resolved like by [resolveFrame], but of the class of the frame transformed by the [transform] of its [method],
     * which stands in for bytecode the compiler has not been seen to produce.
     */
    private suspend fun resolveTransformedFrame(
        snippet: String,
        method: String,
        transform: MethodNode.() -> Unit
    ): List<StackTraceElement>? = resolveTransformedClass(snippet) {
        methods.single { it.name == method }.transform()
    }

    /** The frames resolved like by [resolveTransformedFrame], but of the class of the frame transformed by the [transform]. */
    private suspend fun resolveTransformedClass(
        snippet: String,
        transform: ClassNode.() -> Unit
    ): List<StackTraceElement>? {
        val source = "$snippet\nclass Marker"
        execute(source)
        val frame = execute("frame()") as StackTraceElement
        val classLoader = TransformingClassLoader(execute("Marker()")!!.javaClass, "Snippet1") { it.transform() }
        val inlinedFrames = InlinedFrames(mapOf("snippet1.kts" to source.lines()))
        inlinedFrames.keep(classLoader.marker)
        return inlinedFrames.resolve(frame)
    }

    /** The class `Marker` declared at the end of this [source], executed, of the class loader of its snippet. */
    private suspend fun markerOf(source: String): Class<*> {
        execute(source)
        return execute("Marker()")!!.javaClass
    }

    /** The line in effect at the first `NEW` instruction of the [method] of the class `Snippet1` of the class loader of this marker class. */
    private fun Class<*>.lineOfFirstNew(method: String): Int {
        val bytes = classLoader.getResourceAsStream("Snippet1.class")!!.use { it.readBytes() }
        val node = ClassNode().also { ClassReader(bytes).accept(it, 0) }
        val instructions = node.methods.single { it.name == method }.instructions
        val new = instructions.first { it.opcode == Opcodes.NEW }
        return generateSequence(new) { it.previous }.filterIsInstance<LineNumberNode>().first().line
    }

    @Test
    fun `should resolve intermediate frame of nested inline function`() = runTest {
        // when
        val resolved = resolveFrame("""
            inline fun inner(): Nothing = throw IllegalStateException("inner")
            inline fun outer(): Nothing = inner()
            fun frame(): StackTraceElement = try { outer() } catch (e: IllegalStateException) { e.stackTrace[0] }
        """.trimIndent())

        // then
        assert(
            resolved == listOf(
                StackTraceElement("Snippet1", "inner", "snippet1.kts", 1),
                StackTraceElement("Snippet1", "outer", "snippet1.kts", 2),
                StackTraceElement("Snippet1", "frame", "snippet1.kts", 3)
            )
        )
    }

    @Test
    fun `should resolve intermediate frames of three levels of nested inline functions`() = runTest {
        // when
        val resolved = resolveFrame("""
            inline fun a(): Nothing = throw IllegalStateException("a")
            inline fun b(): Nothing = a()
            inline fun c(): Nothing = b()
            fun frame(): StackTraceElement = try { c() } catch (e: IllegalStateException) { e.stackTrace[0] }
        """.trimIndent())

        // then
        assert(
            resolved == listOf(
                StackTraceElement("Snippet1", "a", "snippet1.kts", 1),
                StackTraceElement("Snippet1", "b", "snippet1.kts", 2),
                StackTraceElement("Snippet1", "c", "snippet1.kts", 3),
                StackTraceElement("Snippet1", "frame", "snippet1.kts", 4)
            )
        )
    }

    @Test
    fun `should resolve intermediate frame of inline function calling inline function of the standard library`() = runTest {
        // when
        val resolved = resolveFrame("""
            inline fun firstBig(list: List<Int>) = list.first { it > 5 }
            fun frame(): StackTraceElement = try { firstBig(listOf(1)); error("not thrown") } catch (e: NoSuchElementException) { e.stackTrace[0] }
        """.trimIndent())

        // then
        // the line of the inlined code of the standard library depends on its version
        assert(resolved?.size == 3)
        assert(resolved!![0].className == "kotlin.collections.CollectionsKt___CollectionsKt")
        assert(resolved[0].methodName == "first")
        assert(resolved[0].fileName == "_Collections.kt")
        assert(
            resolved.drop(1) == listOf(
                StackTraceElement("Snippet1", "firstBig", "snippet1.kts", 1),
                StackTraceElement("Snippet1", "frame", "snippet1.kts", 2)
            )
        )
    }

    @Test
    fun `should resolve frames of lambda passed to inline function of the standard library within inline function`() = runTest {
        // when
        val resolved = resolveFrame("""
            inline fun inner(): Nothing = throw IllegalStateException("inner")
            inline fun outer(list: List<Int>) {
                list.forEach {
                    inner()
                }
            }
            fun frame(): StackTraceElement = try { outer(listOf(1)); error("not thrown") } catch (e: IllegalStateException) { e.stackTrace[0] }
        """.trimIndent())

        // then
        // the line of the inlined code of the standard library depends on its version
        assert(resolved?.size == 5)
        assert(
            resolved!!.take(2) == listOf(
                StackTraceElement("Snippet1", "inner", "snippet1.kts", 1),
                StackTraceElement("Snippet1", "<lambda>", "snippet1.kts", 4)
            )
        )
        assert(resolved[2].className == "kotlin.collections.CollectionsKt___CollectionsKt")
        assert(resolved[2].methodName == "forEach")
        assert(resolved[2].fileName == "_Collections.kt")
        assert(
            resolved.drop(3) == listOf(
                StackTraceElement("Snippet1", "outer", "snippet1.kts", 3),
                StackTraceElement("Snippet1", "frame", "snippet1.kts", 7)
            )
        )
    }

    @Test
    fun `should resolve frames of lambda of the calling function passed to inline function`() = runTest {
        // when
        val resolved = resolveFrame("""
            inline fun inner(): Nothing = throw IllegalStateException("inner")
            inline fun call(block: () -> Unit) {
                block()
            }
            fun frame(): StackTraceElement {
                try {
                    call {
                        inner()
                    }
                } catch (e: IllegalStateException) {
                    return e.stackTrace[0]
                }
                error("not thrown")
            }
        """.trimIndent())

        // then
        // the lambda is the code of the calling function, called by the inline function
        assert(
            resolved == listOf(
                StackTraceElement("Snippet1", "inner", "snippet1.kts", 1),
                StackTraceElement("Snippet1", "<lambda>", "snippet1.kts", 8),
                StackTraceElement("Snippet1", "call", "snippet1.kts", 3),
                StackTraceElement("Snippet1", "frame", "snippet1.kts", 7)
            )
        )
    }

    @Test
    fun `should not resolve intermediate frame of the fake line of the inline marker`() = runTest {
        // when
        val resolved = resolveFrame("""
            inline fun inner(): Nothing = throw IllegalStateException("inner")
            inline fun outer(x: Int) {
                require(x > 1) { inner() }
            }
            fun frame(): StackTraceElement = try { outer(1); error("not thrown") } catch (e: IllegalStateException) { e.stackTrace[0] }
        """.trimIndent())

        // then
        // require is inlined with the lines of its call site, so it has no frame of its own
        assert(
            resolved == listOf(
                StackTraceElement("Snippet1", "inner", "snippet1.kts", 1),
                StackTraceElement("Snippet1", "<lambda>", "snippet1.kts", 3),
                StackTraceElement("Snippet1", "outer", "snippet1.kts", 3),
                StackTraceElement("Snippet1", "frame", "snippet1.kts", 5)
            )
        )
    }

    @Test
    fun `should omit intermediate frame of inline function calling the same inline function twice`() = runTest {
        // when
        val resolved = resolveFrame("""
            inline fun inner(x: Int) { if (x == 2) throw IllegalStateException("x=" + x) }
            inline fun outer() {
                inner(1)
                inner(2)
            }
            fun frame(): StackTraceElement = try { outer(); error("not thrown") } catch (e: IllegalStateException) { e.stackTrace[0] }
        """.trimIndent())

        // then
        // both copies of inner share the line of the frame, so the line calling the one throwing is unknown
        assert(
            resolved == listOf(
                StackTraceElement("Snippet1", "inner", "snippet1.kts", 1),
                StackTraceElement("Snippet1", "frame", "snippet1.kts", 6)
            )
        )
    }

    @Test
    fun `should omit intermediate frame of inline function called also directly on the same line`() = runTest {
        // when
        val resolved = resolveFrame("""
            inline fun inner(x: Int) { if (x == 2) throw IllegalStateException("x=" + x) }
            inline fun outer() { inner(1) }
            fun frame(): StackTraceElement = try { outer(); inner(2); error("not thrown") } catch (e: IllegalStateException) { e.stackTrace[0] }
        """.trimIndent())

        // then
        // the copy of inner within outer shares the line of the frame, so it is unknown whether outer called the one throwing
        assert(
            resolved == listOf(
                StackTraceElement("Snippet1", "inner", "snippet1.kts", 1),
                StackTraceElement("Snippet1", "frame", "snippet1.kts", 3)
            )
        )
    }

    @Test
    fun `should resolve call site of inline function called on a line without a line number of its own`() = runTest {
        // when
        val resolved = resolveFrame("""
            inline fun boom(): Nothing = throw IllegalStateException("boom")
            fun frame(): StackTraceElement = try {
                val map = mapOf<Int, Int>()
                val value = map[1]
                    ?: boom()
                error("not thrown " + value)
            } catch (e: IllegalStateException) {
                e.stackTrace[0]
            }
        """.trimIndent())

        // then
        assert(
            resolved == listOf(
                StackTraceElement("Snippet1", "boom", "snippet1.kts", 1),
                StackTraceElement("Snippet1", "frame", "snippet1.kts", 5)
            )
        )
    }

    @Test
    fun `should resolve intermediate frame of nested inline function called in finally block`() = runTest {
        // when
        val resolved = resolveFrame("""
            inline fun inner(): Nothing = throw IllegalStateException("inner")
            inline fun outer() {
                try {
                    println("try")
                } finally {
                    inner()
                }
            }
            fun frame(): StackTraceElement = try { outer(); error("not thrown") } catch (e: IllegalStateException) { e.stackTrace[0] }
        """.trimIndent())

        // then
        assert(
            resolved == listOf(
                StackTraceElement("Snippet1", "inner", "snippet1.kts", 1),
                StackTraceElement("Snippet1", "outer", "snippet1.kts", 6),
                StackTraceElement("Snippet1", "frame", "snippet1.kts", 9)
            )
        )
    }

    @Test
    fun `should resolve intermediate frame of nested suspend inline functions`() = runTest {
        // when
        val resolved = resolveFrame("""
            suspend inline fun inner(): Nothing { delay(1); throw IllegalStateException("inner") }
            suspend inline fun outer(): Nothing { delay(1); inner() }
            suspend fun suspendingFrame(): StackTraceElement = try { outer() } catch (e: IllegalStateException) { e.stackTrace[0] }
            val awaited = await { suspendingFrame() }
            fun frame(): StackTraceElement = awaited
        """.trimIndent())

        // then
        assert(
            resolved == listOf(
                StackTraceElement("Snippet1", "inner", "snippet1.kts", 1),
                StackTraceElement("Snippet1", "outer", "snippet1.kts", 2),
                StackTraceElement("Snippet1", "suspendingFrame", "snippet1.kts", 3)
            )
        )
    }

    @Test
    fun `should resolve intermediate frame of nested suspend inline functions on separate lines`() = runTest {
        // when
        val resolved = resolveFrame("""
            suspend inline fun inner(): Nothing {
                delay(1)
                throw IllegalStateException("inner")
            }
            suspend inline fun outer(): Nothing {
                delay(1)
                inner()
            }
            suspend fun suspendingFrame(): StackTraceElement = try { outer() } catch (e: IllegalStateException) { e.stackTrace[0] }
            val awaited = await { suspendingFrame() }
            fun frame(): StackTraceElement = awaited
        """.trimIndent())

        // then
        assert(
            resolved == listOf(
                StackTraceElement("Snippet1", "inner", "snippet1.kts", 3),
                StackTraceElement("Snippet1", "outer", "snippet1.kts", 7),
                StackTraceElement("Snippet1", "suspendingFrame", "snippet1.kts", 9)
            )
        )
    }

    @Test
    fun `should resolve intermediate frames of nested suspend inline functions inlined twice`() = runTest {
        // when
        val resolved = resolveFrame("""
            suspend inline fun inner(x: Int) { delay(1); if (x == 2) throw IllegalStateException("x") }
            suspend inline fun mid(x: Int) { delay(1); inner(x) }
            suspend inline fun outer() { mid(1); delay(1); mid(2) }
            suspend fun suspendingFrame(): StackTraceElement = try { outer(); error("not thrown") } catch (e: IllegalStateException) { e.stackTrace[0] }
            val awaited = await { suspendingFrame() }
            fun frame(): StackTraceElement = awaited
        """.trimIndent())

        // then
        // the line of the second copy of mid starts at the label resuming into it, before the initialization of its marker
        assert(
            resolved == listOf(
                StackTraceElement("Snippet1", "inner", "snippet1.kts", 1),
                StackTraceElement("Snippet1", "mid", "snippet1.kts", 2),
                StackTraceElement("Snippet1", "outer", "snippet1.kts", 3),
                StackTraceElement("Snippet1", "suspendingFrame", "snippet1.kts", 4)
            )
        )
    }

    @Test
    fun `should resolve frames of error in the body of lambda passed to inline function with class literal`() = runTest {
        // when
        val resolved = resolveFrame("""
            class Checked
            inline fun call(type: Class<*>, block: () -> Unit) { block() }
            fun frame(): StackTraceElement = try {
                call(Checked::class.java) { error("boom") }
                error("not thrown")
            } catch (e: IllegalStateException) {
                e.stackTrace[0]
            }
        """.trimIndent())

        // then
        assert(
            resolved == listOf(
                StackTraceElement("Snippet1", "<lambda>", "snippet1.kts", 4),
                StackTraceElement("Snippet1", "call", "snippet1.kts", 2),
                StackTraceElement("Snippet1", "frame", "snippet1.kts", 4)
            )
        )
    }

    @Test
    fun `should omit intermediate frame of suspend inline function without a line before the call in its split range`() = runTest {
        // when
        val resolved = resolveTransformedFrame(
            """
            suspend inline fun inner(x: Int) { if (x == 2) throw IllegalStateException("x=" + x) }
            suspend inline fun outer(x: Int) {
                delay(1)
                inner(x)
            }
            suspend fun suspendingFrame(): StackTraceElement = try { outer(1); outer(2); error("not thrown") } catch (e: IllegalStateException) { e.stackTrace[0] }
            val awaited = await { suspendingFrame() }
            fun frame(): StackTraceElement = awaited
            """.trimIndent(),
            method = "suspendingFrame"
        ) {
            // the second copy of outer is split into a range before delay, and a range resuming after it, calling inner
            val inners = markers("\$i\$f\$inner")
            val secondOuter = markers("\$i\$f\$outer").filter { it.first > inners.first().last }
            // only the line of the second copy of inner is left in its scopes, and no line of the first one shares it
            removeLines { line ->
                line in inners.first() || (secondOuter.any { line in it } && line !in inners.last())
            }
        }

        // then
        // the line of outer calling inner is unknown, as both ranges of its copy have no line,
        // while the earlier ranges are of the first copy, so they do not tell it
        assert(
            resolved == listOf(
                StackTraceElement("Snippet1", "inner", "snippet1.kts", 1),
                StackTraceElement("Snippet1", "suspendingFrame", "snippet1.kts", 6)
            )
        )
    }

    @Test
    fun `should resolve call site of inline function receiving lambda called on a line without a line number of its own`() = runTest {
        // when
        val resolved = resolveFrame("""
            inline fun inner(): Nothing = throw IllegalStateException("inner")
            inline fun call(block: () -> Unit) {
                block()
            }
            fun frame(): StackTraceElement {
                try {
                    val map = mapOf<Int, Int>()
                    val value = map[1]
                        ?: call {
                            inner()
                        }
                    error("not thrown " + value)
                } catch (e: IllegalStateException) {
                    return e.stackTrace[0]
                }
            }
        """.trimIndent())

        // then
        assert(
            resolved == listOf(
                StackTraceElement("Snippet1", "inner", "snippet1.kts", 1),
                StackTraceElement("Snippet1", "<lambda>", "snippet1.kts", 10),
                StackTraceElement("Snippet1", "call", "snippet1.kts", 3),
                StackTraceElement("Snippet1", "frame", "snippet1.kts", 9)
            )
        )
    }

    @Test
    fun `should resolve frames of throw in the body of lambda passed to inline function`() = runTest {
        // when
        val resolved = resolveFrame("""
            inline fun call(block: () -> Unit) {
                block()
            }
            fun frame(): StackTraceElement {
                try {
                    call {
                        throw IllegalStateException("lambda")
                    }
                } catch (e: IllegalStateException) {
                    return e.stackTrace[0]
                }
                error("not thrown")
            }
        """.trimIndent())

        // then
        // the body of the lambda keeps the lines of the snippet, but lies within the inlined body of call
        assert(
            resolved == listOf(
                StackTraceElement("Snippet1", "<lambda>", "snippet1.kts", 7),
                StackTraceElement("Snippet1", "call", "snippet1.kts", 2),
                StackTraceElement("Snippet1", "frame", "snippet1.kts", 6)
            )
        )
    }

    @Test
    fun `should resolve frames of throw in the body of lambda on the line of the call of inline function`() = runTest {
        // when
        val resolved = resolveFrame("""
            inline fun call(block: () -> Unit) {
                block()
            }
            fun frame(): StackTraceElement {
                try {
                    call { throw IllegalStateException("lambda") }
                } catch (e: IllegalStateException) {
                    return e.stackTrace[0]
                }
                error("not thrown")
            }
        """.trimIndent())

        // then
        // the code of the line outside of the lambda cannot throw, so the frame is of the lambda
        assert(
            resolved == listOf(
                StackTraceElement("Snippet1", "<lambda>", "snippet1.kts", 6),
                StackTraceElement("Snippet1", "call", "snippet1.kts", 2),
                StackTraceElement("Snippet1", "frame", "snippet1.kts", 6)
            )
        )
    }

    @Test
    fun `should resolve frames of throw in the body of lambda passed to inline function within lambda`() = runTest {
        // when
        val resolved = resolveFrame("""
            inline fun call(block: () -> Unit) {
                block()
            }
            fun frame(): StackTraceElement {
                try {
                    call {
                        call {
                            throw IllegalStateException("lambda")
                        }
                    }
                } catch (e: IllegalStateException) {
                    return e.stackTrace[0]
                }
                error("not thrown")
            }
        """.trimIndent())

        // then
        assert(
            resolved == listOf(
                StackTraceElement("Snippet1", "<lambda>", "snippet1.kts", 8),
                StackTraceElement("Snippet1", "call", "snippet1.kts", 2),
                StackTraceElement("Snippet1", "<lambda>", "snippet1.kts", 7),
                StackTraceElement("Snippet1", "call", "snippet1.kts", 2),
                StackTraceElement("Snippet1", "frame", "snippet1.kts", 6)
            )
        )
    }

    @Test
    fun `should resolve frames of throw in the body of lambda passed to inline function of the standard library`() = runTest {
        // when
        val resolved = resolveFrame("""
            fun frame(): StackTraceElement {
                try {
                    listOf(1).forEach {
                        throw IllegalStateException("lambda")
                    }
                } catch (e: IllegalStateException) {
                    return e.stackTrace[0]
                }
                error("not thrown")
            }
        """.trimIndent())

        // then
        // the line of the inlined code of the standard library depends on its version
        assert(resolved?.size == 3)
        assert(resolved!![0] == StackTraceElement("Snippet1", "<lambda>", "snippet1.kts", 4))
        assert(resolved[1].className == "kotlin.collections.CollectionsKt___CollectionsKt")
        assert(resolved[1].methodName == "forEach")
        assert(resolved[1].fileName == "_Collections.kt")
        assert(resolved[2] == StackTraceElement("Snippet1", "frame", "snippet1.kts", 3))
    }

    @Test
    fun `should resolve frames of error in the body of lambda passed to inline function`() = runTest {
        // when
        val resolved = resolveFrame("""
            inline fun pre(block: () -> Unit) {
                println("before")
                block()
            }
            fun frame(): StackTraceElement {
                try {
                    pre {
                        error("lambda")
                    }
                } catch (e: IllegalStateException) {
                    return e.stackTrace[0]
                }
                error("not thrown")
            }
        """.trimIndent())

        // then
        // the exception created by error is allocated in the lambda before the line of the lambda starts, within the line calling it
        assert(
            resolved == listOf(
                StackTraceElement("Snippet1", "<lambda>", "snippet1.kts", 8),
                StackTraceElement("Snippet1", "pre", "snippet1.kts", 3),
                StackTraceElement("Snippet1", "frame", "snippet1.kts", 7)
            )
        )
    }

    @Test
    fun `should resolve frames of error in the body of lambda passed to inline function without a line before calling it`() = runTest {
        // when
        val resolved = resolveFrame("""
            inline fun call(block: () -> Unit) {
                block()
            }
            fun frame(): StackTraceElement {
                try {
                    call {
                        error("lambda")
                    }
                } catch (e: IllegalStateException) {
                    return e.stackTrace[0]
                }
                error("not thrown")
            }
        """.trimIndent())

        // then
        assert(
            resolved == listOf(
                StackTraceElement("Snippet1", "<lambda>", "snippet1.kts", 7),
                StackTraceElement("Snippet1", "call", "snippet1.kts", 2),
                StackTraceElement("Snippet1", "frame", "snippet1.kts", 6)
            )
        )
    }

    @Test
    fun `should resolve frames of error in the body of lambda passed to inline function calling it in try block`() = runTest {
        // when
        val resolved = resolveFrame("""
            inline fun tr(block: () -> Unit) {
                try {
                    block()
                } finally {
                    println("finally")
                }
            }
            fun frame(): StackTraceElement {
                try {
                    tr {
                        error("lambda")
                    }
                } catch (e: IllegalStateException) {
                    return e.stackTrace[0]
                }
                error("not thrown")
            }
        """.trimIndent())

        // then
        assert(
            resolved == listOf(
                StackTraceElement("Snippet1", "<lambda>", "snippet1.kts", 11),
                StackTraceElement("Snippet1", "tr", "snippet1.kts", 3),
                StackTraceElement("Snippet1", "frame", "snippet1.kts", 10)
            )
        )
    }

    @Test
    fun `should resolve frames of error in the body of lambda passed to suspend inline function`() = runTest {
        // when
        val resolved = resolveFrame("""
            suspend inline fun sc(block: () -> Unit) {
                delay(1)
                block()
            }
            suspend fun suspendingFrame(): StackTraceElement {
                try {
                    sc {
                        error("lambda")
                    }
                } catch (e: IllegalStateException) {
                    return e.stackTrace[0]
                }
                error("not thrown")
            }
            val awaited = await { suspendingFrame() }
            fun frame(): StackTraceElement = awaited
        """.trimIndent())

        // then
        assert(
            resolved == listOf(
                StackTraceElement("Snippet1", "<lambda>", "snippet1.kts", 8),
                StackTraceElement("Snippet1", "sc", "snippet1.kts", 3),
                StackTraceElement("Snippet1", "suspendingFrame", "snippet1.kts", 7)
            )
        )
    }

    @Test
    fun `should resolve intermediate frames of error in the body of lambda passed to inline function within inline function`() = runTest {
        // when
        val resolved = resolveFrame("""
            inline fun pre(block: () -> Unit) {
                println("before")
                block()
            }
            inline fun wrapper() {
                pre {
                    error("lambda")
                }
            }
            fun frame(): StackTraceElement = try { wrapper(); error("not thrown") } catch (e: IllegalStateException) { e.stackTrace[0] }
        """.trimIndent())

        // then
        assert(
            resolved == listOf(
                StackTraceElement("Snippet1", "<lambda>", "snippet1.kts", 7),
                StackTraceElement("Snippet1", "pre", "snippet1.kts", 3),
                StackTraceElement("Snippet1", "wrapper", "snippet1.kts", 6),
                StackTraceElement("Snippet1", "frame", "snippet1.kts", 10)
            )
        )
    }

    @Test
    fun `should not resolve frame of throw in the body of lambda on a line which may throw also outside of it`() = runTest {
        // when
        val resolved = resolveFrame("""
            inline fun call(size: Int, block: () -> Unit) {
                block()
            }
            fun frame(): StackTraceElement {
                try {
                    call(listOf(1).size) { throw IllegalStateException("lambda") }
                } catch (e: IllegalStateException) {
                    return e.stackTrace[0]
                }
                error("not thrown")
            }
        """.trimIndent())

        // then
        // the frame tells only the line, which may be of the code outside of the lambda as well
        assert(resolved == null)
    }

    @Test
    fun `should not resolve frame of throw in the body of lambda passed to inline function without a frame`() = runTest {
        // when
        val resolved = resolveFrame("""
            fun frame(): StackTraceElement {
                try {
                    run {
                        throw IllegalStateException("lambda")
                    }
                } catch (e: IllegalStateException) {
                    return e.stackTrace[0]
                }
                error("not thrown")
            }
        """.trimIndent())

        // then
        // run is inlined with the lines of its call site, so it has no frame to report the lambda in
        assert(resolved == null)
    }

    @Test
    fun `should resolve frames of error in the body of lambda inlined twice`() = runTest {
        // when
        val resolved = resolveFrame("""
            inline fun twice(block: () -> Unit) { block(); block() }
            fun frame(): StackTraceElement = try {
                val list = listOf(0, 1)
                var k = 0
                twice {
                    if (list[k++] == 1) error("x")
                }
                error("not thrown")
            } catch (e: IllegalStateException) { e.stackTrace[0] }
        """.trimIndent())

        // then
        // both copies of the lambda are called on the same line of twice
        assert(
            resolved == listOf(
                StackTraceElement("Snippet1", "<lambda>", "snippet1.kts", 6),
                StackTraceElement("Snippet1", "twice", "snippet1.kts", 1),
                StackTraceElement("Snippet1", "frame", "snippet1.kts", 5)
            )
        )
    }

    @Test
    fun `should omit intermediate frame of lambda inlined twice by calls on different lines`() = runTest {
        // when
        val resolved = resolveFrame("""
            inline fun twice(block: () -> Unit) {
                block()
                block()
            }
            fun frame(): StackTraceElement = try {
                val list = listOf(0, 1)
                var k = 0
                twice {
                    if (list[k++] == 1) error("x")
                }
                error("not thrown")
            } catch (e: IllegalStateException) { e.stackTrace[0] }
        """.trimIndent())

        // then
        // the copies of the lambda share the line of the frame, so the line of twice calling the one throwing is unknown
        assert(
            resolved == listOf(
                StackTraceElement("Snippet1", "<lambda>", "snippet1.kts", 9),
                StackTraceElement("Snippet1", "frame", "snippet1.kts", 8)
            )
        )
    }

    @Test
    fun `should resolve frames of error in the body of lambda passed to inline function in finally block`() = runTest {
        // when
        val resolved = resolveFrame("""
            inline fun call(block: () -> Unit) { block() }
            fun frame(): StackTraceElement = try {
                try {
                    println("try")
                } finally {
                    call {
                        error("x")
                    }
                }
                error("not thrown")
            } catch (e: IllegalStateException) { e.stackTrace[0] }
        """.trimIndent())

        // then
        // the finally block is duplicated for the normal and the exceptional path, with the same lines
        assert(
            resolved == listOf(
                StackTraceElement("Snippet1", "<lambda>", "snippet1.kts", 7),
                StackTraceElement("Snippet1", "call", "snippet1.kts", 1),
                StackTraceElement("Snippet1", "frame", "snippet1.kts", 6)
            )
        )
    }

    @Test
    fun `should resolve frames of error in the body of lambda in constructor with overload without inlined code`() = runTest {
        // when
        val resolved = resolveFrame("""
            var captured: StackTraceElement? = null
            class C(val n: Int) { constructor() : this(0); init { try { listOf(1).forEach {
                error("x") }
            } catch (e: IllegalStateException) { captured = e.stackTrace[0] } } }
            fun frame(): StackTraceElement { C(1); return captured!! }
        """.trimIndent())

        // then
        // the secondary constructor has no line of the lambda
        // the line of the inlined code of the standard library depends on its version
        assert(resolved?.size == 3)
        assert(resolved!![0] == StackTraceElement("Snippet1\$C", "<lambda>", "snippet1.kts", 3))
        assert(resolved[1].methodName == "forEach")
        assert(resolved[2] == StackTraceElement("Snippet1\$C", "<init>", "snippet1.kts", 2))
    }

    @Test
    fun `should resolve frames of error in the body of lambda passed to scope function within lambda`() = runTest {
        // when
        val resolved = resolveFrame("""
            inline fun call(block: () -> Unit) { block() }
            fun frame(): StackTraceElement = try {
                call { run {
                    error("x") } }
                error("not thrown")
            } catch (e: IllegalStateException) { e.stackTrace[0] }
        """.trimIndent())

        // then
        // run is inlined with the lines of its call site, so its lambda is a part of the lambda of call
        assert(
            resolved == listOf(
                StackTraceElement("Snippet1", "<lambda>", "snippet1.kts", 4),
                StackTraceElement("Snippet1", "call", "snippet1.kts", 1),
                StackTraceElement("Snippet1", "frame", "snippet1.kts", 3)
            )
        )
    }

    @Test
    fun `should resolve frames of error in the body of lambda passed to inline function within lambda of scope function`() = runTest {
        // when
        val resolved = resolveFrame("""
            inline fun call(block: () -> Unit) { block() }
            fun frame(): StackTraceElement = try {
                run { call {
                    error("x") } }
                error("not thrown")
            } catch (e: IllegalStateException) { e.stackTrace[0] }
        """.trimIndent())

        // then
        assert(
            resolved == listOf(
                StackTraceElement("Snippet1", "<lambda>", "snippet1.kts", 4),
                StackTraceElement("Snippet1", "call", "snippet1.kts", 1),
                StackTraceElement("Snippet1", "frame", "snippet1.kts", 3)
            )
        )
    }

    @Test
    fun `should resolve frames of error in the body of lambda passed to inline function with default argument`() = runTest {
        // when
        val resolved = resolveFrame("""
            inline fun call(x: Int = 0, block: () -> Unit) { if (x >= 0) block() }
            fun frame(): StackTraceElement = try {
                call {
                    error("x")
                }
                error("not thrown")
            } catch (e: IllegalStateException) { e.stackTrace[0] }
        """.trimIndent())

        // then
        // the marker of the lambda is named after the $default method, like $i$a$-call$default-Snippet1$frame$1
        assert(
            resolved == listOf(
                StackTraceElement("Snippet1", "<lambda>", "snippet1.kts", 4),
                StackTraceElement("Snippet1", "call", "snippet1.kts", 1),
                StackTraceElement("Snippet1", "frame", "snippet1.kts", 3)
            )
        )
    }

    @Test
    fun `should resolve frames of error in the body of lambda passed to inline function with default argument within lambda`() = runTest {
        // when
        val resolved = resolveFrame("""
            inline fun call(x: Int = 0, block: () -> Unit) { if (x >= 0) block() }
            inline fun outer(block: () -> Unit) { block() }
            fun frame(): StackTraceElement = try {
                outer { call {
                    error("x") } }
                error("not thrown")
            } catch (e: IllegalStateException) { e.stackTrace[0] }
        """.trimIndent())

        // then
        assert(
            resolved == listOf(
                StackTraceElement("Snippet1", "<lambda>", "snippet1.kts", 5),
                StackTraceElement("Snippet1", "call", "snippet1.kts", 1),
                StackTraceElement("Snippet1", "<lambda>", "snippet1.kts", 4),
                StackTraceElement("Snippet1", "outer", "snippet1.kts", 2),
                StackTraceElement("Snippet1", "frame", "snippet1.kts", 4)
            )
        )
    }

    @Test
    fun `should resolve intermediate frame of nested inline function with default argument on the line of its body`() = runTest {
        // when
        val resolved = resolveFrame("""
            inline fun inner(x: Int = 1): Nothing = throw IllegalStateException("x=" + x)
            inline fun outer() {
                println("outer")
                inner()
            }
            fun frame(): StackTraceElement = try { outer(); error("not thrown") } catch (e: IllegalStateException) { e.stackTrace[0] }
        """.trimIndent())

        // then
        // the default argument is evaluated before the marker of inner, with the line of its body, in the scope of outer
        assert(
            resolved == listOf(
                StackTraceElement("Snippet1", "inner", "snippet1.kts", 1),
                StackTraceElement("Snippet1", "outer", "snippet1.kts", 4),
                StackTraceElement("Snippet1", "frame", "snippet1.kts", 6)
            )
        )
    }

    @Test
    fun `should resolve frames of throw in the body of lambda without the call site of the KotlinDebug stratum`() = runTest {
        // when
        val resolved = resolveTransformedClass("""
            inline fun call(block: () -> Unit) {
                block()
            }
            fun frame(): StackTraceElement {
                try {
                    call {
                        throw IllegalStateException("lambda")
                    }
                } catch (e: IllegalStateException) {
                    return e.stackTrace[0]
                }
                error("not thrown")
            }
        """.trimIndent()) {
            sourceDebug = sourceDebug.substringBefore("*S KotlinDebug") + "*E\n"
        }

        // then
        // like a line beyond the end of the snippet without a call site, the frame itself stands for the call site
        assert(
            resolved == listOf(
                StackTraceElement("Snippet1", "<lambda>", "snippet1.kts", 7),
                StackTraceElement("Snippet1", "call", "snippet1.kts", 2),
                StackTraceElement("Snippet1", "frame", "snippet1.kts", 7)
            )
        )
    }

    @Test
    fun `should not resolve frame of allocation of exception of the next line under the closing line of inline function`() = runTest {
        // given
        val source = """
            inline fun boom() {
                println("boom")
            }
            fun frame(): StackTraceElement = try {
                boom()
                error("x")
            } catch (e: IllegalStateException) { e.stackTrace[0] }
            class Marker
        """.trimIndent()
        val marker = markerOf(source)
        val inlinedFrames = InlinedFrames(mapOf("snippet1.kts" to source.lines())).apply { keep(marker) }
        // the frame of an OutOfMemoryError of the allocation of the exception of error("x")
        val line = marker.lineOfFirstNew("frame")

        // when
        val resolved = inlinedFrames.resolve(StackTraceElement("Snippet1", "frame", "snippet1.kts", line))

        // then
        // the allocation follows the inlined body of boom, under the line of its closing brace
        assert(line > source.lines().size)
        assert(resolved == null)
    }

    @Test
    fun `should resolve frame of allocation of exception of lambda under the line of inline function calling it`() = runTest {
        // given
        val source = """
            inline fun pre(block: () -> Unit) {
                println("before")
                block()
            }
            fun frame(): StackTraceElement = try {
                pre {
                    error("lambda")
                }
                error("not thrown")
            } catch (e: IllegalStateException) { e.stackTrace[0] }
            class Marker
        """.trimIndent()
        val marker = markerOf(source)
        val inlinedFrames = InlinedFrames(mapOf("snippet1.kts" to source.lines())).apply { keep(marker) }
        // the frame of an OutOfMemoryError of the allocation of the exception of error("lambda")
        val line = marker.lineOfFirstNew("frame")

        // when
        val resolved = inlinedFrames.resolve(StackTraceElement("Snippet1", "frame", "snippet1.kts", line))

        // then
        // the allocation is the code of the lambda called on the line of pre
        assert(line > source.lines().size)
        assert(
            resolved == listOf(
                StackTraceElement("Snippet1", "pre", "snippet1.kts", 3),
                StackTraceElement("Snippet1", "frame", "snippet1.kts", 6)
            )
        )
    }

    @Test
    fun `should read class of frame which cannot be read once until the next snippet`() = runTest {
        // given
        execute(snippet)
        val classLoader = CountingClassLoader(execute("Marker()")!!.javaClass)
        val inlinedFrames = InlinedFrames(snippets)
        inlinedFrames.keep(classLoader.marker)
        val missing = StackTraceElement("Snippet1\$Missing", "run", "snippet1.kts", 3)

        // when
        val resolved = List(3) { inlinedFrames.resolve(missing) }
        val readsBeforeKeep = classLoader.reads
        inlinedFrames.keep(classLoader.marker)
        inlinedFrames.resolve(missing)

        // then
        assert(resolved == listOf(null, null, null))
        assert(readsBeforeKeep == 1)
        assert(classLoader.reads == 2)
    }

    @Test
    fun `should analyze frame of inlined code once per method and line`() = runTest {
        // given
        execute(snippet)
        val frame = execute("frame()") as StackTraceElement
        val marker = execute("Marker()")!!
        val analyzed = mutableListOf<StackTraceElement>()
        val inlinedFrames = InlinedFrames(snippets).apply { analyzing = { analyzed += it } }
        inlinedFrames.keep(marker.javaClass)

        // when
        // like the repeated frames of a recursion through inlined code overflowing the stack
        val resolved = List(3) { inlinedFrames.resolve(StackTraceElement(frame.className, frame.methodName, frame.fileName, frame.lineNumber)) }

        // then
        assert(analyzed == listOf(frame))
        assert(resolved.distinct().size == 1)
        assert(
            resolved.first() == listOf(
                StackTraceElement("Snippet1", "boom", "snippet1.kts", 1),
                StackTraceElement("Snippet1", "frame", "snippet1.kts", 3)
            )
        )
    }

    @Test
    fun `should not resolve frame of inlined code whose analysis fails`() = runTest {
        // given
        execute(snippet)
        val frame = execute("frame()") as StackTraceElement
        val marker = execute("Marker()")!!
        var analyses = 0
        // stands in for an unexpected failure of the analysis of odd bytecode
        val inlinedFrames = InlinedFrames(snippets).apply { analyzing = { analyses++; throw IndexOutOfBoundsException("-1") } }
        inlinedFrames.keep(marker.javaClass)

        // when
        val resolved = List(2) { inlinedFrames.resolve(frame) }

        // then
        assert(resolved == listOf(null, null))
        assert(analyses == 1)
    }

    @Test
    fun `should resolve frame of inlined code again after its analysis overflows the stack`() = runTest {
        // given
        execute(snippet)
        val frame = execute("frame()") as StackTraceElement
        val marker = execute("Marker()")!!
        var analyses = 0
        // stands in for an analysis on a stack too deep to complete it
        val inlinedFrames = InlinedFrames(snippets).apply { analyzing = { if (++analyses == 1) throw StackOverflowError() } }
        inlinedFrames.keep(marker.javaClass)

        // when
        val resolved = List(2) { inlinedFrames.resolve(frame) }

        // then
        assert(
            resolved == listOf(
                null,
                listOf(
                    StackTraceElement("Snippet1", "boom", "snippet1.kts", 1),
                    StackTraceElement("Snippet1", "frame", "snippet1.kts", 3)
                )
            )
        )
        assert(analyses == 2)
    }

    @Test
    fun `should not resolve frame of a line within the snippet outside of inlined lambda`() = runTest {
        // given
        execute(snippet)
        val marker = execute("Marker()")!!
        val inlinedFrames = InlinedFrames(snippets)
        inlinedFrames.keep(marker.javaClass)

        // when
        val resolved = inlinedFrames.resolve(StackTraceElement("Snippet1", "frame", "snippet1.kts", 3))

        // then
        assert(resolved == null)
    }

    @Test
    fun `should not resolve frame without a line number without reading its class`() = runTest {
        // given
        execute(snippet)
        val classLoader = CountingClassLoader(execute("Marker()")!!.javaClass)
        val inlinedFrames = InlinedFrames(snippets)
        inlinedFrames.keep(classLoader.marker)

        // when
        val resolved = inlinedFrames.resolve(StackTraceElement("Snippet1", "frame", "snippet1.kts", -1))

        // then
        assert(resolved == null)
        assert(classLoader.reads == 0)
    }

    @Test
    fun `should not resolve frame of a class which is not a snippet`() = runTest {
        // given
        execute(snippet)
        val marker = execute("Marker()")!!
        val inlinedFrames = InlinedFrames(snippets)
        inlinedFrames.keep(marker.javaClass)

        // when
        val resolved = inlinedFrames.resolve(StackTraceElement("java.lang.Class", "getDeclaredField", "Class.java", 2794))

        // then
        assert(resolved == null)
    }

}

/**
 * A class loader counting the class files read through it,
 * defining the [marker] class again, so that it is the class loader of its [marker].
 */
private class CountingClassLoader(marker: Class<*>) : ClassLoader(marker.classLoader) {

    var reads = 0

    val marker: Class<*> = marker.classLoader.getResourceAsStream(marker.name.replace('.', '/') + ".class")!!
        .use { it.readBytes() }
        .let { defineClass(marker.name, it, 0, it.size) }

    override fun getResourceAsStream(name: String): InputStream? {
        reads++
        return super.getResourceAsStream(name)
    }

}

/**
 * A class loader reading the class file of the [className] transformed by the [transform],
 * defining the [marker] class again, so that it is the class loader of its [marker].
 */
private class TransformingClassLoader(
    marker: Class<*>,
    private val className: String,
    private val transform: (ClassNode) -> Unit
) : ClassLoader(marker.classLoader) {

    val marker: Class<*> = marker.classLoader.getResourceAsStream(marker.name.replace('.', '/') + ".class")!!
        .use { it.readBytes() }
        .let { defineClass(marker.name, it, 0, it.size) }

    override fun getResourceAsStream(name: String): InputStream? {
        val stream = super.getResourceAsStream(name)
        if (name != className.replace('.', '/') + ".class") return stream
        val node = ClassNode().also { node -> ClassReader(stream!!.use { it.readBytes() }).accept(node, 0) }
        transform(node)
        return ByteArrayInputStream(ClassWriter(0).also { node.accept(it) }.toByteArray())
    }

}

/** The ranges of the instruction indices of the local variables of this method starting with the [prefix], by their start. */
private fun MethodNode.markers(prefix: String): List<IntRange> = localVariables
    .filter { it.name.startsWith(prefix) }
    .map { instructions.indexOf(it.start)..<instructions.indexOf(it.end) }
    .sortedBy { it.first }

/** Removes the line numbers of this method whose index of the instruction starting the line matches the [predicate]. */
private fun MethodNode.removeLines(predicate: (start: Int) -> Boolean) {
    instructions.filterIsInstance<LineNumberNode>()
        .filter { predicate(instructions.indexOf(it.start)) }
        .forEach { instructions.remove(it) }
}
