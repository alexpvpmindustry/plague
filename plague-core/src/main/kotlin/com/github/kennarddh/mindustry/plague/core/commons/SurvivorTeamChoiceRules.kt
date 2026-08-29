package com.github.kennarddh.mindustry.plague.core.commons

data class SurvivorTeamCandidate(
    val teamId: Int,
    val locked: Boolean,
    val blacklisted: Boolean,
    val hasCore: Boolean,
)

object SurvivorTeamChoiceRules {
    fun chooseTeamId(candidates: Iterable<SurvivorTeamCandidate>): Int? = candidates
        .asSequence()
        .filter { !it.locked && !it.blacklisted && it.hasCore }
        .minByOrNull { it.teamId }
        ?.teamId
}
