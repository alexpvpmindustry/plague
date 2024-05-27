package com.github.kennarddh.mindustry.plague.core.handlers

import arc.util.Timer
import com.github.kennarddh.mindustry.genesis.core.commands.annotations.Command
import com.github.kennarddh.mindustry.genesis.core.commands.senders.CommandSender
import com.github.kennarddh.mindustry.genesis.core.commons.CoroutineScopes
import com.github.kennarddh.mindustry.genesis.core.commons.priority.Priority
import com.github.kennarddh.mindustry.genesis.core.commons.runOnMindustryThread
import com.github.kennarddh.mindustry.genesis.core.events.annotations.EventHandler
import com.github.kennarddh.mindustry.genesis.core.filters.FilterType
import com.github.kennarddh.mindustry.genesis.core.filters.annotations.Filter
import com.github.kennarddh.mindustry.genesis.core.handlers.Handler
import com.github.kennarddh.mindustry.plague.core.commons.PlagueBanned
import com.github.kennarddh.mindustry.plague.core.commons.PlagueState
import com.github.kennarddh.mindustry.plague.core.commons.PlagueVars
import com.github.kennarddh.mindustry.plague.core.commons.extensions.Logger
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import mindustry.Vars
import mindustry.content.Blocks
import mindustry.content.UnitTypes
import mindustry.core.NetServer
import mindustry.game.EventType
import mindustry.game.Team
import mindustry.gen.Groups
import mindustry.gen.Player
import mindustry.net.Administration
import mindustry.world.Block
import mindustry.world.Tile
import mindustry.world.blocks.storage.CoreBlock.CoreBuild
import kotlin.time.Duration.Companion.minutes


class PlagueHandler : Handler {
    private val timers = mutableSetOf<Timer.Task>()

    @Filter(FilterType.Action, Priority.High)
    fun powerSourceActionFilter(action: Administration.PlayerAction): Boolean {
        if (!(action.type == Administration.ActionType.breakBlock || action.type == Administration.ActionType.pickupBlock)) return true

        if (action.block != Blocks.powerSource) return true

        return false
    }

    @Filter(FilterType.Action, Priority.High)
    suspend fun buildBlockActionFilter(action: Administration.PlayerAction): Boolean {
        if (action.unit == null) return true

        if (action.unit.team() == Team.blue) return true

        PlagueVars.stateLock.withLock {
            if (action.unit.team() == Team.malis) {
                if (action.block != null) {
                    if (PlagueBanned.getCurrentPlagueBannedBlocks(true).contains(action.block)) {
                        return false
                    }
                }
            } else {
                if (action.block != null) {
                    if (PlagueBanned.getCurrentSurvivorsBannedBlocks().contains(action.block)) {
                        return false
                    }
                }
            }
        }

        return true
    }

    @Filter(FilterType.Action, Priority.High)
    fun respawnActionFilter(action: Administration.PlayerAction): Boolean {
        if (action.type != Administration.ActionType.respawn) return true

        if (action.player.team() == Team.blue) return false

        return true
    }

    fun getNewEmptyTeam(): Team? {
        return Team.all.find {
            // Non default team and not active.
            it.id > 5 && !it.active()
        }
    }

    fun validPlace(block: Block, tile: Tile): Boolean {
        val offsetX: Int = -(block.size - 1) / 2
        val offsetY: Int = -(block.size - 1) / 2

        for (dx in 0..<block.size) {
            for (dy in 0..<block.size) {
                val wx = dx + offsetX + tile.x
                val wy = dy + offsetY + tile.y

                val checkTile = Vars.world.tile(wx, wy)

                if (
                // Empty tile
                    checkTile == null
                    // Deep water
                    || checkTile.floor().isDeep
                    // Exactly same block
                    || (block == checkTile.block() && checkTile.build != null && block.rotate)
                    || !checkTile.floor().placeableOn
                )
                    return false
            }
        }

        return true
    }

