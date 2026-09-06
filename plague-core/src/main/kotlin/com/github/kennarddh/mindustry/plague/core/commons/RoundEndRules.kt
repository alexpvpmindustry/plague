package com.github.kennarddh.mindustry.plague.core.commons

enum class RoundWinner {
    PLAGUE,
    SURVIVORS
}

data class SurvivorVictoryPlan<T>(
    val winnerTeam: T,
    val normalizedPlayerTeams: List<T>,
)

object RoundEndRules {
    /** Mindustry fires BlockDestroyEvent before removing the destroyed core. */
    fun isLastCoreBeforeRemoval(coreCountIncludingDestroyedCore: Int): Boolean =
        coreCountIncludingDestroyedCore == 1

    fun <K, V> claimEliminatedTeam(
        team: K,
        coreCountIncludingDestroyedCore: Int,
        activeTeams: MutableMap<K, V>,
    ): V? {
        if (!isLastCoreBeforeRemoval(coreCountIncludingDestroyedCore)) return null
        return activeTeams.remove(team)
    }

    fun <T> createSurvivorVictoryPlan(
        winnerCandidates: List<T>,
        survivorSideTeams: Set<T>,
        playerTeams: List<T>,
    ): SurvivorVictoryPlan<T>? {
        val winnerTeam = winnerCandidates.firstOrNull { it in survivorSideTeams } ?: return null
        return SurvivorVictoryPlan(
            winnerTeam,
            playerTeams.map { if (it in survivorSideTeams) winnerTeam else it },
        )
    }

    fun canClaimGameOver(state: PlagueState): Boolean = state != PlagueState.GameOver

    fun canStartSecondPhase(state: PlagueState): Boolean =
        state == PlagueState.PlayingFirstPhase

    fun canStartTimeLimitEnd(state: PlagueState): Boolean =
        state == PlagueState.PlayingSecondPhase

    fun winnerWhenPlagueCoreDestroyed(hasSurvivorWinner: Boolean): RoundWinner =
        if (hasSurvivorWinner) RoundWinner.SURVIVORS else RoundWinner.PLAGUE

    fun winnerWhenNoSurvivorRemains(): RoundWinner = RoundWinner.PLAGUE

    fun winnerAtTimeLimit(activeSurvivorTeamCount: Int): RoundWinner =
        if (activeSurvivorTeamCount > 0) RoundWinner.SURVIVORS else RoundWinner.PLAGUE
}
