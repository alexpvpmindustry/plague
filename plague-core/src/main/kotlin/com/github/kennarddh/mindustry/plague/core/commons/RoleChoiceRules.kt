package com.github.kennarddh.mindustry.plague.core.commons

enum class RoleChoice {
    SURVIVOR,
    PLAGUE,
    LATER,
}

object RoleChoiceRules {
    fun shouldOfferMenu(
        isPrepare: Boolean,
        isUnassigned: Boolean,
        hasSurvivorTeam: Boolean,
        isConnected: Boolean,
    ): Boolean = isPrepare && isUnassigned && hasSurvivorTeam && isConnected

    fun canApplySelection(
        shownMapGeneration: Long,
        currentMapGeneration: Long,
        isPrepare: Boolean,
        isUnassigned: Boolean,
        hasSurvivorTeam: Boolean,
        isConnected: Boolean,
    ): Boolean = shownMapGeneration == currentMapGeneration &&
        shouldOfferMenu(isPrepare, isUnassigned, hasSurvivorTeam, isConnected)

    fun choiceForOption(option: Int): RoleChoice = when (option) {
        0 -> RoleChoice.SURVIVOR
        1 -> RoleChoice.PLAGUE
        else -> RoleChoice.LATER
    }
}
