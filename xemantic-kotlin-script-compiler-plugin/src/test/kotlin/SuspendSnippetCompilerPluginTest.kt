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


package com.xemantic.kotlin.script.compiler

import com.xemantic.kotlin.test.assert
import com.xemantic.kotlin.test.be
import com.xemantic.kotlin.test.have
import com.xemantic.kotlin.test.should
import kotlinx.coroutines.runBlocking
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.scripting.compiler.plugin.impl.K2ReplCompiler
import org.jetbrains.kotlin.scripting.compiler.plugin.impl.ScriptDiagnosticsMessageCollector
import org.jetbrains.kotlin.scripting.compiler.plugin.services.FirReplHistoryProviderImpl
import org.jetbrains.kotlin.scripting.compiler.plugin.services.firReplHistoryProvider
import org.jetbrains.kotlin.scripting.compiler.plugin.services.isReplSnippetSource
import kotlin.coroutines.Continuation
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.ScriptDiagnostic
import kotlin.script.experimental.api.ScriptEvaluationConfiguration
import kotlin.script.experimental.api.compilerOptions
import kotlin.script.experimental.api.defaultImports
import kotlin.script.experimental.api.repl
import kotlin.script.experimental.host.ScriptingHostConfiguration
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.jvm.baseClassLoader
import kotlin.script.experimental.jvm.defaultJvmScriptingHostConfiguration
import kotlin.script.experimental.jvm.dependenciesFromClassloader
import kotlin.script.experimental.jvm.impl.KJvmCompiledScript
import kotlin.script.experimental.jvm.jvm
import kotlin.test.Test

class SuspendSnippetCompilerPluginTest {

    /** Compiles the [snippet] by the K2 REPL compiler, with the plugin if [suspending], returning the class of the snippet, or the errors. */
    private fun compile(
        snippet: String,
        suspending: Boolean = true
    ): ResultWithDiagnostics<Class<*>> {
        val disposable = Disposer.newDisposable()
        try {
            val configuration = ScriptCompilationConfiguration {
                jvm {
                    dependenciesFromClassloader(classLoader = SuspendSnippetCompilerPluginTest::class.java.classLoader, wholeClasspath = true)
                }
                // without -Xplugin, the scripting compiler would load every plugin of the classpath, this one as well
                val plugins = if (suspending) suspendSnippetPluginRoots else listOf(noPlugins)
                compilerOptions("-Xplugin=${plugins.joinToString(",")}")
                defaultImports("kotlinx.coroutines.*")
            }
            val compiler = K2ReplCompiler(
                K2ReplCompiler.createCompilationState(
                    ScriptDiagnosticsMessageCollector(null),
                    disposable,
                    configuration,
                    ScriptingHostConfiguration(defaultJvmScriptingHostConfiguration) {
                        repl {
                            firReplHistoryProvider(FirReplHistoryProviderImpl())
                            isReplSnippetSource { _, _ -> true }
                        }
                    }
                )
            )
            return runBlocking {
                when (val result = compiler.compile(listOf(snippet.toScriptSource("snippet1.kts")), configuration)) {
                    is ResultWithDiagnostics.Failure -> result
                    is ResultWithDiagnostics.Success -> (result.value.get() as KJvmCompiledScript).getClass(
                        ScriptEvaluationConfiguration {
                            jvm { baseClassLoader(SuspendSnippetCompilerPluginTest::class.java.classLoader) }
                        }
                    ).let { classResult ->
                        when (classResult) {
                            is ResultWithDiagnostics.Failure -> classResult
                            is ResultWithDiagnostics.Success -> ResultWithDiagnostics.Success(classResult.value.java)
                        }
                    }
                }
            }
        } finally {
            Disposer.dispose(disposable)
        }
    }

    /** A directory declaring no plugin. */
    private val noPlugins = kotlin.io.path.createTempDirectory("no-plugins").toFile().apply { deleteOnExit() }

    private val Class<*>.evalFunction get() = methods.single { it.name == "\$\$eval" }

    @Test
    fun `should compile snippet calling suspend function at its top level`() {
        // when
        val result = compile("delay(1)")

        // then
        result should {
            be<ResultWithDiagnostics.Success<Class<*>>>()
            have(value.evalFunction.parameterTypes.last() == Continuation::class.java)
        }
    }

    @Test
    fun `should compile property of snippet initialized by suspend call`() {
        // when
        val result = compile("val answer = run { delay(1); 42 }")

        // then
        result should {
            be<ResultWithDiagnostics.Success<Class<*>>>()
        }
    }

    @Test
    fun `should keep function of snippet not suspend`() {
        // when
        val result = compile("fun answer() = 42")

        // then
        result should {
            be<ResultWithDiagnostics.Success<Class<*>>>()
            have(value.methods.single { it.name == "answer" }.parameterTypes.isEmpty())
        }
    }

    @Test
    fun `should reject suspend call in lambda which is not suspending`() {
        // when
        val result = compile("val task = Runnable { delay(1) }")

        // then
        result should {
            be<ResultWithDiagnostics.Failure>()
            have(reports.any { it.severity == ScriptDiagnostic.Severity.ERROR && "coroutine" in it.message })
        }
    }

    @Test
    fun `should reject suspend call at top level of snippet without the plugin`() {
        // when
        val result = compile("delay(1)", suspending = false)

        // then
        result should {
            be<ResultWithDiagnostics.Failure>()
            have(reports.any { it.severity == ScriptDiagnostic.Severity.ERROR && "can only be called from a coroutine or another suspend function" in it.message })
        }
    }

    @Test
    fun `should find the roots of the plugin`() {
        // when
        val roots = suspendSnippetPluginRoots

        // then
        assert(roots.isNotEmpty() && roots.all { it.exists() })
    }

}
