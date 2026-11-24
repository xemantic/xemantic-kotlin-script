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

import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.copy
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.declarations.FirDeclarationOrigin
import org.jetbrains.kotlin.fir.declarations.FirDeclarationStatus
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter
import org.jetbrains.kotlin.fir.extensions.FirStatusTransformerExtension
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol

/** The id of the compiler plugin making the body of a REPL snippet suspending. */
public const val SUSPEND_SNIPPET_PLUGIN_ID: String = "com.xemantic.kotlin.script.suspend-snippet"

/**
 * The compiler plugin making the body of every REPL snippet suspending,
 * so a snippet can call a suspend function directly, like `delay(1)`,
 * loaded by the K2 REPL compiler from the jar of this class, given with `-Xplugin`.
 *
 * The K2 REPL compiler compiles a snippet into a class, its declarations as the members of the class,
 * and its statements, also the initializers of its properties, as the body of a synthetic `$$eval` function,
 * so marking that function `suspend` makes the compiler resolve, check and generate the body
 * like the one of any other suspend function, while the declarations keep their meaning.
 * It is done in FIR, as a call of a suspend function from a function which is not `suspend` is reported there,
 * before any IR is generated.
 *
 * A snippet compiled with it is evaluated by calling `$$eval` with a continuation, which the `K2ReplEvaluator` does not pass.
 */
@OptIn(ExperimentalCompilerApi::class)
public class SuspendSnippetCompilerPluginRegistrar : CompilerPluginRegistrar() {

    override val pluginId: String = SUSPEND_SNIPPET_PLUGIN_ID

    override val supportsK2: Boolean = true

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        FirExtensionRegistrarAdapter.registerExtension(SuspendSnippetFirExtensionRegistrar())
    }

}

/** Registers the [SuspendSnippetStatusTransformer]. */
internal class SuspendSnippetFirExtensionRegistrar : FirExtensionRegistrar() {

    override fun ExtensionRegistrarContext.configurePlugin() {
        +::SuspendSnippetStatusTransformer
    }

}

/**
 * Marks the `$$eval` function of a REPL snippet `suspend`, told by its origin, [FirDeclarationOrigin.Synthetic.ReplEvalFunction],
 * an internal detail of the K2 REPL compiler, re-checked after every Kotlin upgrade.
 */
internal class SuspendSnippetStatusTransformer(session: FirSession) : FirStatusTransformerExtension(session) {

    override fun needTransformStatus(
        declaration: FirDeclaration
    ): Boolean = declaration is FirNamedFunction && declaration.origin == FirDeclarationOrigin.Synthetic.ReplEvalFunction

    override fun transformStatus(
        status: FirDeclarationStatus,
        function: FirNamedFunction,
        containingClass: FirClassLikeSymbol<*>?,
        isLocal: Boolean
    ): FirDeclarationStatus = status.copy(isSuspend = true)

}
