package com.github.kennarddh.mindustry.plague.core.commons

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

enum class RoundSide(val value: String) {
    PLAGUE("plague"),
    SURVIVORS("survivors"),
    OTHER("none"),
}

enum class RoundEndReason(val value: String) {
    LAST_SURVIVOR_CORE_DESTROYED("last_survivor_core_destroyed"),
    PLAGUE_CORE_DESTROYED("plague_core_destroyed"),
    ALL_SURVIVOR_PLAYERS_LEFT("all_survivor_players_left"),
    NO_SURVIVORS("no_survivors"),
    TIME_LIMIT("time_limit"),
    MANUAL("manual"),
}

enum class RoundInterruptionReason(val value: String) {
    NEW_ROUND_STARTED_BEFORE_COMPLETION("new_round_started_before_completion"),
    NO_NEXT_MAP("no_next_map"),
    NEXT_MAP_SELECTION_FAILED("next_map_selection_failed"),
    GAME_OVER_PUBLICATION_FAILED("game_over_publication_failed"),
    ROUND_RESOLUTION_FAILED("round_resolution_failed"),
    POST_CLAIM_TRANSITION_FAILED("post_claim_transition_failed"),
}

data class RoundPlayerCounts(
    val plague: Int,
    val survivors: Int,
    val other: Int,
    val plagueNames: List<String> = emptyList(),
    val survivorNames: List<String> = emptyList(),
    val otherNames: List<String> = emptyList(),
) {
    val total: Int = plague + survivors + other
}

