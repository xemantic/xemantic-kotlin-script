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

package com.xemantic.kotlin.script;

import kotlinx.coroutines.CoroutineDispatcher;

/** Creates the executors like a Java client, which sees only the factory functions which are not synthetic. */
public final class JavaClient {

    private JavaClient() {}

    public static ScriptExecutor executor() {
        return ScriptExecutorKt.ScriptExecutor();
    }

    public static ScriptExecutor executor(CoroutineDispatcher dispatcher) {
        return ScriptExecutorKt.ScriptExecutor(dispatcher);
    }

    /** Like the Java clients compiled against 0.1.0. */
    public static ScriptExecutor executor(CoroutineDispatcher dispatcher, int compilationCacheSize) {
        return ScriptExecutorKt.ScriptExecutor(dispatcher, compilationCacheSize);
    }

    public static ScriptExecutor executor(CoroutineDispatcher dispatcher, int compilationCacheSize, int maxConcurrentCompilations) {
        return ScriptExecutorKt.ScriptExecutor(dispatcher, compilationCacheSize, maxConcurrentCompilations);
    }

}
