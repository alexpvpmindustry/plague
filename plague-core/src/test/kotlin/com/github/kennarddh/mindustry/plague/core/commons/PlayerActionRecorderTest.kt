package com.github.kennarddh.mindustry.plague.core.commons

import arc.util.serialization.JsonReader
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerActionRecorderTest {
    @Test
    fun `build selection uses the construct recipe instead of the construct proxy`() {
        assertEquals("copper-wall", PlayerActionSamplingRules.selectedBlockName("copper-wall", "build1"))
        assertEquals("router", PlayerActionSamplingRules.selectedBlockName(null, "router"))
        assertFalse(PlayerActionSamplingRules.shouldGeneralHandlerRecordBuildSelection(isBlueTeam = true, breaking = false))
        assertTrue(PlayerActionSamplingRules.shouldGeneralHandlerRecordBuildSelection(isBlueTeam = true, breaking = true))
        assertTrue(PlayerActionSamplingRules.shouldGeneralHandlerRecordBuildSelection(isBlueTeam = false, breaking = false))
    }

    @Test
    fun `free disk floor includes the bytes about to be written`() {
        val logFile = Files.createTempDirectory("plague-player-action-floor-test").resolve("player-actions.jsonl")
        val failures = mutableListOf<Throwable>()
        val recorder = PlayerActionRecorder(
            logFile = logFile,
            nowEpochMillis = { 1_700_000_000_000L },
            onWriteFailure = failures::add,
            asyncWrites = false,
            maxLogBytes = Long.MAX_VALUE,
            minimumFreeBytes = 80,
            usableSpace = { 100 },
        )

        assertFalse(recorder.recordAction(PlayerGameplayAction(null, "Map", "Player", RoundSide.OTHER, "tap")))
        assertFalse(Files.exists(logFile))
        assertEquals(1, failures.size)
    }

    @Test
    fun `shutdown keeps retrying the ordered head batch during the flush window`() {
        val root = Files.createTempDirectory("plague-player-action-shutdown-test")
        val blockedParent = root.resolve("blocked")
        Files.writeString(blockedParent, "occupied")
        val logFile = blockedParent.resolve("player-actions.jsonl")
        val recorder = PlayerActionRecorder(
            logFile = logFile,
            nowEpochMillis = { 1_700_000_000_000L },
            onWriteFailure = {},
            asyncWrites = true,
            minimumFreeBytes = 0,
        )
        assertTrue(recorder.recordAction(PlayerGameplayAction(null, "Map", "First", RoundSide.OTHER, "tap")))
        assertTrue(recorder.recordAction(PlayerGameplayAction(null, "Map", "Second", RoundSide.OTHER, "tap")))

        val closer = Thread { recorder.closeAndFlush() }.apply { start() }
        Thread.sleep(300)
        Files.delete(blockedParent)
        Files.createDirectories(blockedParent)
        closer.join(5_000)

        assertFalse(closer.isAlive)
        val names = Files.readAllLines(logFile).map { JsonReader().parse(it).getString("player_name") }
        assertEquals(listOf("First", "Second"), names)
    }

    @Test
    fun `motion sampling is limited to ten times per second`() {
        assertFalse(PlayerActionSamplingRules.shouldSample(nowMillis = 1_099, lastSampleMillis = 1_000))
        assertTrue(PlayerActionSamplingRules.shouldSample(nowMillis = 1_100, lastSampleMillis = 1_000))
        assertTrue(PlayerActionSamplingRules.shouldSample(nowMillis = 2_000, lastSampleMillis = 0))
    }

    @Test
    fun `background writer keeps order and does not block gameplay while storage recovers`() {
        val root = Files.createTempDirectory("plague-player-action-async-test")
        val blockedDirectory = root.resolve("actions")
        Files.writeString(blockedDirectory, "occupied")
        val logFile = blockedDirectory.resolve("player-actions.jsonl")
        val recorder = PlayerActionRecorder(
            logFile = logFile,
            asyncWrites = true,
            minimumFreeBytes = 0,
        )
        val motion = PlayerMotionSnapshot(
            roundId = "round-async",
            map = "Simplexbeans",
            playerName = "ScoutBot",
            side = RoundSide.SURVIVORS,
            x = 1f,
            y = 2f,
            aimX = 3f,
            aimY = 4f,
            shooting = false,
            boosting = false,
            unitType = "mono",
        )
        val action = PlayerGameplayAction(
            roundId = "round-async",
            map = "Simplexbeans",
            playerName = "ScoutBot",
            side = RoundSide.SURVIVORS,
            actionType = "tap",
            x = 1f,
            y = 2f,
        )

        assertTrue(recorder.recordMotion(motion))
        val startedAt = System.nanoTime()
        assertTrue(recorder.recordMotion(motion))
        assertTrue(recorder.recordAction(action))
        val callMillis = (System.nanoTime() - startedAt) / 1_000_000
        assertTrue(callMillis < 250, "action enqueue took ${callMillis}ms")

        Files.delete(blockedDirectory)
        Files.createDirectories(blockedDirectory)
        val deadline = System.currentTimeMillis() + 3_000
        while ((!Files.exists(logFile) || Files.readAllLines(logFile).size < 3) && System.currentTimeMillis() < deadline) {
            Thread.sleep(25)
        }

        val events = Files.readAllLines(logFile).map { JsonReader().parse(it).getString("event") }
        assertEquals(listOf("motion", "motion", "action"), events)
    }

    @Test
    fun `logging stops before the configured file or free disk limits are crossed`() {
        val directory = Files.createTempDirectory("plague-player-action-limit-test")
        val logFile = directory.resolve("player-actions.jsonl")
        val failures = mutableListOf<String>()
        val recorder = PlayerActionRecorder(
            logFile = logFile,
            asyncWrites = false,
            maxLogBytes = 0,
            minimumFreeBytes = 0,
            onWriteFailure = { failures.add(it.message ?: "unknown") },
        )

        val accepted = recorder.recordMotion(
            PlayerMotionSnapshot(
                roundId = null,
                map = "Simplexbeans",
                playerName = "ScoutBot",
                side = RoundSide.SURVIVORS,
                x = 0f,
                y = 0f,
                aimX = 0f,
                aimY = 0f,
                shooting = false,
                boosting = false,
                unitType = null,
            )
        )

        assertFalse(accepted)
        assertFalse(Files.exists(logFile))
        assertTrue(failures.single().contains("limit", ignoreCase = true))
    }

    @Test
    fun `gameplay action records the visible player and target without hidden identifiers`() {
        val logFile = Files.createTempDirectory("plague-player-action-event-test").resolve("player-actions.jsonl")
        val recorder = PlayerActionRecorder(logFile = logFile, nowEpochMillis = { 1_700_000_001_000L }, asyncWrites = false)

        assertTrue(recorder.recordAction(
            PlayerGameplayAction(
                roundId = "round-2",
                map = "Bridge",
                playerName = "Builder",
                side = RoundSide.PLAGUE,
                actionType = "build_complete",
                x = 80f,
                y = 96f,
                tileX = 10,
                tileY = 12,
                block = "router",
                item = "copper",
                amount = 4,
                unitType = "dagger",
                command = "move",
            )
        ))

        val text = Files.readString(logFile)
        val record = JsonReader().parse(text.trim())
        assertEquals("action", record.getString("event"))
        assertEquals("build_complete", record.getString("action_type"))
        assertEquals("Builder", record.getString("player_name"))
        assertEquals(10, record.getInt("tile_x"))
        assertEquals(12, record.getInt("tile_y"))
        assertEquals("router", record.getString("block"))
        assertEquals("copper", record.getString("item"))
        assertEquals(4, record.getInt("amount"))
        assertEquals("dagger", record.getString("unit_type"))
        assertEquals("move", record.getString("command"))
        assertFalse(text.contains("uuid", ignoreCase = true))
        assertFalse(text.contains("address", ignoreCase = true))
    }

    @Test
    fun `high frequency motion records visible name position aim and controls`() {
        val logFile = Files.createTempDirectory("plague-player-actions-test").resolve("player-actions.jsonl")
        val recorder = PlayerActionRecorder(
            logFile = logFile,
            nowEpochMillis = { 1_700_000_000_123L },
            asyncWrites = false,
        )

        assertTrue(recorder.recordMotion(
            PlayerMotionSnapshot(
                roundId = "round-1",
                map = "Space Virus",
                playerName = "Scout\"Bot",
                side = RoundSide.SURVIVORS,
                x = 10.25f,
                y = 20.5f,
                aimX = 30.75f,
                aimY = 40.125f,
                shooting = true,
                boosting = false,
                unitType = "mono",
            )
        ))

        val text = Files.readString(logFile)
        val record = JsonReader().parse(text.trim())
        assertEquals("motion", record.getString("event"))
        assertEquals("Scout\"Bot", record.getString("player_name"))
        assertEquals("survivors", record.getString("side"))
        assertEquals("round-1", record.getString("round_id"))
        assertEquals("Space Virus", record.getString("map"))
        assertEquals(10.25f, record.getFloat("x"))
        assertEquals(20.5f, record.getFloat("y"))
        assertEquals(30.75f, record.getFloat("aim_x"))
        assertEquals(40.125f, record.getFloat("aim_y"))
        assertTrue(record.getBoolean("shooting"))
        assertFalse(record.getBoolean("boosting"))
        assertEquals("mono", record.getString("unit_type"))
        assertFalse(text.contains("uuid", ignoreCase = true))
        assertFalse(text.contains("address", ignoreCase = true))
        assertFalse(text.contains("ip", ignoreCase = true))
    }
}
