package com.github.kennarddh.mindustry.plague.core.commons

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PendingRoleChoiceRegistryTest {
    private class Identity

    @Test
    fun `duplicate offer for one live connection is rejected`() {
        val registry = PendingRoleChoiceRegistry<String, Identity, Identity>()
        val player = Identity()
        val connection = Identity()

        assertTrue(registry.offer("uuid", 4, 10, player, connection))
        assertFalse(registry.offer("uuid", 4, 10, player, connection))
        assertEquals(4, registry.peek("uuid")?.mapGeneration)
    }

    @Test
    fun `completed later choice blocks duplicate confirmation until map cleanup`() {
        val completed = PendingRoleChoiceRegistry<String, Identity, Identity>()
        val player = Identity()
        val connection = Identity()

        assertTrue(completed.offer("uuid", 4, 10, player, connection))
        assertFalse(completed.offer("uuid", 4, 10, player, connection))
        assertEquals(10, completed.peek("uuid")?.offerId)

        completed.clear()
        assertTrue(completed.offer("uuid", 5, 11, player, connection))
    }

    @Test
    fun `delayed callback from old connection cannot consume reconnect menu`() {
        val registry = PendingRoleChoiceRegistry<String, Identity, Identity>()
        val oldPlayer = Identity()
        val oldConnection = Identity()
        val newPlayer = Identity()
        val newConnection = Identity()

        assertTrue(registry.offer("uuid", 4, 10, oldPlayer, oldConnection))
        assertTrue(registry.cancel("uuid", oldPlayer, oldConnection) != null)
        assertTrue(registry.offer("uuid", 4, 11, newPlayer, newConnection))

        assertNull(registry.consume("uuid", 10, oldPlayer, oldConnection))
        assertTrue(registry.consume("uuid", 11, newPlayer, newConnection) != null)
    }

    @Test
    fun `old map callback cannot consume new offer on same connection`() {
        val registry = PendingRoleChoiceRegistry<String, Identity, Identity>()
        val player = Identity()
        val connection = Identity()

        assertTrue(registry.offer("uuid", 4, 10, player, connection))
        registry.clear()
        assertTrue(registry.offer("uuid", 5, 11, player, connection))

        assertNull(registry.consume("uuid", 10, player, connection))
        assertEquals(11, registry.peek("uuid")?.offerId)
        assertTrue(registry.consume("uuid", 11, player, connection) != null)
    }

    @Test
    fun `command cancellation only removes matching connection`() {
        val registry = PendingRoleChoiceRegistry<String, Identity, Identity>()
        val player = Identity()
        val connection = Identity()

        assertTrue(registry.offer("uuid", 7, 12, player, connection))
        assertNull(registry.cancel("uuid", Identity(), Identity()))
        assertTrue(registry.cancel("uuid", player, connection) != null)
        assertNull(registry.peek("uuid"))
    }

    @Test
    fun `map or phase cleanup drains all pending menus`() {
        val registry = PendingRoleChoiceRegistry<String, Identity, Identity>()
        assertTrue(registry.offer("a", 1, 20, Identity(), Identity()))
        assertTrue(registry.offer("b", 1, 21, Identity(), Identity()))

        assertEquals(2, registry.clear().size)
        assertNull(registry.peek("a"))
        assertNull(registry.peek("b"))
    }
}
