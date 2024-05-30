package com.github.kennarddh.mindustry.plague.core.handlers

import arc.math.Mathf
import arc.util.Reflect
import arc.util.Time
import arc.util.Timer
import com.github.kennarddh.mindustry.genesis.core.Genesis
import com.github.kennarddh.mindustry.genesis.core.commands.CommandSide
import com.github.kennarddh.mindustry.genesis.core.commands.annotations.Command
import com.github.kennarddh.mindustry.genesis.core.commands.annotations.Description
import com.github.kennarddh.mindustry.genesis.core.commands.annotations.parameters.Vararg
import com.github.kennarddh.mindustry.genesis.core.commands.senders.CommandSender
import com.github.kennarddh.mindustry.genesis.core.commands.senders.PlayerCommandSender
import com.github.kennarddh.mindustry.genesis.core.commons.CoroutineScopes
import com.github.kennarddh.mindustry.genesis.core.commons.priority.Priority
import com.github.kennarddh.mindustry.genesis.core.commons.runOnMindustryThread
import com.github.kennarddh.mindustry.genesis.core.commons.runOnMindustryThreadSuspended
import com.github.kennarddh.mindustry.genesis.core.events.annotations.EventHandler
import com.github.kennarddh.mindustry.genesis.core.events.annotations.EventHandlerTrigger
import com.github.kennarddh.mindustry.genesis.core.filters.FilterType
import com.github.kennarddh.mindustry.genesis.core.filters.annotations.Filter
import com.github.kennarddh.mindustry.genesis.core.handlers.Handler
import com.github.kennarddh.mindustry.genesis.standard.extensions.setRules
import com.github.kennarddh.mindustry.plague.core.commons.*
import com.github.kennarddh.mindustry.plague.core.commons.extensions.Logger
import com.github.kennarddh.mindustry.plague.core.commons.extensions.toDisplayString
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import mindustry.Vars
import mindustry.content.Blocks
import mindustry.content.Items
import mindustry.content.UnitTypes
import mindustry.core.GameState
import mindustry.core.NetServer
import mindustry.game.EventType
import mindustry.game.EventType.Trigger
import mindustry.game.Team
import mindustry.gen.Call
import mindustry.gen.Groups
import mindustry.gen.Iconc
import mindustry.gen.Player
import mindustry.maps.MapException
import mindustry.net.Administration
import mindustry.net.Administration.Config
import mindustry.net.Packets.KickReason
import mindustry.server.ServerControl
import mindustry.type.ItemStack
import mindustry.type.UnitType
import mindustry.world.Block
import mindustry.world.Tile
import mindustry.world.blocks.storage.CoreBlock
import mindustry.world.blocks.storage.CoreBlock.CoreBuild
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.floor
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds


class PlagueHandler : Handler {
    private val monoReward = listOf(ItemStack(Items.copper, 300), ItemStack(Items.lead, 300))

    private val survivorTeamsData: MutableMap<Team, SurvivorTeamData> = ConcurrentHashMap()

    private val teamsPlayersUUIDBlacklist: MutableMap<Team, MutableSet<String>> = ConcurrentHashMap()

    private lateinit var mapStartTime: Instant
    private var totalMapSkipDuration: Duration = 0.seconds

    private val mapTime: Duration
        get() {
            if (::mapStartTime.isInitialized)
                return Clock.System.now() - mapStartTime + totalMapSkipDuration

            return totalMapSkipDuration
        }

    @Filter(FilterType.Action, Priority.High)
    fun payloadActionFilter(action: Administration.PlayerAction): Boolean {
        if (action.type == Administration.ActionType.dropPayload) return false
        if (action.type == Administration.ActionType.pickupBlock) return false

        return true
    }

    @Filter(FilterType.Action, Priority.High)
    fun powerSourceActionFilter(action: Administration.PlayerAction): Boolean {
        if (action.type != Administration.ActionType.breakBlock) return true

        if (action.block != Blocks.powerSource) return true

        return false
    }

