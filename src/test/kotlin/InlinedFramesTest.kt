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

    @Test
    fun `should not resolve frame of a line within the snippet without reading its class`() = runTest {
        // given
        execute(snippet)
        val classLoader = CountingClassLoader(execute("Marker()")!!.javaClass)
        val inlinedFrames = InlinedFrames(snippets)
        inlinedFrames.keep(classLoader.marker)

        // when
        val resolved = inlinedFrames.resolve(StackTraceElement("Snippet1", "frame", "snippet1.kts", 3))

        // then
        assert(resolved == null)
        assert(classLoader.reads == 0)
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
