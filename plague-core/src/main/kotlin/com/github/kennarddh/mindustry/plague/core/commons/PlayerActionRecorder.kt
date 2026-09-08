package com.github.kennarddh.mindustry.plague.core.commons

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

object PlayerActionSamplingRules {
    const val SAMPLE_INTERVAL_MILLIS = 100L

    fun shouldSample(nowMillis: Long, lastSampleMillis: Long): Boolean =
        nowMillis - lastSampleMillis >= SAMPLE_INTERVAL_MILLIS

    fun selectedBlockName(constructRecipeName: String?, tileBlockName: String): String =
        constructRecipeName ?: tileBlockName

    fun shouldGeneralHandlerRecordBuildSelection(isBlueTeam: Boolean, breaking: Boolean): Boolean =
        !isBlueTeam || breaking
}

data class PlayerMotionSnapshot(
    val roundId: String?,
    val map: String,
    val playerName: String,
    val side: RoundSide,
    val x: Float,
    val y: Float,
    val aimX: Float,
    val aimY: Float,
    val shooting: Boolean,
    val boosting: Boolean,
    val unitType: String?,
)

data class PlayerGameplayAction(
    val roundId: String?,
    val map: String,
    val playerName: String,
    val side: RoundSide,
    val actionType: String,
    val x: Float = 0f,
    val y: Float = 0f,
    val tileX: Int? = null,
    val tileY: Int? = null,
    val block: String? = null,
    val item: String? = null,
    val amount: Int? = null,
    val unitType: String? = null,
    val command: String? = null,
)

class PlayerActionRecorder(
    logFile: Path,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    maxLogBytes: Long = 8L * 1024 * 1024 * 1024,
    minimumFreeBytes: Long = 5L * 1024 * 1024 * 1024,
    onWriteFailure: (Exception) -> Unit = {},
    asyncWrites: Boolean = true,
    usableSpace: () -> Long = { Files.getFileStore(logFile.parent ?: logFile).usableSpace },
) {
    private val writer = PlayerActionLogWriter(
        logFile,
        maxLogBytes,
        minimumFreeBytes,
        onWriteFailure,
        asyncWrites,
        usableSpace,
    )

    fun recordAction(action: PlayerGameplayAction): Boolean {
        val recordedAt = nowEpochMillis()
        val line = "{" +
            "\"schema_version\":1," +
            "\"event\":\"action\"," +
            "\"action_type\":${jsonString(action.actionType)}," +
            "\"recorded_at\":${jsonString(Instant.ofEpochMilli(recordedAt).toString())}," +
            "\"recorded_at_epoch_ms\":$recordedAt," +
            "\"round_id\":${action.roundId?.let(::jsonString) ?: "null"}," +
            "\"map\":${jsonString(action.map)}," +
            "\"player_name\":${jsonString(action.playerName)}," +
            "\"side\":${jsonString(action.side.value)}," +
            "\"x\":${jsonNumber(action.x)}," +
            "\"y\":${jsonNumber(action.y)}," +
            "\"tile_x\":${action.tileX ?: "null"}," +
            "\"tile_y\":${action.tileY ?: "null"}," +
            "\"block\":${action.block?.let(::jsonString) ?: "null"}," +
            "\"item\":${action.item?.let(::jsonString) ?: "null"}," +
            "\"amount\":${action.amount ?: "null"}," +
            "\"unit_type\":${action.unitType?.let(::jsonString) ?: "null"}," +
            "\"command\":${action.command?.let(::jsonString) ?: "null"}" +
            "}"
        return writer.append(line)
    }

    fun recordMotion(snapshot: PlayerMotionSnapshot): Boolean {
        val recordedAt = nowEpochMillis()
        val line = "{" +
            "\"schema_version\":1," +
            "\"event\":\"motion\"," +
            "\"recorded_at\":${jsonString(Instant.ofEpochMilli(recordedAt).toString())}," +
            "\"recorded_at_epoch_ms\":$recordedAt," +
            "\"round_id\":${snapshot.roundId?.let(::jsonString) ?: "null"}," +
            "\"map\":${jsonString(snapshot.map)}," +
            "\"player_name\":${jsonString(snapshot.playerName)}," +
            "\"side\":${jsonString(snapshot.side.value)}," +
            "\"x\":${jsonNumber(snapshot.x)}," +
            "\"y\":${jsonNumber(snapshot.y)}," +
            "\"aim_x\":${jsonNumber(snapshot.aimX)}," +
            "\"aim_y\":${jsonNumber(snapshot.aimY)}," +
            "\"shooting\":${snapshot.shooting}," +
            "\"boosting\":${snapshot.boosting}," +
            "\"unit_type\":${snapshot.unitType?.let(::jsonString) ?: "null"}" +
            "}"
        return writer.append(line)
    }

    fun closeAndFlush() = writer.closeAndFlush()

    private fun jsonNumber(value: Float) = if (value.isFinite()) value.toString() else "null"

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
                else -> if (character.code < 0x20) append("\\u%04x".format(character.code)) else append(character)
            }
        }
        append('"')
    }
}

