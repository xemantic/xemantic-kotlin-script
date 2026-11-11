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

package com.xemantic.kotlin.script.test

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory

/** Collects the events logged by the logger of this [name], or by its descendants, until it is closed. */
class LogCollector(name: String) : AutoCloseable {

    private val logger = LoggerFactory.getLogger(name) as Logger

    private val appender = ListAppender<ILoggingEvent>().apply {
        start()
        logger.addAppender(this)
    }

    /** The events logged so far, also on other threads. */
    val events: List<ILoggingEvent>
        get() = synchronized(appender) { appender.list.toList() }

    override fun close() {
        logger.detachAppender(appender)
    }

}
