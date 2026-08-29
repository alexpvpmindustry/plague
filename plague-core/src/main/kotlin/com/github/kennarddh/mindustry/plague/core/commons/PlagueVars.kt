package com.github.kennarddh.mindustry.plague.core.commons

import kotlinx.coroutines.sync.Mutex
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import mindustry.content.Items
import mindustry.gen.Player
import mindustry.type.ItemStack
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

object PlagueVars {
    val port: Int
        get() = System.getenv("PORT")?.toInt() ?: 6567

    val monoReward = listOf(ItemStack(Items.copper, 300), ItemStack(Items.lead, 300))
    val newCoreCost = listOf(ItemStack(Items.thorium, 1000))

    var state: PlagueState = PlagueState.Prepare

    val stateLock = Mutex()

    lateinit var mapStartTime: Instant
    val prepareTimer = PrepareTimer()
    var totalMapSkipDuration: Duration = 0.seconds

    val mapTime: Duration
        get() {
            if (::mapStartTime.isInitialized)
                return Clock.System.now() - mapStartTime + totalMapSkipDuration

            return totalMapSkipDuration
        }

    val playersHUDInfo: MutableMap<Player, PlayerHUDInfo> = ConcurrentHashMap()

    /**
     * If this is true mono tree units made pre second phase will have their weapons restored when second phase.
     */
    val restorePreSecondPhaseMonoTreeUnitsWeapons: Boolean = false

    val survivorsMinBuildRangeFromPlagueCoreInTiles: Int = 100
    val newSurvivorCoreMinDistanceFromPlagueCoreInTiles: Int = 70
    val survivorCoreMaxJoinDistanceInTiles: Int = 120

    val playerHUDPresets: List<PlayerHUDPreset> = listOf(
        PlayerHUDPreset(Align.TopLeft, 200, 0, 0, 100),
        PlayerHUDPreset(Align.TopLeft, 300),
        PlayerHUDPreset(Align.Top, 100)
    )

    val playerHUDPresetDefaultIndex: Int = 0
}