class RoundHistoryRecorder(
    private val historyFile: Path,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val roundId: () -> String = { UUID.randomUUID().toString() },
    private val onWriteFailure: (Exception) -> Unit = {},
    asyncWrites: Boolean = false,
) {
    private val asyncWriter = if (asyncWrites) RetryingAsyncJsonlWriter(historyFile, onWriteFailure) else null
    private data class ActiveRound(
        val id: String,
        val map: String,
        val startedAtEpochMillis: Long,
        var firstPlayerAtEpochMillis: Long? = null,
        var peakPlayers: RoundPlayerCounts = RoundPlayerCounts(0, 0, 0),
        var lastPlayers: RoundPlayerCounts = RoundPlayerCounts(0, 0, 0),
        var pendingTerminalLine: String? = null,
    )

    private var activeRound: ActiveRound? = null

    @Synchronized
    fun currentRoundId(): String? = activeRound?.id

    @Synchronized
    fun startRound(map: String) {
        val startedAt = nowEpochMillis()
        activeRound?.let { interrupted ->
            interrupted.pendingTerminalLine?.let { terminalLine ->
                if (!appendLine(terminalLine)) return
                activeRound = null
            }
            if (activeRound != null && !appendInterruptedRound(
                    interrupted,
                    startedAt,
                    RoundInterruptionReason.NEW_ROUND_STARTED_BEFORE_COMPLETION,
                    interrupted.lastPlayers,
                )
            ) return
            activeRound = null
        }
        val round = ActiveRound(roundId(), map, startedAt)
        if (
            appendLine(
                "{" +
                    "\"schema_version\":1," +
                    "\"event\":\"round_started\"," +
                    "\"round_id\":${jsonString(round.id)}," +
                    "\"map\":${jsonString(round.map)}," +
                    "\"started_at\":${jsonString(timestamp(startedAt))}," +
                    "\"started_at_epoch_ms\":$startedAt" +
                    "}"
            )
        ) {
            activeRound = round
        }
    }

    @Synchronized
    fun observePlayers(counts: RoundPlayerCounts) {
        val round = activeRound ?: return
        if (round.pendingTerminalLine != null) return
        if (counts == round.lastPlayers) return

        val observedAt = nowEpochMillis()
        if (counts.total > 0 && round.firstPlayerAtEpochMillis == null) {
            round.firstPlayerAtEpochMillis = observedAt
        }
        if (counts.total > round.peakPlayers.total) {
            round.peakPlayers = counts
        }
        if (
            appendLine(
                "{" +
                    "\"schema_version\":1," +
                    "\"event\":\"round_activity\"," +
                    "\"round_id\":${jsonString(round.id)}," +
                    "\"map\":${jsonString(round.map)}," +
                    "\"observed_at\":${jsonString(timestamp(observedAt))}," +
                    "\"observed_at_epoch_ms\":$observedAt," +
                    "\"elapsed_ms\":${observedAt - round.startedAtEpochMillis}," +
                    "\"players\":${countsJson(counts)}" +
                    "}",
                critical = false,
            )
        ) {
            round.lastPlayers = counts
        }
    }

    @Synchronized
    fun completeRound(
        winner: RoundSide,
        reason: RoundEndReason,
        endPlayers: RoundPlayerCounts,
    ): Boolean {
        val round = activeRound ?: return false
        round.pendingTerminalLine?.let { pending ->
            val persisted = appendLine(pending)
            if (persisted) activeRound = null
            return persisted
        }
        observePlayers(endPlayers)
        val endedAt = nowEpochMillis()
        val terminalLine =
            "{" +
                "\"schema_version\":1," +
                "\"event\":\"round_completed\"," +
                "\"round_id\":${jsonString(round.id)}," +
                "\"map\":${jsonString(round.map)}," +
                "\"started_at\":${jsonString(timestamp(round.startedAtEpochMillis))}," +
                "\"started_at_epoch_ms\":${round.startedAtEpochMillis}," +
                "\"ended_at\":${jsonString(timestamp(endedAt))}," +
                "\"ended_at_epoch_ms\":$endedAt," +
                "\"duration_ms\":${endedAt - round.startedAtEpochMillis}," +
                "\"first_player_at_epoch_ms\":${round.firstPlayerAtEpochMillis ?: "null"}," +
                "\"winner\":${jsonString(winner.value)}," +
                "\"loser\":${loserFor(winner)?.let(::jsonString) ?: "null"}," +
                "\"reason\":${jsonString(reason.value)}," +
                "\"end_players\":${countsJson(endPlayers)}," +
                "\"peak_players\":${countsJson(round.peakPlayers)}" +
                "}"
        round.pendingTerminalLine = terminalLine
        val persisted = appendLine(terminalLine)
        if (persisted) activeRound = null
        return persisted
    }

    @Synchronized
    fun interruptRound(
        reason: RoundInterruptionReason,
        endPlayers: RoundPlayerCounts,
    ): Boolean {
        val round = activeRound ?: return false
        if (endPlayers.total > 0 && round.firstPlayerAtEpochMillis == null) {
            round.firstPlayerAtEpochMillis = nowEpochMillis()
        }
        if (endPlayers.total > round.peakPlayers.total) round.peakPlayers = endPlayers
        val persisted = appendInterruptedRound(round, nowEpochMillis(), reason, endPlayers)
        if (persisted) activeRound = null
        return persisted
    }

    private fun appendInterruptedRound(
        round: ActiveRound,
        endedAt: Long,
        reason: RoundInterruptionReason,
        endPlayers: RoundPlayerCounts,
    ): Boolean {
        val terminalLine = round.pendingTerminalLine ?: (
            "{" +
            "\"schema_version\":1," +
            "\"event\":\"round_interrupted\"," +
            "\"round_id\":${jsonString(round.id)}," +
            "\"map\":${jsonString(round.map)}," +
            "\"started_at\":${jsonString(timestamp(round.startedAtEpochMillis))}," +
            "\"started_at_epoch_ms\":${round.startedAtEpochMillis}," +
            "\"ended_at\":${jsonString(timestamp(endedAt))}," +
            "\"ended_at_epoch_ms\":$endedAt," +
            "\"duration_ms\":${endedAt - round.startedAtEpochMillis}," +
            "\"first_player_at_epoch_ms\":${round.firstPlayerAtEpochMillis ?: "null"}," +
            "\"reason\":${jsonString(reason.value)}," +
            "\"end_players\":${countsJson(endPlayers)}," +
            "\"peak_players\":${countsJson(round.peakPlayers)}" +
            "}"
        )
        round.pendingTerminalLine = terminalLine
        return appendLine(terminalLine)
    }

    private fun appendLine(line: String, critical: Boolean = true): Boolean {
        asyncWriter?.let {
            return it.enqueue(line, critical)
        }
        return try {
            appendJsonLineSafely(historyFile, line)
            true
        } catch (error: Exception) {
            runCatching { onWriteFailure(error) }
            false
        }
    }

    private fun countsJson(counts: RoundPlayerCounts) =
        "{\"plague\":${counts.plague},\"survivors\":${counts.survivors},\"other\":${counts.other},\"total\":${counts.total}," +
            "\"names\":{" +
            "\"plague\":${namesJson(counts.plagueNames)}," +
            "\"survivors\":${namesJson(counts.survivorNames)}," +
            "\"other\":${namesJson(counts.otherNames)}" +
            "}}"

    private fun namesJson(names: List<String>) = names.joinToString(prefix = "[", postfix = "]") { jsonString(it) }

    private fun loserFor(winner: RoundSide): String? = when (winner) {
        RoundSide.PLAGUE -> RoundSide.SURVIVORS.value
        RoundSide.SURVIVORS -> RoundSide.PLAGUE.value
        RoundSide.OTHER -> null
    }

    private fun timestamp(epochMillis: Long) = Instant.ofEpochMilli(epochMillis).toString()

    private fun jsonString(value: String): String = buildString {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000c' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) {
                    append("\\u%04x".format(character.code))
                } else {
                    append(character)
                }
            }
        }
        append('"')
    }
}

