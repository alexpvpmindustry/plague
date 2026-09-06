package com.github.kennarddh.mindustry.plague.core.commons

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SurvivorTeamChoiceRulesTest {
    @Test
    fun `lowest id open team with a core is selected`() {
        val candidates = listOf(
            SurvivorTeamCandidate(teamId = 9, locked = false, blacklisted = false, hasCore = true),
            SurvivorTeamCandidate(teamId = 7, locked = false, blacklisted = false, hasCore = true),
            SurvivorTeamCandidate(teamId = 8, locked = false, blacklisted = false, hasCore = true),
        )

        assertEquals(7, SurvivorTeamChoiceRules.chooseTeamId(candidates))
    }

    @Test
    fun `locked blacklisted and coreless teams are skipped`() {
        val candidates = listOf(
            SurvivorTeamCandidate(teamId = 7, locked = true, blacklisted = false, hasCore = true),
            SurvivorTeamCandidate(teamId = 8, locked = false, blacklisted = true, hasCore = true),
            SurvivorTeamCandidate(teamId = 9, locked = false, blacklisted = false, hasCore = false),
            SurvivorTeamCandidate(teamId = 10, locked = false, blacklisted = false, hasCore = true),
        )

        assertEquals(10, SurvivorTeamChoiceRules.chooseTeamId(candidates))
    }

    @Test
    fun `no eligible team returns null`() {
        assertNull(
            SurvivorTeamChoiceRules.chooseTeamId(
                listOf(SurvivorTeamCandidate(7, locked = true, blacklisted = false, hasCore = true))
            )
        )
    }
}