    @Filter(FilterType.Action, Priority.High)
    suspend fun buildBlockActionFilter(action: Administration.PlayerAction): Boolean {
        if (action.player == null) return true

        if (action.player.team() == Team.blue) return true

        if (action.block == null) return true

        PlagueVars.stateLock.withLock {
            if (action.player.team() == Team.malis) {
                if (PlagueBanned.getCurrentPlagueBannedBlocks(true).contains(action.block)) {
                    return false
                }
            } else {
                if (PlagueBanned.getCurrentSurvivorsBannedBlocks().contains(action.block)) {
                    return false
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

    /**
     * Player has their own rules
     * This desync the player's rules with server's rules but this is fine because only the banned blocks and units are changed
     * This is not safe by itself because with customized client or mod rules can be easily bypassed and because the server doesn't ban the block in the rules player can just build the block.
     * To prevent this action filter is used to check if player is allowed to build or create unit
     */
    suspend fun updatePlayerSpecificRules(player: Player) {
        if (player.team() == Team.blue) return

        val playerRules = Vars.state.rules.copy()

        if (player.team() == Team.malis) {
            PlagueBanned.getCurrentPlagueBannedBlocks().forEach {
                playerRules.bannedBlocks.add(it)
            }

            PlagueBanned.getCurrentPlagueBannedUnits().forEach {
                playerRules.bannedUnits.add(it)
            }
        } else {
            PlagueBanned.getCurrentSurvivorsBannedBlocks().forEach {
                playerRules.bannedBlocks.add(it)
            }

            PlagueBanned.getCurrentSurvivorsBannedUnits().forEach {
                playerRules.bannedUnits.add(it)
            }
        }

        player.setRules(playerRules)
    }

    suspend fun updateAllPlayerSpecificRules() {
        Groups.player.forEach {
            updatePlayerSpecificRules(it)
        }
    }

    suspend fun changePlayerTeam(player: Player, team: Team) {
        player.team(team)

        updatePlayerSpecificRules(player)
    }

    suspend fun onSurvivorTeamDestroyed() {
        PlagueVars.stateLock.withLock {
            if (PlagueVars.state == PlagueState.Ended) {
                Call.infoMessage("[green]All survivors have been destroyed.")

                restart(Team.derelict)

                return
            }

            Call.infoMessage("[green]Plague team won the game.")

            restart(Team.malis)
        }
    }

    fun leaveSurvivorTeam(player: Player) {
        survivorTeamsData[player.team()]?.playersUUID?.remove(player.uuid())

        val teamData = Vars.state.teams[player.team()]

        // Minus 1 because the player is still in the team
        if (teamData.players.size - 1 == 0) {
            if (player.team() != Team.blue) {
                teamsPlayersUUIDBlacklist.remove(player.team())

                Call.sendMessage("[accent]All ${player.team().name} team players left. Team will be removed.")

                teamData.units.forEach { it.kill() }
                teamData.buildings.forEach { it.kill() }

                survivorTeamsData.remove(player.team())

                CoroutineScopes.Main.launch {
                    onSurvivorTeamDestroyed()
                }
            }

            return
        }

        if (survivorTeamsData[player.team()]?.ownerUUID == player.uuid()) {
            val newCurrentOwner = teamData.players.toList().filter { it.uuid() != player.uuid() }[0]

            survivorTeamsData[player.team()]?.ownerUUID = newCurrentOwner.uuid()

            Groups.player.filter { survivorTeamsData[player.team()]?.playersUUID?.contains(it.uuid()) ?: false }
                .forEach {
                    it.sendMessage("[green]'${newCurrentOwner.plainName()}' is now the owner of this team because the previous owner left.")
                }
        }
    }

    @Command(["plague"])
    fun plague(sender: PlayerCommandSender) {
        if (sender.player.team() == Team.malis)
            return sender.sendError("You are already in plague team.")

        runOnMindustryThread {
            runBlocking {
                leaveSurvivorTeam(sender.player)

                changePlayerTeam(sender.player, Team.malis)

                sender.player.unit().kill()

                sender.sendSuccess("You are now in plague team.")
            }
        }
    }

    @Command(["teamleave"])
    suspend fun teamLeave(sender: PlayerCommandSender) {
        if (sender.player.team() == Team.blue)
            return sender.sendError("You are not in any team.")

        PlagueVars.stateLock.withLock {
            if (PlagueVars.state == PlagueState.Prepare) {
                runOnMindustryThread {
                    runBlocking {
                        if (sender.player.team() != Team.malis)
                            leaveSurvivorTeam(sender.player)

                        changePlayerTeam(sender.player, Team.blue)
                    }
                }

                return
            }
        }

        runOnMindustryThread {
            runBlocking {
                if (isValidSurvivorTeam(sender.player.team()))
                    leaveSurvivorTeam(sender.player)

                changePlayerTeam(sender.player, Team.blue)

                sender.player.unit().kill()

                sender.sendSuccess("You are now in plague team.")
            }
        }
    }

    @Command(["teamkick"])
    suspend fun teamKick(sender: PlayerCommandSender, @Vararg target: Player) {
        if (sender.player.team() == Team.malis)
            return sender.sendError("You cannot kick in plague team.")

        if (sender.player.team() == Team.blue)
            return sender.sendError("You are not in any team.")

        if (sender.player.team() != target.team())
            return sender.sendError("Cannot kick other team's member.")

        if (sender.player == target)
            return sender.sendError("Cannot kick yourself.")

        val survivorTeamData = survivorTeamsData[sender.player.team()]
            ?: return sender.sendError("Error occurred. SurvivorTeamData == null.")

        if (survivorTeamData.ownerUUID != sender.player.uuid())
            return sender.sendError("You are not owner in the team.")

        Groups.player.filter { survivorTeamData.playersUUID.contains(it.uuid()) }
            .forEach {
                it.sendMessage("[scarlet]'${target.plainName()}' was kicked from the team.")
            }

        teamsPlayersUUIDBlacklist[sender.player.team()]?.add(target.uuid())

        PlagueVars.stateLock.withLock {
            if (PlagueVars.state == PlagueState.Prepare) {
                runOnMindustryThread {
                    runBlocking {
                        leaveSurvivorTeam(target)

                        changePlayerTeam(target, Team.blue)
                    }
                }

                return
            }
        }

        runOnMindustryThread {
            runBlocking {
                leaveSurvivorTeam(target)

                changePlayerTeam(target, Team.malis)

                target.unit().kill()
            }
        }
    }

    @Command(["teamtransferownership"])
    fun teamTransferOwnership(sender: PlayerCommandSender, @Vararg target: Player) {
        if (sender.player.team() == Team.malis)
            return sender.sendError("You cannot transfer ownership in plague team.")

        if (sender.player.team() == Team.blue)
            return sender.sendError("You are not in any team.")

        if (sender.player.team() != target.team())
            return sender.sendError("Cannot transfer ownership to other team's member.")

        if (sender.player == target)
            return sender.sendError("Cannot transfer ownership to yourself.")

        val survivorTeamData = survivorTeamsData[sender.player.team()]
            ?: return sender.sendError("Error occurred. SurvivorTeamData == null.")

        if (survivorTeamData.ownerUUID != sender.player.uuid())
            return sender.sendError("You are not owner in the team.")

        survivorTeamData.ownerUUID = target.uuid()

        Groups.player.filter { survivorTeamData.playersUUID.contains(it.uuid()) }
            .forEach {
                it.sendMessage("[green]'${target.plainName()}' is now the owner of this team because the previous owner transferred the ownership.")
            }
    }

    @Command(["teamlock"])
    fun teamLock(sender: PlayerCommandSender) {
        if (sender.player.team() == Team.malis)
            return sender.sendError("You cannot lock plague team.")

        if (sender.player.team() == Team.blue)
            return sender.sendError("You are not in any team.")

        val survivorTeamData = survivorTeamsData[sender.player.team()]
            ?: return sender.sendError("Error occurred. SurvivorTeamData == null.")

        if (survivorTeamData.ownerUUID != sender.player.uuid())
            return sender.sendError("You are not owner in the team.")

        survivorTeamData.locked = !survivorTeamData.locked

        Groups.player.filter { survivorTeamData.playersUUID.contains(it.uuid()) }
            .forEach {
                if (survivorTeamData.locked)
                    it.sendMessage("[scarlet]This team is now locked by the owner.")
                else
                    it.sendMessage("[green]This team is now unlocked by the owner.")
            }
    }

    fun isValidSurvivorTeam(team: Team) = team.id > 6

    fun getNewEmptySurvivorTeam(): Team? {
        return Team.all.find {
            isValidSurvivorTeam(it) && !it.active()
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
                // Void tile
                    checkTile == null ||
                    // Tile with block
                    checkTile.build != null ||
                    // Deep water
                    checkTile.floor().isDeep ||
                    // Exactly same block
                    (block == checkTile.block() && checkTile.build != null && block.rotate) ||
                    !checkTile.floor().placeableOn
                )
                    return false
            }
        }

        return true
    }

    fun getClosestEnemyCore(team: Team, x: Float, y: Float, maxDistance: Float = Float.MAX_VALUE): CoreBuild? {
        var closest: CoreBuild? = null
        var closestDistance = Float.MAX_VALUE

        for (activeTeam in Vars.state.teams.active) {
            if (activeTeam == team) continue

            for (core in activeTeam.cores) {
                val distance = Mathf.dst(x, y, core.x, core.y)

                if (distance > maxDistance) continue

                if (closestDistance > distance) {
                    closest = core

                    closestDistance = distance
                }
            }
        }

        return closest
    }

    @EventHandler
    suspend fun onCoreDestroyed(event: EventType.BlockDestroyEvent) {
        if (event.tile.build !is CoreBuild) return

        val coreBuild = event.tile.build as CoreBuild

        // Minus 1 because core have not been destroyed here
        if (coreBuild.team.cores().size - 1 != 0) return

        val teamData = Vars.state.teams[coreBuild.team]

        runOnMindustryThread {
            Call.sendMessage("[scarlet]'${coreBuild.team.name}' survivor team lost.")

            runBlocking {
                teamData.players.forEach {
                    changePlayerTeam(it, Team.malis)

                    it.unit().kill()

                    Call.sendMessage("[scarlet]'${it.plainName()}' has been infected.")
                }
            }

            teamData.units.forEach { it.kill() }
            teamData.buildings.forEach { it.kill() }
        }

        survivorTeamsData.remove(coreBuild.team)

        onSurvivorTeamDestroyed()
    }

    @Command(["sync"])
    @Description("Re-synchronize world state.")
    fun playerSyncCommand(sender: PlayerCommandSender) {
        runOnMindustryThread {
            if (sender.player.isLocal)
                return@runOnMindustryThread sender.sendError("Re-synchronizing as the host is pointless.")

            if (Time.timeSinceMillis(sender.player.info.lastSyncTime) < 1000 * 5)
                return@runOnMindustryThread sender.sendError("You may only /sync every 5 seconds.")

            sender.player.info.lastSyncTime = Time.millis()

            Call.worldDataBegin(sender.player.con)

            Vars.netServer.sendWorldData(sender.player)

            runBlocking {
                updatePlayerSpecificRules(sender.player)
            }
        }
    }

    @EventHandler
    suspend fun createSurvivorCoreEventHandler(event: EventType.BuildSelectEvent) {
        PlagueVars.stateLock.withLock {
            if (PlagueVars.state != PlagueState.Prepare) return
        }

        if (event.builder.player == null) return
        if (event.builder.team() != Team.blue) return
        if (event.breaking) return

        runOnMindustryThread {
            event.tile.removeNet()

            if (!validPlace(Blocks.coreShard, event.tile))
                return@runOnMindustryThread event.builder.player.sendMessage("[scarlet]Invalid core position.")

            for (core in Team.malis.cores()) {
                if (core.dst(event.tile) < 100 * Vars.tilesize)
                    return@runOnMindustryThread event.builder.player.sendMessage("[scarlet]Core must be at least 100 tiles away from nearest plague's core.")
            }

            val closestEnemyCoreInRange = getClosestEnemyCore(
                Team.derelict,
                event.tile.x.toFloat() * Vars.tilesize,
                event.tile.y.toFloat() * Vars.tilesize,
                50f * Vars.tilesize
            )

            if (closestEnemyCoreInRange != null) {
                // Join closest survivor core team
                if (teamsPlayersUUIDBlacklist[closestEnemyCoreInRange.team]?.contains(event.builder.player.uuid()) == true)
                    return@runOnMindustryThread event.builder.player.sendMessage("[scarlet]You are blacklisted from joining the team '${closestEnemyCoreInRange.team.name}' because you were kicked by the team owner.")

                val survivorTeamData = survivorTeamsData[closestEnemyCoreInRange.team()]
                    ?: return@runOnMindustryThread event.builder.player.sendMessage("[scarlet]Error occurred. SurvivorTeamData == null when joining a team.")

                if (survivorTeamData.locked)
                    return@runOnMindustryThread event.builder.player.sendMessage("[scarlet]The closest team '${closestEnemyCoreInRange.team.name}' is locked.")

                survivorTeamData.playersUUID.add(event.builder.player.uuid())

                runBlocking {
                    changePlayerTeam(event.builder.player, closestEnemyCoreInRange.team)
                }

                event.tile.setNet(Blocks.coreShard, closestEnemyCoreInRange.team, 0)

                Vars.state.teams.registerCore(event.tile.build as CoreBuild)
            } else {
                // Create new team
                val newTeam = getNewEmptySurvivorTeam()
                    ?: return@runOnMindustryThread event.builder.player.sendMessage("[scarlet]No available team.")

                survivorTeamsData[newTeam] = SurvivorTeamData(
                    event.builder.player.uuid(), mutableSetOf(event.builder.player.uuid())
                )

                teamsPlayersUUIDBlacklist[newTeam] = Collections.synchronizedSet(mutableSetOf())

                runBlocking {
                    changePlayerTeam(event.builder.player, newTeam)
                }

                event.tile.setNet(Blocks.coreShard, newTeam, 0)

                Vars.state.teams.registerCore(event.tile.build as CoreBuild)

                Vars.state.rules.loadout.forEach {
                    newTeam.core().items().add(it.item, it.amount.coerceAtMost(newTeam.core().storageCapacity))
                }
            }
        }
    }

    @EventHandler
    @EventHandlerTrigger(Trigger.update)
    suspend fun onUpdate() {
        runOnMindustryThreadSuspended {
            if (Vars.state.gameOver) return@runOnMindustryThreadSuspended

            // Make sure malis core cannot be destroyed
            Team.malis.cores().forEach {
                it.health = Float.MAX_VALUE
            }

            // Make sure blue team units cannot be killed
            Groups.unit.forEach {
                if (it.team != Team.blue) return@forEach

                it.health = Float.MAX_VALUE
            }
        }

        // Like this to prevent locking state dead lock
        PlagueVars.stateLock.withLock {
            if (PlagueVars.state == PlagueState.Prepare && mapTime >= 2.minutes) {
                CoroutineScopes.Main.launch { onFirstPhase() }
            } else if (PlagueVars.state == PlagueState.PlayingFirstPhase && mapTime >= 47.minutes) {
                CoroutineScopes.Main.launch { onSecondPhase() }
            } else if (PlagueVars.state == PlagueState.PlayingSecondPhase && mapTime >= 62.minutes) {
                CoroutineScopes.Main.launch { onEnded() }
            } else {
                // Empty else because this 'if' is seen as an expression
            }
        }
    }

    @EventHandler
    suspend fun onPlay(event: EventType.PlayEvent) {
        PlagueVars.stateLock.withLock {
            PlagueVars.state = PlagueState.Prepare
        }

        totalMapSkipDuration = 0.seconds
        mapStartTime = Clock.System.now()

        runOnMindustryThread {
            // Reset rules
            Vars.state.rules = PlagueRules.initRules(Vars.state.rules)

            Call.setRules(Vars.state.rules)

            Team.malis.core().items().clear()
        }
    }

    @EventHandler
    fun onPlayerLeave(event: EventType.PlayerLeave) {
        val teamOwned = survivorTeamsData.entries.find { it.value.ownerUUID == event.player.uuid() }

        if (teamOwned == null) return

        runOnMindustryThread {
            val teamData = Vars.state.teams[teamOwned.key]

            if (teamData.players.size == 0)
                return@runOnMindustryThread

            Groups.player.filter { survivorTeamsData[teamOwned.key]?.playersUUID?.contains(it.uuid()) ?: false }
                .forEach {
                    it.sendMessage("[accent]Team owner left.")
                }
        }
    }

    suspend fun restart(winner: Team) {
        Logger.info("Restart")
        survivorTeamsData.clear()
        teamsPlayersUUIDBlacklist.clear()

        val roundExtraTimeDuration = Config.roundExtraTime.num().seconds

        val map = runOnMindustryThreadSuspended {
            val map = Vars.maps.getNextMap(ServerControl.instance.lastMode, Vars.state.map)

            if (map == null) {
                Vars.netServer.kickAll(KickReason.gameover)
                Vars.state.set(GameState.State.menu)
                Vars.net.closeServer()

                return@runOnMindustryThreadSuspended null
            }

            Call.infoMessage(
                """
                [scarlet]Game over!
                [white]Next selected map: [white]${map.name()}[white]${if (map.hasTag("author")) " by [white]${map.author()}" else ""}.
                [white]New game begins in ${roundExtraTimeDuration.toDisplayString()}.
                """.trimIndent()
            )

            Vars.state.gameOver = true
            Call.updateGameOver(winner)

            Logger.info("Selected next map to be '${map.plainName()}'.")

            ServerControl.instance.inGameOverWait = true

            // TODO: When v147 released replace this with ServerControl.instance.cancelPlayTask()
            Reflect.get<Timer.Task>(ServerControl.instance, "lastTask")?.cancel()

            return@runOnMindustryThreadSuspended map
        } ?: return

        delay(roundExtraTimeDuration)

        runOnMindustryThread {
            try {
                val reloader = PlagueWorldReloader()

                reloader.begin()

                Vars.logic.reset()

                Vars.world.loadMap(map, map.applyRules(ServerControl.instance.lastMode))

                Vars.state.rules = Vars.state.map.applyRules(ServerControl.instance.lastMode)
                Vars.state.rules = PlagueRules.initRules(Vars.state.rules)

                Vars.logic.play()

                reloader.end()

                ServerControl.instance.inGameOverWait = false
            } catch (error: MapException) {
                Logger.error("${error.map.plainName()}: ${error.message}")

                Vars.net.closeServer()
            }
        }
    }

    suspend fun setupPlayer(player: Player, playerSpawnDelay: Duration = 0.seconds) {
        if (player.team() != Team.blue) return

        val randomPlagueCore = Team.malis.cores().random()

        CoroutineScopes.Main.launch {
            delay(playerSpawnDelay)

            runOnMindustryThread {
                CoreBlock.playerSpawn(randomPlagueCore.tile, player)
            }
        }

        updatePlayerSpecificRules(player)
    }

    @EventHandler
    fun onPlayerJoin(event: EventType.PlayerJoin) {
        runOnMindustryThread {
            runBlocking {
                setupPlayer(event.player)
            }
        }
    }

    @Command(["state"])
    suspend fun getState(sender: CommandSender) {
        PlagueVars.stateLock.withLock {
            sender.sendSuccess(
                """
                State: ${PlagueVars.state.name}
                Map Time: ${mapTime.toDisplayString()}
                Map Skip Duration: ${totalMapSkipDuration.toDisplayString()}
                """.trimIndent()
            )
        }
    }

    @Command(["skiptime"])
    fun skipTime(sender: CommandSender, duration: Duration) {
        totalMapSkipDuration += duration

        sender.sendSuccess("Skipped '${duration.toDisplayString()}'. Current map time is '${mapTime.toDisplayString()}'.")
    }

    @Command(["spawnunit"])
    fun spawnUnit(sender: PlayerCommandSender, unitType: UnitType, team: Team = sender.player.team()) {
        unitType.spawn(sender.player, team)

        sender.sendSuccess(
            "Spawned '${unitType.name}' at (${floor(sender.player.x / Vars.tilesize).toInt()}, ${
                floor(sender.player.y / Vars.tilesize).toInt()
            })"
        )
    }

