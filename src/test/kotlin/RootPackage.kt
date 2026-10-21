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

/** In the root package, referred to by the simple name, which a script declaration of the same name shadows. */
class RootConfig(val v: Int) {
    class Entry(val config: RootConfig)
}

/** In the root package, referred to by a script compiled in a package, see [RootConfig]. */
class RootLabel(val text: String)

/** Compiled to the file facade class `RootPackageKt` of the root package. */
fun rootFunction(): Int = 1

/** In the root package, named like a hard keyword. */
@Suppress("ClassName")
class `in`(val v: Int)
