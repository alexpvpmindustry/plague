package com.github.kennarddh.mindustry.plague.core.commons

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RoleChoiceRulesTest {
    @Test
    fun `menu is offered only to connected unassigned players after the first survivor exists during prepare`() {
        assertTrue(
            RoleChoiceRules.shouldOfferMenu(
                isPrepare = true,
                isUnassigned = true,
                hasSurvivorTeam = true,
                isConnected = true,
            )
        )
        assertFalse(RoleChoiceRules.shouldOfferMenu(false, true, true, true))
        assertFalse(RoleChoiceRules.shouldOfferMenu(true, false, true, true))
        assertFalse(RoleChoiceRules.shouldOfferMenu(true, true, false, true))
        assertFalse(RoleChoiceRules.shouldOfferMenu(true, true, true, false))
    }

    @Test
    fun `selection is rejected after map phase team or connection changes`() {
        assertTrue(RoleChoiceRules.canApplySelection(4, 4, true, true, true, true))
        assertFalse(RoleChoiceRules.canApplySelection(4, 5, true, true, true, true))
        assertFalse(RoleChoiceRules.canApplySelection(4, 4, false, true, true, true))
        assertFalse(RoleChoiceRules.canApplySelection(4, 4, true, false, true, true))
        assertFalse(RoleChoiceRules.canApplySelection(4, 4, true, true, false, true))
        assertFalse(RoleChoiceRules.canApplySelection(4, 4, true, true, true, false))
    }

    @Test
    fun `buttons map to roles and close fails safe to later`() {
        assertEquals(RoleChoice.SURVIVOR, RoleChoiceRules.choiceForOption(0))
        assertEquals(RoleChoice.PLAGUE, RoleChoiceRules.choiceForOption(1))
        assertEquals(RoleChoice.LATER, RoleChoiceRules.choiceForOption(2))
        assertEquals(RoleChoice.LATER, RoleChoiceRules.choiceForOption(-1))
        assertEquals(RoleChoice.LATER, RoleChoiceRules.choiceForOption(99))
    }
}
