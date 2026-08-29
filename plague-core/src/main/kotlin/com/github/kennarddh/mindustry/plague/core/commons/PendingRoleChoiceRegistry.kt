package com.github.kennarddh.mindustry.plague.core.commons

import java.util.concurrent.ConcurrentHashMap

class PendingRoleChoice<P : Any, C : Any>(
    val mapGeneration: Long,
    val offerId: Int,
    val player: P,
    val connection: C,
)

class PendingRoleChoiceRegistry<K : Any, P : Any, C : Any> {
    private val pending = ConcurrentHashMap<K, PendingRoleChoice<P, C>>()

    fun offer(key: K, mapGeneration: Long, offerId: Int, player: P, connection: C): Boolean =
        pending.putIfAbsent(key, PendingRoleChoice(mapGeneration, offerId, player, connection)) == null

    fun peek(key: K): PendingRoleChoice<P, C>? = pending[key]

    fun consume(key: K, offerId: Int, player: P, connection: C): PendingRoleChoice<P, C>? =
        removeMatching(key, player, connection, offerId)

    fun cancel(key: K, player: P, connection: C): PendingRoleChoice<P, C>? =
        removeMatching(key, player, connection, null)

    fun clear(): List<PendingRoleChoice<P, C>> {
        val removed = pending.values.toList()
        pending.clear()
        return removed
    }

    private fun removeMatching(
        key: K,
        player: P,
        connection: C,
        offerId: Int?,
    ): PendingRoleChoice<P, C>? {
        while (true) {
            val current = pending[key] ?: return null
            if (
                current.player !== player ||
                current.connection !== connection ||
                offerId != null && current.offerId != offerId
            ) return null
            if (pending.remove(key, current)) return current
        }
    }
}
