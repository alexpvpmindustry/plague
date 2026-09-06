package com.github.kennarddh.mindustry.plague.core.commons

import arc.util.serialization.JsonReader
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RoundHistoryRecorderTest {
    @Test
    fun `background writer retries after storage recovers without blocking caller`() {
        val root = Files.createTempDirectory("plague-round-history-async-test")
        val historyDirectory = root.resolve("history")
        Files.writeString(historyDirectory, "occupied")
        val historyFile = historyDirectory.resolve("round-history.jsonl")
        val recorder = RoundHistoryRecorder(
            historyFile = historyFile,
            asyncWrites = true,
            roundId = { "round-async" },
        )

        val startedAt = System.nanoTime()
        recorder.startRound("Simplexbeans")
        recorder.observePlayers(RoundPlayerCounts(plague = 1, survivors = 2, other = 0))
        recorder.completeRound(
            winner = RoundSide.PLAGUE,
            reason = RoundEndReason.LAST_SURVIVOR_CORE_DESTROYED,
            endPlayers = RoundPlayerCounts(plague = 1, survivors = 0, other = 0),
        )
        val callMillis = (System.nanoTime() - startedAt) / 1_000_000
        assertTrue(callMillis < 250, "history enqueue took ${callMillis}ms")

        Files.delete(historyDirectory)
        Files.createDirectories(historyDirectory)
        val deadline = System.currentTimeMillis() + 3_000
        while (
            (!Files.exists(historyFile) || Files.readAllLines(historyFile).size < 4) &&
            System.currentTimeMillis() < deadline
        ) Thread.sleep(25)

        assertTrue(Files.exists(historyFile))
        val events = Files.readAllLines(historyFile).map { JsonReader().parse(it).getString("event") }
        assertEquals(listOf("round_started", "round_activity", "round_activity", "round_completed"), events)
    }

    @Test
    fun `no next map saves an interrupted terminal record`() {
        val historyFile = Files.createTempDirectory("plague-round-history-no-map-test").resolve("round-history.jsonl")
        val recorder = RoundHistoryRecorder(
            historyFile = historyFile,
            nowEpochMillis = { 2_000L },
            roundId = { "round-no-map" },
        )

        recorder.startRound("Simplexbeans")
        assertTrue(
            recorder.interruptRound(
                reason = RoundInterruptionReason.NO_NEXT_MAP,
                endPlayers = RoundPlayerCounts(plague = 1, survivors = 2, other = 0),
            )
        )

        val interrupted = Files.readAllLines(historyFile).last()
        assertTrue(interrupted.contains("\"event\":\"round_interrupted\""))
        assertTrue(interrupted.contains("\"reason\":\"no_next_map\""))
        assertCounts(interrupted, "end_players", plague = 1, survivors = 2, other = 0)
    }

    @Test
    fun `non-side manual result has no loser`() {
        val historyFile = Files.createTempDirectory("plague-round-history-manual-test").resolve("round-history.jsonl")
        val recorder = RoundHistoryRecorder(historyFile = historyFile)

        recorder.startRound("Simplexbeans")
        recorder.completeRound(
            winner = RoundSide.OTHER,
            reason = RoundEndReason.MANUAL,
            endPlayers = RoundPlayerCounts(plague = 0, survivors = 0, other = 0),
        )

        val completed = Files.readAllLines(historyFile).last()
        assertTrue(completed.contains("\"winner\":\"none\""))
        assertTrue(completed.contains("\"loser\":null"))
    }

    @Test
    fun `player names are saved by side without connection identifiers`() {
        val historyFile = Files.createTempDirectory("plague-round-history-name-test").resolve("round-history.jsonl")
        val recorder = RoundHistoryRecorder(historyFile = historyFile, roundId = { "round-names" })
        val snapshot = RoundPlayerCounts(
            plague = 1,
            survivors = 1,
            other = 1,
            plagueNames = listOf("Scout\"Bot"),
            survivorNames = listOf("雪 Survivor"),
            otherNames = listOf("Other\\Player"),
        )

        recorder.startRound("Simplexbeans")
        recorder.observePlayers(snapshot)
        recorder.completeRound(
            winner = RoundSide.PLAGUE,
            reason = RoundEndReason.LAST_SURVIVOR_CORE_DESTROYED,
            endPlayers = snapshot,
        )

        val activity = JsonReader().parse(Files.readAllLines(historyFile)[1])
        val players = activity.get("players")
        assertEquals("Scout\"Bot", players.get("names").get("plague").get(0).asString())
        assertEquals("雪 Survivor", players.get("names").get("survivors").get(0).asString())
        assertEquals("Other\\Player", players.get("names").get("other").get(0).asString())
        val allText = Files.readString(historyFile)
        assertFalse(allText.contains("uuid", ignoreCase = true))
        assertFalse(allText.contains("address", ignoreCase = true))
        assertFalse(allText.contains("ip", ignoreCase = true))
    }

    @Test
    fun `player count changes are saved without repeated unchanged records`() {
        val historyFile = Files.createTempDirectory("plague-round-history-activity-test").resolve("round-history.jsonl")
        var now = 1_700_000_000_000L
        val recorder = RoundHistoryRecorder(
            historyFile = historyFile,
            nowEpochMillis = { now },
            roundId = { "round-activity" },
        )

        recorder.startRound("Simplexbeans")
        now += 5_000
        recorder.observePlayers(RoundPlayerCounts(plague = 2, survivors = 1, other = 0))
        now += 5_000
        recorder.observePlayers(RoundPlayerCounts(plague = 2, survivors = 1, other = 0))
        now += 5_000
        recorder.observePlayers(RoundPlayerCounts(plague = 1, survivors = 0, other = 0))

        val lines = Files.readAllLines(historyFile)
        assertEquals(3, lines.size)
        assertTrue(lines[1].contains("\"event\":\"round_activity\""))
        assertTrue(lines[1].contains("\"observed_at_epoch_ms\":1700000005000"))
        assertCounts(lines[1], "players", plague = 2, survivors = 1, other = 0)
        assertCounts(lines[2], "players", plague = 1, survivors = 0, other = 0)
    }

    @Test
    fun `peak players is one real snapshot and never combines different times`() {
        val historyFile = Files.createTempDirectory("plague-round-history-peak-test").resolve("round-history.jsonl")
        val recorder = RoundHistoryRecorder(historyFile = historyFile)

        recorder.startRound("Simplexbeans")
        recorder.observePlayers(RoundPlayerCounts(plague = 4, survivors = 0, other = 0))
        recorder.observePlayers(RoundPlayerCounts(plague = 0, survivors = 3, other = 0))
        recorder.completeRound(
            winner = RoundSide.SURVIVORS,
            reason = RoundEndReason.PLAGUE_CORE_DESTROYED,
            endPlayers = RoundPlayerCounts(plague = 0, survivors = 3, other = 0),
        )

        val completed = Files.readAllLines(historyFile).last()
        assertCounts(completed, "peak_players", plague = 4, survivors = 0, other = 0)
    }

    @Test
    fun `history write failure never blocks the game`() {
        val root = Files.createTempDirectory("plague-round-history-failure-test")
        val historyDirectory = root.resolve("history")
        val historyFile = historyDirectory.resolve("round-history.jsonl")
        val failures = mutableListOf<Exception>()
        val recorder = RoundHistoryRecorder(
            historyFile = historyFile,
            onWriteFailure = { failures.add(it) },
        )

        recorder.startRound("Simplexbeans")
        Files.delete(historyFile)
        Files.delete(historyDirectory)
        Files.writeString(historyDirectory, "occupied")

        val firstAttempt = recorder.completeRound(
            winner = RoundSide.PLAGUE,
            reason = RoundEndReason.NO_SURVIVORS,
            endPlayers = RoundPlayerCounts(plague = 1, survivors = 0, other = 0),
        )

        Files.delete(historyDirectory)
        Files.createDirectories(historyDirectory)
        val retry = recorder.completeRound(
            winner = RoundSide.SURVIVORS,
            reason = RoundEndReason.TIME_LIMIT,
            endPlayers = RoundPlayerCounts(plague = 0, survivors = 1, other = 0),
        )

        assertFalse(firstAttempt)
        assertTrue(retry)
        assertEquals(2, failures.size)
        val persistedTerminal = Files.readAllLines(historyFile).last()
        assertTrue(persistedTerminal.contains("\"event\":\"round_completed\""))
        assertTrue(persistedTerminal.contains("\"winner\":\"plague\""))
        assertTrue(persistedTerminal.contains("\"reason\":\"no_survivors\""))
    }

    @Test
    fun `starting another round marks the previous round interrupted`() {
        val directory = Files.createTempDirectory("plague-round-history-interrupted-test")
        val historyFile = directory.resolve("round-history.jsonl")
        var now = 1_700_000_000_000L
        var nextId = 0
        val recorder = RoundHistoryRecorder(
            historyFile = historyFile,
            nowEpochMillis = { now },
            roundId = { "round-${++nextId}" },
        )

        recorder.startRound("First map")
        recorder.observePlayers(RoundPlayerCounts(plague = 1, survivors = 2, other = 0))
        now += 30_000
        recorder.startRound("Second map")

        val lines = Files.readAllLines(historyFile)
        assertEquals(4, lines.size)
        assertTrue(lines[2].contains("\"event\":\"round_interrupted\""))
        assertTrue(lines[2].contains("\"round_id\":\"round-1\""))
        assertTrue(lines[2].contains("\"reason\":\"new_round_started_before_completion\""))
        assertCounts(lines[2], "end_players", plague = 1, survivors = 2, other = 0)
        assertTrue(lines[3].contains("\"event\":\"round_started\""))
        assertTrue(lines[3].contains("\"round_id\":\"round-2\""))
    }

    @Test
    fun `completed round saves winner loser player counts and timestamps`() {
        val directory = Files.createTempDirectory("plague-round-history-test")
        val historyFile = directory.resolve("round-history.jsonl")
        var now = 1_700_000_000_000L
        val recorder = RoundHistoryRecorder(
            historyFile = historyFile,
            nowEpochMillis = { now },
            roundId = { "round-1" },
        )

        recorder.startRound("Simplexbeans")
        now += 1_000
        recorder.observePlayers(RoundPlayerCounts(plague = 2, survivors = 3, other = 1))
        now += 59_000
        assertTrue(recorder.completeRound(
            winner = RoundSide.SURVIVORS,
            reason = RoundEndReason.PLAGUE_CORE_DESTROYED,
            endPlayers = RoundPlayerCounts(plague = 1, survivors = 2, other = 0),
        ))
        assertFalse(recorder.completeRound(
            winner = RoundSide.PLAGUE,
            reason = RoundEndReason.TIME_LIMIT,
            endPlayers = RoundPlayerCounts(plague = 1, survivors = 2, other = 0),
        ))

        val lines = Files.readAllLines(historyFile)
        assertEquals(4, lines.size)
        assertTrue(lines[0].contains("\"event\":\"round_started\""))
        assertTrue(lines[0].contains("\"round_id\":\"round-1\""))
        assertTrue(lines[0].contains("\"map\":\"Simplexbeans\""))
        val completed = lines.last()
        assertTrue(completed.contains("\"event\":\"round_completed\""))
        assertTrue(completed.contains("\"winner\":\"survivors\""))
        assertTrue(completed.contains("\"loser\":\"plague\""))
        assertTrue(completed.contains("\"reason\":\"plague_core_destroyed\""))
        assertTrue(completed.contains("\"duration_ms\":60000"))
        assertCounts(completed, "end_players", plague = 1, survivors = 2, other = 0)
        assertCounts(completed, "peak_players", plague = 2, survivors = 3, other = 1)
        assertTrue(completed.contains("\"first_player_at_epoch_ms\":1700000001000"))
        assertTrue(completed.contains("\"started_at\":\"2023-11-14T22:13:20Z\""))
        assertTrue(completed.contains("\"ended_at\":\"2023-11-14T22:14:20Z\""))
    }

    @Test
    fun `every record is valid json for hostile map text`() {
        val historyFile = Files.createTempDirectory("plague-round-history-json-test").resolve("round-history.jsonl")
        val hostileMap = "quote\" slash\\ newline\n雪"
        val recorder = RoundHistoryRecorder(historyFile = historyFile, roundId = { "round-json" })

        recorder.startRound(hostileMap)
        recorder.observePlayers(RoundPlayerCounts(plague = 1, survivors = 1, other = 0))
        recorder.completeRound(
            winner = RoundSide.PLAGUE,
            reason = RoundEndReason.LAST_SURVIVOR_CORE_DESTROYED,
            endPlayers = RoundPlayerCounts(plague = 1, survivors = 0, other = 0),
        )

        Files.readAllLines(historyFile).forEach { line ->
            val parsed = JsonReader().parse(line)
            assertEquals(hostileMap, parsed.getString("map"))
        }
    }

    @Test
    fun `partial tail is removed before the next record`() {
        val historyFile = Files.createTempDirectory("plague-round-history-tail-test").resolve("round-history.jsonl")
        Files.writeString(historyFile, "{\"event\":\"old\"}\n{\"broken\":")

        RoundHistoryRecorder(historyFile = historyFile, roundId = { "round-after-repair" })
            .startRound("Simplexbeans")

        val records = Files.readAllLines(historyFile).map { JsonReader().parse(it) }
        assertEquals(2, records.size)
        assertEquals("old", records[0].getString("event"))
        assertEquals("round-after-repair", records[1].getString("round_id"))
    }

    private fun assertCounts(record: String, field: String, plague: Int, survivors: Int, other: Int) {
        val counts = JsonReader().parse(record).get(field)
        assertEquals(plague, counts.getInt("plague"))
        assertEquals(survivors, counts.getInt("survivors"))
        assertEquals(other, counts.getInt("other"))
        assertEquals(plague + survivors + other, counts.getInt("total"))
    }
}
