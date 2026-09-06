package com.github.kennarddh.mindustry.plague.core.commons

class PrepareTimer {
    private var firstPlayerAtMillis: Long? = null

    fun elapsedMillis(nowMillis: Long, playerCount: Int): Long {
        if (playerCount == 0) {
            firstPlayerAtMillis = null
            return 0
        }

        val startedAt = firstPlayerAtMillis ?: nowMillis.also { firstPlayerAtMillis = it }
        return (nowMillis - startedAt).coerceAtLeast(0)
    }
}