    @Command(["gameover"])
    suspend fun gameover(sender: CommandSender, team: Team = Team.derelict) {
        restart(team)
    }

    override suspend fun onInit() {
        Genesis.commandRegistry.removeCommand("sync")
        Genesis.commandRegistry.removeCommand("gameover", CommandSide.Server)

        runOnMindustryThread {
            Vars.netServer.assigner = NetServer.TeamAssigner { player, _ ->
                val lastSurvivorTeamData =
                    survivorTeamsData.entries.find { it.value.playersUUID.contains(player.uuid()) }

                if (lastSurvivorTeamData != null) {
                    return@TeamAssigner lastSurvivorTeamData.key
                }

                runBlocking {
                    PlagueVars.stateLock.withLock {
                        if (PlagueVars.state == PlagueState.Prepare) return@runBlocking Team.blue
                    }

                    Team.malis
                }
            }
        }
    }

    suspend fun onFirstPhase() {
        PlagueVars.stateLock.withLock {
            PlagueVars.state = PlagueState.PlayingFirstPhase
        }

        runOnMindustryThread {
            Vars.state.rules.enemyCoreBuildRadius = Vars.state.map.rules().enemyCoreBuildRadius

            Call.setRules(Vars.state.rules)

            runBlocking {
                updateAllPlayerSpecificRules()
            }

            // Move every no team player to plague team
            Groups.player.filter { it.team() === Team.blue }.forEach {
                runBlocking {
                    changePlayerTeam(it, Team.malis)

                    it.unit().kill()
                }
            }
        }
    }

