package com.github.kennarddh.mindustry.plague.core.commons

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SurvivorStartRulesTest {
    @Test
    fun `core must be at least the minimum tile distance from plague`() {
        assertFalse(SurvivorStartRules.isFarEnoughFromPlague(799.9f, tileSize = 8, minimumTiles = 100))
        assertTrue(SurvivorStartRules.isFarEnoughFromPlague(800f, tileSize = 8, minimumTiles = 100))
    }
}