    @EventHandler
    suspend fun onBuildSelect(event: EventType.BuildSelectEvent) {
        PlagueVars.stateLock.withLock {
            if (PlagueVars.state != PlagueState.Prepare) return
        }

        if (event.builder.player == null) return
        if (event.builder.team() != Team.blue) return
        if (event.breaking) return

        runOnMindustryThread {
            event.tile.removeNet()

            if (!validPlace(Blocks.coreShard, event.tile))
                return@runOnMindustryThread

            val newTeam = getNewEmptyTeam()
                ?: return@runOnMindustryThread event.builder.player.sendMessage("[scarlet]No available team.")

            for (core in Team.malis.cores()) {
                Logger.info("Dis: ${core.dst(event.tile)}")

                if (core.dst(event.tile) < 50 * Vars.tilesize)
                    return@runOnMindustryThread event.builder.player.sendMessage("[scarlet]Core must be at least 50 tiles away from nearest plague's core.")
            }

            event.builder.player.team(newTeam)

            event.tile.setNet(Blocks.coreShard, newTeam, 0)

            Vars.state.teams.registerCore(event.tile.build as CoreBuild)
        }
    }

    @EventHandler
    suspend fun onPlay(event: EventType.PlayEvent) {
        PlagueVars.stateLock.withLock {
            PlagueVars.state = PlagueState.Prepare
        }

        Timer.schedule({
            CoroutineScopes.Main.launch {
                onFirstPhase()

                Timer.schedule({
                    CoroutineScopes.Main.launch {
                        onSecondPhase()
                    }

                    Timer.schedule({
                        CoroutineScopes.Main.launch {
                            onEnded()
                        }
                    }, 15.minutes.inWholeSeconds.toFloat()).run {
                        timers.add(this)
                    }
                }, 45.minutes.inWholeSeconds.toFloat()).run {
                    timers.add(this)
                }
            }
        }, 2.minutes.inWholeSeconds.toFloat()).run {
            timers.add(this)
        }
    }

    @EventHandler
    fun onGameOver(event: EventType.GameOverEvent) {
        timers.forEach {
            it.cancel()
        }
    }

    fun spawnPlayerUnit(
        player: Player,
        team: Team,
        x: Float = Vars.state.map.width / 2f * Vars.tilesize,
        y: Float = Vars.state.map.height / 2f * Vars.tilesize
    ) {
        val unit = UnitTypes.gamma.create(team)

        player.set(x, y)

        unit.set(x, y)
        unit.rotation(90.0f)
        unit.impulse(0.0f, 3.0f)
        unit.spawnedByCore(true)
        unit.controller(player)
        unit.add()
    }

    @EventHandler
    fun onPlayerJoin(event: EventType.PlayerJoin) {
        runOnMindustryThread {
            if (event.player.team() == Team.blue) {
                spawnPlayerUnit(event.player, Team.blue)
            }
        }
    }

    @Command(["state"])
    suspend fun getState(sender: CommandSender) {
        PlagueVars.stateLock.withLock {
            sender.sendSuccess(PlagueVars.state.name)
        }
    }

    override suspend fun onInit() {
        runOnMindustryThread {
            Vars.netServer.assigner = NetServer.TeamAssigner { _, _ ->
                val team = runBlocking {
                    PlagueVars.stateLock.withLock {
                        if (PlagueVars.state == PlagueState.Prepare) return@runBlocking Team.blue
                    }

                    Team.malis
                }

                team
            }
        }
    }

    suspend fun onFirstPhase() {
        PlagueVars.stateLock.withLock {
            PlagueVars.state = PlagueState.PlayingFirstPhase
        }

        runOnMindustryThread {
            // Move every no team player to plague team
            Groups.player.filter { it.team() === Team.blue }.forEach {
                it.team(Team.malis)
            }
        }
    }

    // 45 minutes after onPlay move to second phase.
    suspend fun onSecondPhase() {
        PlagueVars.stateLock.withLock {
            PlagueVars.state = PlagueState.PlayingSecondPhase
        }
    }

    // 15 minutes after onSecondPhase move to ended.
    suspend fun onEnded() {
        PlagueVars.stateLock.withLock {
            PlagueVars.state = PlagueState.Ended
        }
    }
}