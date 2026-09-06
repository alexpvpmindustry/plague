package com.github.kennarddh.mindustry.plague.core.commons

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoundEndRulesTest {
    @Test
    fun `last core is detected while the destroyed core is still registered`() {
        assertTrue(RoundEndRules.isLastCoreBeforeRemoval(1))
        assertFalse(RoundEndRules.isLastCoreBeforeRemoval(2))
    }

    @Test
    fun `last core elimination is claimed once before cleanup can recurse`() {
        val teams = mutableMapOf(7 to "survivor")

        assertEquals(
            "survivor",
            RoundEndRules.claimEliminatedTeam(7, coreCountIncludingDestroyedCore = 1, teams)
        )
        assertEquals(null, RoundEndRules.claimEliminatedTeam(7, coreCountIncludingDestroyedCore = 1, teams))
        assertTrue(teams.isEmpty())
    }

    @Test
    fun `all survivor players share one winner at game over`() {
        val plan = RoundEndRules.createSurvivorVictoryPlan(
            winnerCandidates = listOf(7, 8),
            survivorSideTeams = setOf(7, 8),
            playerTeams = listOf(7, 8, 69),
        )

        assertEquals(7, plan?.winnerTeam)
        assertEquals(listOf(7, 7, 69), plan?.normalizedPlayerTeams)
    }

    @Test
    fun `no survivor candidate means no survivor victory plan`() {
        assertNull(
            RoundEndRules.createSurvivorVictoryPlan<Int>(
                winnerCandidates = emptyList(),
                survivorSideTeams = setOf(7, 8),
                playerTeams = emptyList(),
            )
        )
        assertEquals(RoundWinner.PLAGUE, RoundEndRules.winnerWhenPlagueCoreDestroyed(false))
        assertEquals(RoundWinner.SURVIVORS, RoundEndRules.winnerWhenPlagueCoreDestroyed(true))
    }

    @Test
    fun `game over winner can be claimed only before game over state`() {
        assertTrue(RoundEndRules.canClaimGameOver(PlagueState.PlayingFirstPhase))
        assertTrue(RoundEndRules.canClaimGameOver(PlagueState.SuddenDeath))
        assertFalse(RoundEndRules.canClaimGameOver(PlagueState.GameOver))
    }

    @Test
    fun `second phase can start only once from the first play phase`() {
        assertTrue(RoundEndRules.canStartSecondPhase(PlagueState.PlayingFirstPhase))
        assertFalse(RoundEndRules.canStartSecondPhase(PlagueState.PlayingSecondPhase))
        assertFalse(RoundEndRules.canStartSecondPhase(PlagueState.SuddenDeath))
        assertFalse(RoundEndRules.canStartSecondPhase(PlagueState.GameOver))
    }

    @Test
    fun `time limit can start only once from the second play phase`() {
        assertTrue(RoundEndRules.canStartTimeLimitEnd(PlagueState.PlayingSecondPhase))
        assertFalse(RoundEndRules.canStartTimeLimitEnd(PlagueState.SuddenDeath))
        assertFalse(RoundEndRules.canStartTimeLimitEnd(PlagueState.GameOver))
    }

    @Test
    fun `survivors win when time expires while a survivor team remains`() {
        assertEquals(
            RoundWinner.SURVIVORS,
            RoundEndRules.winnerAtTimeLimit(activeSurvivorTeamCount = 1)
        )
    }

    @Test
    fun `plague wins whenever no survivor remains`() {
        assertEquals(RoundWinner.PLAGUE, RoundEndRules.winnerWhenNoSurvivorRemains())
    }

    @Test
    fun `plague wins when time expires with no survivor team left`() {
        assertEquals(
            RoundWinner.PLAGUE,
            RoundEndRules.winnerAtTimeLimit(activeSurvivorTeamCount = 0)
        )
    }
}
