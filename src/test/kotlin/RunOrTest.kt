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

import com.xemantic.kotlin.script.test.LogCollector
import com.xemantic.kotlin.test.assert
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.oshai.kotlinlogging.Level
import kotlin.test.Test

/** The fallback of a block which throws, logged with its failure, see [runOr]. */
class RunOrTest {

    private val name = "com.xemantic.kotlin.script.RunOrTest"

    private val logger = KotlinLogging.logger(name)

    @Test
    fun `should return result of block`() {
        // when
        val result = logger.runOr(0, { "Block failed" }, Level.WARN) { 42 }

        // then
        assert(result == 42)
    }

    @Test
    fun `should return fallback of failed block and log its failure`() {
        LogCollector(name).use { logs ->
            // when
            val result = logger.runOr(0, { "Block failed" }, Level.WARN) { error("Failure") }

            // then
            assert(result == 0)
            assert(
                logs.events.map { "${it.level} ${it.formattedMessage} - ${it.throwableProxy?.message}" } ==
                    listOf("WARN Block failed - Failure")
            )
        }
    }

    /** Like the debug messages of kotlin-logging, guarded by its `toStringSafe`, see #239. */
    @Test
    fun `should return fallback of failed block also if its message throws`() {
        LogCollector(name).use { logs ->
            // when
            val result = runCatching {
                logger.runOr(0, { error("Message failed") }, Level.WARN) { error("Failure") }
            }

            // then
            assert(result.getOrNull() == 0)
            assert(
                logs.events.map { "${it.level} ${it.formattedMessage} - ${it.throwableProxy?.message}" } ==
                    listOf("WARN Log message invocation failed: java.lang.IllegalStateException: Message failed - Failure")
            )
        }
    }

    @Test
    fun `should throw fatal error of block`() {
        // when
        val failure = runCatching {
            logger.runOr(0, { "Block failed" }, Level.WARN) { throw OutOfMemoryError("Fatal") }
        }.exceptionOrNull()

        // then
        assert(failure is OutOfMemoryError && failure.message == "Fatal")
    }

    @Test
    fun `should throw fatal error of message`() {
        // when
        val failure = runCatching {
            logger.runOr(0, { throw OutOfMemoryError("Fatal") }, Level.WARN) { error("Failure") }
        }.exceptionOrNull()

        // then
        assert(failure is OutOfMemoryError && failure.message == "Fatal")
    }

}