private fun appendJsonLineSafely(historyFile: Path, line: String) {
    historyFile.parent?.let { Files.createDirectories(it) }
    val bytes = "$line\n".toByteArray(StandardCharsets.UTF_8)
    FileChannel.open(
        historyFile,
        StandardOpenOption.CREATE,
        StandardOpenOption.READ,
        StandardOpenOption.WRITE,
    ).use { channel ->
        repairPartialTail(channel)
        val originalSize = channel.size()
        channel.position(originalSize)
        try {
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        } catch (error: Exception) {
            runCatching {
                channel.truncate(originalSize)
                channel.force(true)
            }
            throw error
        }
    }
}

private fun repairPartialTail(channel: FileChannel) {
    val size = channel.size()
    if (size == 0L) return
    val byte = ByteBuffer.allocate(1)
    channel.read(byte, size - 1)
    if (byte.array()[0] == '\n'.code.toByte()) return

    var position = size - 1
    while (position >= 0) {
        byte.clear()
        channel.read(byte, position)
        if (byte.array()[0] == '\n'.code.toByte()) {
            channel.truncate(position + 1)
            return
        }
        position--
    }
    channel.truncate(0)
}

private class RetryingAsyncJsonlWriter(
    private val historyFile: Path,
    private val onWriteFailure: (Exception) -> Unit,
) {
    private data class PendingLine(val line: String, val critical: Boolean)

    private val queue = LinkedBlockingQueue<PendingLine>(MAX_PENDING_LINES)
    private val worker = Thread(::writeQueuedLines, "plague-round-history-writer").apply { isDaemon = true }
    @Volatile private var closing = false
    @Volatile private var shutdownDeadlineMillis = Long.MAX_VALUE
    @Volatile private var lastFailureReportMillis = 0L

    init {
        worker.start()
        Runtime.getRuntime().addShutdownHook(
            Thread({ closeAndFlush() }, "plague-round-history-shutdown")
        )
    }

    @Synchronized
    fun enqueue(line: String, critical: Boolean): Boolean {
        if (closing) return false
        if (!critical && queue.remainingCapacity() <= RESERVED_CRITICAL_LINES) {
            reportFailure(IOException("Round history activity queue is full; the latest counts will be retried."))
            return false
        }
        if (queue.offer(PendingLine(line, critical))) return true

        reportFailure(IOException("Round history critical queue is full; terminal state remains eligible for retry."))
        return false
    }

    private fun writeQueuedLines() {
        while (true) {
            val pending = try {
                queue.poll(POLL_MILLIS, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                null
            }
            if (pending == null) {
                if (closing && queue.isEmpty()) return
                continue
            }

            while (!writeLine(pending.line)) {
                if (closing && System.currentTimeMillis() >= shutdownDeadlineMillis) return
                try {
                    Thread.sleep(RETRY_DELAY_MILLIS)
                } catch (_: InterruptedException) {
                    // Recheck the shutdown deadline, then retry this head record.
                }
            }
        }
    }

    private fun writeLine(line: String): Boolean = try {
        appendJsonLineSafely(historyFile, line)
        true
    } catch (error: Exception) {
        reportFailure(error)
        false
    }

    @Synchronized
    private fun closeAndFlush() {
        closing = true
        shutdownDeadlineMillis = System.currentTimeMillis() + SHUTDOWN_FLUSH_MILLIS
        worker.interrupt()
        runCatching { worker.join(SHUTDOWN_FLUSH_MILLIS + POLL_MILLIS) }
    }

    private fun reportFailure(error: Exception) {
        val now = System.currentTimeMillis()
        if (now - lastFailureReportMillis < FAILURE_REPORT_INTERVAL_MILLIS) return
        lastFailureReportMillis = now
        runCatching { onWriteFailure(error) }
    }

    companion object {
        private const val MAX_PENDING_LINES = 4_096
        private const val RESERVED_CRITICAL_LINES = 512
        private const val POLL_MILLIS = 250L
        private const val RETRY_DELAY_MILLIS = 1_000L
        private const val SHUTDOWN_FLUSH_MILLIS = 5_000L
        private const val FAILURE_REPORT_INTERVAL_MILLIS = 60_000L
    }
}
