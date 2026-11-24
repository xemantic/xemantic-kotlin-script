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

import java.io.File
import java.net.JarURLConnection
import java.net.URL

/** The service entry of a compiler plugin, which the compiler scans the jars given with `-Xplugin` for. */
private const val REGISTRAR_SERVICE = "META-INF/services/org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar"

/**
 * The roots of the [SuspendSnippetCompilerPluginRegistrar] to give to the compiler with `-Xplugin`, like `-Xplugin=a.jar`:
 * its jar, or, when not packaged, like in the build of this project, the directory of its classes and the one of its service entry, which differ.
 * The compiler loads the classes of a plugin by a class loader whose parent is the one of the compiler, which loads them first, if it can,
 * so the classes are shared with the host when the compiler is embedded in it.
 */
public val suspendSnippetPluginRoots: List<File> by lazy {
    val registrar = SuspendSnippetCompilerPluginRegistrar::class.java
    val classesRoot = File(registrar.protectionDomain.codeSource.location.toURI())
    val serviceRoot = registrar.classLoader.getResources(REGISTRAR_SERVICE).asSequence().firstOrNull { url ->
        url.openStream().bufferedReader().useLines { lines -> lines.any { it.trim() == registrar.name } }
    }?.let(::rootOf) ?: error("No $REGISTRAR_SERVICE declares ${registrar.name}")
    listOf(classesRoot, serviceRoot).distinct()
}

/** The jar, or the directory, of this [url] of the [REGISTRAR_SERVICE]. */
private fun rootOf(url: URL): File = when (url.protocol) {
    "jar" -> File((url.openConnection() as JarURLConnection).jarFileURL.toURI())
    else -> File(url.toURI()).let { file ->
        generateSequence(file) { it.parentFile }.drop(REGISTRAR_SERVICE.count { it == '/' } + 1).first()
    }
}