private class PlayerActionLogWriter(
    private val logFile: Path,
    private val maxLogBytes: Long,
    private val minimumFreeBytes: Long,
    private val onWriteFailure: (Exception) -> Unit,
    private val asyncWrites: Boolean,
    private val usableSpace: () -> Long,
) {
    private enum class WriteResult { SUCCESS, RETRY, STOP }

    private val queue = LinkedBlockingQueue<String>(MAX_PENDING_LINES)
    @Volatile private var enabled = true
    @Volatile private var closing = false
    @Volatile private var lastFailureReportMillis = 0L
    @Volatile private var shutdownDeadlineNanos = Long.MAX_VALUE
    private val worker = if (asyncWrites) Thread(::writeQueuedLines, "plague-player-action-writer").apply {
        isDaemon = true
        start()
    } else null

    init {
        if (asyncWrites) {
            Runtime.getRuntime().addShutdownHook(Thread({ closeAndFlush() }, "plague-player-action-shutdown"))
        }
    }

    fun append(line: String): Boolean {
        if (!enabled || closing) return false
        if (!asyncWrites) return writeBatch(listOf(line)) == WriteResult.SUCCESS
        if (queue.offer(line)) return true
        reportFailure(IOException("Player action queue is full; new samples are being dropped."))
        return false
    }

    private fun writeQueuedLines() {
        while (enabled) {
            val first = try {
                queue.poll(POLL_MILLIS, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                null
            }
            if (first == null) {
                if (closing && queue.isEmpty()) return
                continue
            }
            val batch = ArrayList<String>(MAX_BATCH_LINES)
            batch.add(first)
            queue.drainTo(batch, MAX_BATCH_LINES - 1)
            while (enabled) {
                when (writeBatch(batch)) {
                    WriteResult.SUCCESS -> break
                    WriteResult.STOP -> return
                    WriteResult.RETRY -> {
                        if (closing && System.nanoTime() >= shutdownDeadlineNanos) return
                        val sleepMillis = if (closing) {
                            ((shutdownDeadlineNanos - System.nanoTime()) / 1_000_000)
                                .coerceAtLeast(1L)
                                .coerceAtMost(RETRY_DELAY_MILLIS)
                        } else {
                            RETRY_DELAY_MILLIS
                        }
                        try {
                            Thread.sleep(sleepMillis)
                        } catch (_: InterruptedException) {
                            if (closing && System.nanoTime() >= shutdownDeadlineNanos) return
                        }
                    }
                }
            }
        }
    }

    private fun writeBatch(lines: List<String>): WriteResult = try {
        logFile.parent?.let(Files::createDirectories)
        val bytes = lines.joinToString(separator = "\n", postfix = "\n").toByteArray(StandardCharsets.UTF_8)
        val currentSize = if (Files.exists(logFile)) Files.size(logFile) else 0L
        val availableBytes = usableSpace()
        val batchBytes = bytes.size.toLong()
        val crossesFileLimit = currentSize > maxLogBytes || batchBytes > maxLogBytes - currentSize
        val crossesFreeFloor = availableBytes < minimumFreeBytes || batchBytes > availableBytes - minimumFreeBytes
        if (crossesFileLimit || crossesFreeFloor) {
            enabled = false
            reportFailure(IOException("Player action log safety limit reached; action logging stopped."))
            WriteResult.STOP
        } else {
            appendActionBatchSafely(logFile, bytes)
            WriteResult.SUCCESS
        }
    } catch (error: Exception) {
        reportFailure(error)
        WriteResult.RETRY
    }

    fun closeAndFlush() {
        closing = true
        shutdownDeadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(SHUTDOWN_FLUSH_MILLIS)
        worker?.interrupt()
        runCatching { worker?.join(SHUTDOWN_FLUSH_MILLIS) }
    }

    private fun reportFailure(error: Exception) {
        val now = System.currentTimeMillis()
        if (now - lastFailureReportMillis < FAILURE_REPORT_INTERVAL_MILLIS) return
        lastFailureReportMillis = now
        runCatching { onWriteFailure(error) }
    }

    companion object {
        private const val MAX_PENDING_LINES = 32_768
        private const val MAX_BATCH_LINES = 256
        private const val POLL_MILLIS = 100L
        private const val RETRY_DELAY_MILLIS = 1_000L
        private const val SHUTDOWN_FLUSH_MILLIS = 5_000L
        private const val FAILURE_REPORT_INTERVAL_MILLIS = 60_000L
    }
}

private fun appendActionBatchSafely(logFile: Path, bytes: ByteArray) {
    FileChannel.open(
        logFile,
        StandardOpenOption.CREATE,
        StandardOpenOption.READ,
        StandardOpenOption.WRITE,
    ).use { channel ->
        repairActionLogTail(channel)
        val originalSize = channel.size()
        channel.position(originalSize)
        try {
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(false)
        } catch (error: Exception) {
            runCatching { channel.truncate(originalSize) }
            throw error
        }
    }
}

private fun repairActionLogTail(channel: FileChannel) {
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
