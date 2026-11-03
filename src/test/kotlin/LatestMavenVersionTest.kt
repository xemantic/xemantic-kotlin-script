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
import kotlin.test.Test

class LatestMavenVersionTest {

    @Test
    fun `should select highest stable version in Maven ordering`() {
        // given
        val versions = listOf(
            "1.9.0",
            "1.10.0",
            "1.2.0",
            "1.10.0.Final",
            "0.8.0-0.6.x-compat"
        )

        // when
        val latest = latestMavenVersion(versions)

        // then
        assert(latest?.version == "1.10.0" && latest.stable)
    }

    @Test
    fun `should prefer release version to its qualified variant`() {
        // given the versions of kotlinx-datetime-jvm, where Maven orders an unknown qualifier after the release
        val versions = listOf(
            "0.7.1",
            "0.7.1-0.6.x-compat",
            "0.8.0-rc02",
            "0.8.0-rc02-0.6.x-compat",
            "0.8.0",
            "0.8.0-0.6.x-compat"
        )

        // when
        val latest = latestMavenVersion(versions)

        // then
        assert(latest?.version == "0.8.0" && latest.stable)
    }

    @Test
    fun `should select qualified variant without its release version`() {
        // given
        val versions = listOf(
            "33.3.1-jre",
            "33.4.0-android",
            "33.4.0-jre"
        )

        // when
        val latest = latestMavenVersion(versions)

        // then
        assert(latest?.version == "33.4.0-jre" && latest.stable)
    }

    @Test
    fun `should skip pre-release versions`() {
        // given
        val versions = listOf(
            "1.0",
            "2.0-alpha1",
            "2.0-Beta2",
            "2.0.0-RC",
            "2.0-cr1",
            "2.0-M1",
            "2.0-milestone-3",
            "2.0-preview",
            "2.0-pre1",
            "2.0.0-eap-12",
            "2.0-ea",
            "2.0-dev-123",
            "2.0-nightly",
            "2.0a1",
            "2.0b2"
        )

        // when
        val latest = latestMavenVersion(versions)

        // then
        assert(latest?.version == "1.0" && latest.stable)
    }

    @Test
    fun `should select highest pre-release version without stable one`() {
        // given
        val versions = listOf(
            "2.0-RC1",
            "2.0-beta3",
            "2.0-RC2"
        )

        // when
        val latest = latestMavenVersion(versions)

        // then
        assert(latest?.version == "2.0-RC2" && !latest.stable)
    }

    @Test
    fun `should never select snapshot version`() {
        // given
        val versions = listOf(
            "3.0-SNAPSHOT",
            "2.0-RC1-SNAPSHOT"
        )

        // when
        val latest = latestMavenVersion(versions)

        // then
        assert(latest == null)
    }

}
