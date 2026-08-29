package com.github.kennarddh.mindustry.plague.core.commons

import kotlin.test.Test
import kotlin.test.assertEquals

class PrepareTimerTest {
    @Test
    fun `empty server keeps full prepare window for first player`() {
        val timer = PrepareTimer()

        assertEquals(0L, timer.elapsedMillis(nowMillis = 60_000L, playerCount = 0))
        assertEquals(0L, timer.elapsedMillis(nowMillis = 90_000L, playerCount = 1))
        assertEquals(30_000L, timer.elapsedMillis(nowMillis = 120_000L, playerCount = 1))
    }

    @Test
    fun `empty server resets prepare window for next player`() {
        val timer = PrepareTimer()

        timer.elapsedMillis(nowMillis = 10_000L, playerCount = 1)
        assertEquals(30_000L, timer.elapsedMillis(nowMillis = 40_000L, playerCount = 1))
        assertEquals(0L, timer.elapsedMillis(nowMillis = 50_000L, playerCount = 0))
        assertEquals(0L, timer.elapsedMillis(nowMillis = 80_000L, playerCount = 1))
    }
}