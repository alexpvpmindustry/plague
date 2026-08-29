package com.github.kennarddh.mindustry.plague.core.commons

import kotlin.test.Test
import kotlin.test.assertEquals

class CornerSpawnRulesTest {
    @Test
    fun `corner anchors stay inside the map margin`() {
        assertEquals(
            listOf(
                MapPoint(5, 5),
                MapPoint(94, 5),
                MapPoint(94, 74),
                MapPoint(5, 74),
            ),
            CornerSpawnRules.cornerAnchors(width = 100, height = 80, margin = 5)
        )
    }

    @Test
    fun `candidate search is unique in bounds and strictly budgeted`() {
        val candidates = CornerSpawnRules.boundedCandidates(
            anchors = CornerSpawnRules.cornerAnchors(4096, 4096, 6),
            width = 4096,
            height = 4096,
            maximumRadius = 96,
            stride = 4,
            maximumCandidates = 256,
        )

        assertEquals(256, candidates.size)
        assertEquals(candidates.size, candidates.toSet().size)
        assertEquals(true, candidates.all { it.x in 0 until 4096 && it.y in 0 until 4096 })
    }
}