    suspend fun onSecondPhase() {
        PlagueVars.stateLock.withLock {
            PlagueVars.state = PlagueState.PlayingSecondPhase
        }

        runOnMindustryThread {
            runBlocking {
                Logger.info("Pre ${Vars.state.rules.teams[Team.all[7]].blockDamageMultiplier}")
                Team.all.filter { isValidSurvivorTeam(it) }.forEach {
                    Vars.state.rules.teams[it].blockDamageMultiplier *= 1.3f
                }
                Logger.info("Post ${Vars.state.rules.teams[Team.all[7]].blockDamageMultiplier}")

                updateAllPlayerSpecificRules()
            }
        }
    }

    suspend fun onEnded() {
        PlagueVars.stateLock.withLock {
            PlagueVars.state = PlagueState.Ended
        }

        runOnMindustryThread {
            runBlocking {
                updateAllPlayerSpecificRules()
            }
        }

        val survivorTeams = Vars.state.teams.active.toList().filter { isValidSurvivorTeam(it.team) }

        if (survivorTeams.isEmpty()) return

        Call.infoMessage(
            """
                [green]Survivor teams won.
                [green]${survivorTeams.joinToString(", ") { "'${it.team.name}'" }} won.
                [scarlet]Plague lost.
                [white]Game will still continue.
                """.trimIndent()
        )
    }

    @EventHandler
    fun onMonoUnitCreate(event: EventType.UnitCreateEvent) {
        if (event.unit.type != UnitTypes.mono) return

        if (event.unit.team.core() == null) return

        runOnMindustryThread {
            Call.label(
                "${Iconc.unitMono} created",
                5f,
                event.spawner.x,
                event.spawner.y
            )

            // .kill() instantly kill the unit makes it weird because the unit just disappear
            event.unit.health = 0f
            event.unit.dead = true

            event.unit.team.core().items().add(monoReward)
        }
    }
}