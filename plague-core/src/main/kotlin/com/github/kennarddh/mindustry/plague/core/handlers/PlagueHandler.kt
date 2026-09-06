package com.github.kennarddh.mindustry.plague.core.handlers

import arc.struct.Seq
import arc.util.Reflect
import arc.util.Time
import arc.util.Timer
import com.github.kennarddh.mindustry.genesis.core.Genesis
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
import com.github.kennarddh.mindustry.genesis.core.timers.annotations.TimerTask
import com.github.kennarddh.mindustry.genesis.standard.commands.parameters.validations.numbers.GTE
import com.github.kennarddh.mindustry.genesis.standard.extensions.infoPopup
import com.github.kennarddh.mindustry.genesis.standard.extensions.setRules
import com.github.kennarddh.mindustry.genesis.standard.handlers.tap.events.DoubleTap
import com.github.kennarddh.mindustry.plague.core.commons.*
import com.github.kennarddh.mindustry.plague.core.commons.extensions.toDisplayString
import com.github.kennarddh.mindustry.plague.core.commons.extensions.toMinutesDisplayString
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import mindustry.Vars
import mindustry.content.Blocks
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
import mindustry.net.Administration.ActionType
import mindustry.net.Administration.Config
import mindustry.net.NetConnection
import mindustry.net.Packets.KickReason
import mindustry.server.ServerControl
import mindustry.type.UnitType
import mindustry.type.Weapon
import mindustry.world.Block
import mindustry.world.Tile
import mindustry.world.blocks.payloads.BuildPayload
import mindustry.world.blocks.payloads.UnitPayload
import mindustry.world.blocks.storage.CoreBlock
import mindustry.world.blocks.storage.CoreBlock.CoreBuild
import mindustry.world.blocks.storage.StorageBlock.StorageBuild
import mindustry.world.blocks.units.Reconstructor.ReconstructorBuild
import mindustry.world.blocks.units.UnitFactory
import mindustry.world.blocks.units.UnitFactory.UnitFactoryBuild
import mindustry.ui.Menus as MindustryMenus
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.round
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds


class PlagueHandler : Handler {
    private val survivorTeamsData: MutableMap<Team, SurvivorTeamData> = ConcurrentHashMap()

    private val teamsPlayersUUIDBlacklist: MutableMap<Team, MutableSet<String>> = ConcurrentHashMap()

    private val storedUnitsWeapons: MutableMap<UnitType, Seq<Weapon>> = ConcurrentHashMap()

    private var lastMinuteUpdatesInMapTimeMinute: Long = -1

    private var lastRoundHistorySampleSecond: Long = -1

    private val roundHistory by lazy {
        RoundHistoryRecorder(
            historyFile = Vars.dataDirectory.child("plague").child("round-history.jsonl").file().toPath(),
            onWriteFailure = { error -> Logger.error("Failed to write round history: ${error.message}") },
            asyncWrites = true,
        )
    }

    private val activePlagueAttackerUnits = mutableListOf<mindustry.gen.Unit>()

    private val activePlagueAttackerUnitsMutex = Mutex()

    private val survivorCreationMutex = Mutex()

    private var cornerSearchComplete = false

    private var cachedSafeCornerTilePosition: Int? = null

    private val pendingRoleChoices = PendingRoleChoiceRegistry<String, Player, NetConnection>()

    private val completedRoleChoices = PendingRoleChoiceRegistry<String, Player, NetConnection>()

    private val roleChoiceMapGeneration = AtomicLong(0)

    private var roleChoiceMenuId = -1

    private enum class SurvivorCreationResult {
        CREATED,
        JOINED,
        NOT_ELIGIBLE,
        FAILED,
    }

    private enum class SurvivorCreationSource {
        AUTOMATIC,
        COMMAND,
        BUILD,
    }

    companion object {
        fun isValidSurvivorTeam(team: Team) = team.id > 6
    }

    private fun currentRoundPlayerCounts(excludedPlayer: Player? = null): RoundPlayerCounts {
        val players = Groups.player.toList().filter { it !== excludedPlayer }
        return RoundPlayerCounts(
            plague = players.count { it.team() == Team.malis },
            survivors = players.count { isValidSurvivorTeam(it.team()) },
            other = players.count { it.team() != Team.malis && !isValidSurvivorTeam(it.team()) },
        )
    }

    private suspend fun observeCurrentRoundPlayers(excludedPlayer: Player? = null) {
        val roundIsActive = PlagueVars.stateLock.withLock { PlagueVars.state != PlagueState.GameOver }
        if (roundIsActive) {
            roundHistory.observePlayers(currentRoundPlayerCounts(excludedPlayer))
        }
    }

    private fun roundSideFor(team: Team): RoundSide = when {
        team == Team.malis -> RoundSide.PLAGUE
        isValidSurvivorTeam(team) -> RoundSide.SURVIVORS
        else -> RoundSide.OTHER
    }

    private fun registerRoleChoiceMenu() {
        var menuId = -1
        menuId = MindustryMenus.registerMenu { player, option ->
            onRoleChoiceSelected(player, option, menuId)
        }
        roleChoiceMenuId = menuId
    }

    override suspend fun onInit() {
        Genesis.commandRegistry.removeCommand("sync")

        registerRoleChoiceMenu()

        // Store for restoring on every SecondPhase
        storeUnitWeapons(UnitTypes.quad)
        storeUnitWeapons(UnitTypes.oct)

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

    @Filter(FilterType.Action, Priority.High)
    suspend fun plagueAttackerUnitsActionFilter(action: Administration.PlayerAction): Boolean {
        val payload = action.payload

        activePlagueAttackerUnitsMutex.withLock {
            if (
                action.type == ActionType.dropPayload &&
                payload is UnitPayload &&
                activePlagueAttackerUnits.contains(payload.unit)
            ) return false

            if (
                action.type == ActionType.control &&
                activePlagueAttackerUnits.contains(action.unit)
            ) return false

            if (
                action.type == ActionType.commandUnits &&
                activePlagueAttackerUnits.contains(action.unit)
            ) return false
        }

        return true
    }

    @Filter(FilterType.Action, Priority.High)
    fun powerSourceActionFilter(action: Administration.PlayerAction): Boolean {
        if (Vars.state.rules.infiniteResources) return true

        val payload = action.payload

        if (action.type == ActionType.dropPayload && payload is BuildPayload && payload.block() == Blocks.powerSource) return false

        if (action.type == ActionType.breakBlock || action.type == ActionType.pickupBlock || action.type == ActionType.placeBlock) {
            if (action.tile?.build?.block == null) return true

            if (action.tile.build.block == Blocks.powerSource) return false
        }

        return true
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
        if (action.type != ActionType.respawn) return true

        if (action.player.team() == Team.blue) return false

        return true
    }

    /**
     * Remove units weapon so they have 0 damage.
     * This is a slight desync but not noticeable because on client they will see they can shoot but with 0 damage.
     */
    private fun clearUnitWeapons(unitType: UnitType) {
        unitType.weapons = Seq()
    }

    private fun storeUnitWeapons(unitType: UnitType) {
        storedUnitsWeapons[unitType] = unitType.weapons
    }

    private fun restoreUnitWeapons(unitType: UnitType) {
        runOnMindustryThread {
            unitType.weapons = storedUnitsWeapons.getOrDefault(unitType, unitType.weapons)
        }
    }

    private fun restoreAllUnitsWeapons() {
        runOnMindustryThread {
            for (storedUnitWeapons in storedUnitsWeapons) {
                storedUnitWeapons.key.weapons = storedUnitWeapons.value
            }
        }
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
        if (player.team() == Team.blue && team != Team.blue) {
            cancelPendingRoleChoiceMenu(player)
        }

        player.team(team)

        updatePlayerSpecificRules(player)
        observeCurrentRoundPlayers()
    }

    private suspend fun prepareSurvivorVictory(preferredWinnerTeams: List<Team> = emptyList()): Team? {
        val players = Groups.player.toList()
        val survivorSideTeams = Team.all.filter { isValidSurvivorTeam(it) }.toSet()
        val winnerCandidates = (
            preferredWinnerTeams +
                survivorTeamsData.keys +
                players.map { it.team() }.filter { isValidSurvivorTeam(it) }
            ).distinct()
        val plan = RoundEndRules.createSurvivorVictoryPlan(
            winnerCandidates,
            survivorSideTeams,
            players.map { it.team() },
        ) ?: return null

        players.zip(plan.normalizedPlayerTeams).forEach { (player, team) ->
            if (player.team() != team) changePlayerTeam(player, team)
        }

        return plan.winnerTeam
    }

    suspend fun onSurvivorTeamDestroyed(reason: RoundEndReason) {
        val state = PlagueVars.stateLock.withLock { PlagueVars.state }

        if (state == PlagueState.GameOver) return

        if (state == PlagueState.Prepare) return

        if (survivorTeamsData.isNotEmpty()) return

        if (state == PlagueState.SuddenDeath) {
            restartWithWinner(reason) {
                Call.infoMessage("[green]All survivors have been destroyed. Plague team won the game.")
                Team.malis
            }
            return
        }

        restartWithWinner(reason) {
            Call.infoMessage("[green]Plague team won the game.")
            Team.malis
        }
    }

    fun leaveSurvivorTeam(player: Player) {
        survivorTeamsData[player.team()]?.playersUUID?.remove(player.uuid())

        val teamData = Vars.state.teams[player.team()]

        // Minus 1 because the player is still in the team
        if (teamData.players.size - 1 == 0) {
            if (player.team() != Team.blue) {
                teamsPlayersUUIDBlacklist.remove(player.team())
                survivorTeamsData.remove(player.team())

                Call.sendMessage("[accent]All ${player.team().name} team players left. Team will be removed.")

                runBlocking {
                    teamData.players.forEach {
                        changePlayerTeam(it, Team.malis)

                        it.unit().kill()

                        Call.sendMessage("[scarlet]'${it.plainName()}' has been infected.")
                    }
                }

                clearTeam(teamData.team)

                CoroutineScopes.Main.launch {
                    onSurvivorTeamDestroyed(RoundEndReason.ALL_SURVIVOR_PLAYERS_LEFT)
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

    private fun isConnectedForRoleChoice(player: Player): Boolean =
        player.isAdded && player.con != null && !player.con.hasDisconnected

    private fun isConnectedPendingRoleChoice(pending: PendingRoleChoice<Player, NetConnection>): Boolean =
        pending.player.isAdded &&
            pending.player.con === pending.connection &&
            !pending.connection.hasDisconnected

    private fun cancelPendingRoleChoiceMenu(player: Player) {
        val connection = player.con ?: return
        val pending = pendingRoleChoices.cancel(player.uuid(), player, connection)
        if (!connection.hasDisconnected) {
            Call.hideFollowUpMenu(connection, pending?.offerId ?: roleChoiceMenuId)
        }
    }

    private fun onRoleChoiceSelected(player: Player, option: Int, menuId: Int) {
        val connection = player.con ?: return
        val pending = pendingRoleChoices.consume(player.uuid(), menuId, player, connection) ?: return
        val shownMapGeneration = pending.mapGeneration
        completedRoleChoices.offer(player.uuid(), shownMapGeneration, menuId, player, connection)

        runOnMindustryThread {
            runBlocking {
                val isPrepare = PlagueVars.stateLock.withLock { PlagueVars.state == PlagueState.Prepare }
                val selectionIsValid = RoleChoiceRules.canApplySelection(
                    shownMapGeneration = shownMapGeneration,
                    currentMapGeneration = roleChoiceMapGeneration.get(),
                    isPrepare = isPrepare,
                    isUnassigned = player.team() == Team.blue,
                    hasSurvivorTeam = survivorTeamsData.isNotEmpty(),
                    isConnected = isConnectedForRoleChoice(player),
                )

                if (!selectionIsValid) {
                    if (isConnectedForRoleChoice(player)) {
                        player.sendMessage("[accent]That choice is no longer available. Your current team was kept.")
                    }
                    return@runBlocking
                }

                when (RoleChoiceRules.choiceForOption(option)) {
                    RoleChoice.SURVIVOR -> joinAvailableSurvivorTeam(player, shownMapGeneration)
                    RoleChoice.PLAGUE -> joinPlagueTeam(player, shownMapGeneration)
                    RoleChoice.LATER -> player.sendMessage(
                        "[accent]Choose before Prepare ends with [gold]/survivor[] or [gold]/plague[]. No choice becomes Plague."
                    )
                }
            }
        }
    }

    private suspend fun showRoleChoiceMenuIfEligible(player: Player) = survivorCreationMutex.withLock {
        val connection = player.con ?: return@withLock
        val mapGeneration = roleChoiceMapGeneration.get()
        val isPrepare = PlagueVars.stateLock.withLock { PlagueVars.state == PlagueState.Prepare }
        val shouldOffer = RoleChoiceRules.shouldOfferMenu(
            isPrepare = isPrepare,
            isUnassigned = player.team() == Team.blue,
            hasSurvivorTeam = survivorTeamsData.isNotEmpty(),
            isConnected = isConnectedForRoleChoice(player),
        )

        if (!shouldOffer) return@withLock

        completedRoleChoices.peek(player.uuid())?.let { completed ->
            if (
                completed.mapGeneration == mapGeneration &&
                completed.player === player &&
                completed.connection === connection
            ) return@withLock

            if (isConnectedPendingRoleChoice(completed)) return@withLock
            completedRoleChoices.cancel(player.uuid(), completed.player, completed.connection)
        }

        pendingRoleChoices.peek(player.uuid())?.let { existing ->
            if (isConnectedPendingRoleChoice(existing)) return@withLock
            pendingRoleChoices.cancel(player.uuid(), existing.player, existing.connection)
        }
        if (!pendingRoleChoices.offer(player.uuid(), mapGeneration, roleChoiceMenuId, player, connection)) return@withLock

        Call.followUpMenu(
            connection,
            roleChoiceMenuId,
            "[gold]Choose Your Side[]",
            "[white]Pick now, or choose later before Prepare ends.[]",
            arrayOf(
                arrayOf("[yellow]Survivor[]\n[lightgray]Build towers and defend your core[]"),
                arrayOf("[green]Plague[]\n[lightgray]Build units and conquer Survivors[]"),
                arrayOf("[gray]Choose Later[]\n[lightgray]Use /survivor or /plague[]"),
            ),
        )
    }

    private suspend fun joinAvailableSurvivorTeam(player: Player, expectedMapGeneration: Long): Boolean =
        survivorCreationMutex.withLock {
            val isPrepare = PlagueVars.stateLock.withLock { PlagueVars.state == PlagueState.Prepare }
            if (
                expectedMapGeneration != roleChoiceMapGeneration.get() ||
                !isPrepare ||
                player.team() != Team.blue ||
                !isConnectedForRoleChoice(player)
            ) {
                player.sendMessage("[accent]Survivor selection is no longer available.")
                return@withLock false
            }

            val selectedTeamId = SurvivorTeamChoiceRules.chooseTeamId(
                survivorTeamsData.map { (team, data) ->
                    SurvivorTeamCandidate(
                        teamId = team.id,
                        locked = data.locked,
                        blacklisted = teamsPlayersUUIDBlacklist[team]?.contains(player.uuid()) == true,
                        hasCore = Vars.state.teams[team].cores.size > 0,
                    )
                }
            )
            val selectedTeam = survivorTeamsData.keys.find { it.id == selectedTeamId }
            val selectedTeamData = selectedTeam?.let { survivorTeamsData[it] }
            val selectedCore = selectedTeam?.let { Vars.state.teams[it].cores.firstOrNull() }

            if (selectedTeam == null || selectedTeamData == null || selectedCore == null) {
                player.sendMessage(
                    "[accent]No open Survivor team is available. Fly to a clear area and use [gold]/survivor[] to start one."
                )
                return@withLock false
            }

            val playerWasAdded = selectedTeamData.playersUUID.add(player.uuid())
            try {
                changePlayerTeam(player, selectedTeam)
                if (!player.dead()) player.unit().kill()
                CoreBlock.playerSpawn(selectedCore.tile, player)
                player.sendMessage("[green]You joined the '${selectedTeam.name}' Survivor team.")
                true
            } catch (error: Exception) {
                if (playerWasAdded) selectedTeamData.playersUUID.remove(player.uuid())
                player.team(Team.blue)
                player.setRules(Vars.state.rules)
                observeCurrentRoundPlayers()
                Logger.error("Failed to join Survivor team from role menu: ${error.message}")
                player.sendMessage("[scarlet]Could not join the Survivor team. Use /survivor or try again.")
                false
            }
        }

    private suspend fun joinPlagueTeam(player: Player, expectedMapGeneration: Long? = null): Boolean =
        survivorCreationMutex.withLock {
            if (expectedMapGeneration != null) {
                val isPrepare = PlagueVars.stateLock.withLock { PlagueVars.state == PlagueState.Prepare }
                if (
                    expectedMapGeneration != roleChoiceMapGeneration.get() ||
                    !isPrepare ||
                    player.team() != Team.blue ||
                    !isConnectedForRoleChoice(player)
                ) {
                    player.sendMessage("[accent]Plague selection is no longer available.")
                    return@withLock false
                }
            }

            if (player.team() == Team.malis) {
                player.sendMessage("[accent]You are already on the Plague team.")
                return@withLock false
            }

            if (isValidSurvivorTeam(player.team())) leaveSurvivorTeam(player)
            changePlayerTeam(player, Team.malis)
            if (player.dead()) {
                getHigestRandomPlagueCore()?.let { CoreBlock.playerSpawn(it.tile, player) }
            } else {
                player.unit().kill()
            }
            player.sendMessage("[green]You joined the Plague team.")
            true
        }

    @Command(["plague"])
    @Description("Change team to plague")
    fun plague(sender: PlayerCommandSender) {
        runOnMindustryThread {
            runBlocking {
                joinPlagueTeam(sender.player)
            }
        }
    }

    @Command(["teaminfo"])
    @Description("Shows info of a team.")
    fun teamInfo(
        sender: CommandSender,
        team: Team = if (sender is PlayerCommandSender) sender.player.team() else Vars.state.map.rules().defaultTeam,
    ) {
        val teamData = Vars.state.teams[team]

        if (team == Team.malis) {
            sender.sendSuccess(
                """
                Team: Plague
                Players: (${teamData.players.size}) ${teamData.players.joinToString(", ") { it.plainName() }}
                Cores: (${teamData.cores.size})
                Units: (${teamData.units.size})
                """.trimIndent()
            )
        } else if (isValidSurvivorTeam((team))) {
            val survivorTeamData = survivorTeamsData[team]
                ?: return sender.sendError("Error occurred. SurvivorTeamData == null.")

            sender.sendSuccess(
                """
                Team: ${team.name} (Survivor)
                Owner: ${
                    (Groups.player.find { it.uuid() == survivorTeamData.ownerUUID }
                        .plainName()) ?: "[accent]${survivorTeamData.ownerUUID}[]"
                }
                Locked: ${survivorTeamData.locked}
                Players: (${teamData.players.size}) ${
                    survivorTeamData.playersUUID.joinToString(", ") { playerUUID ->
                        (Groups.player.find { it.uuid() == playerUUID }.plainName()) ?: "[accent]${playerUUID}[]"
                    }
                }
                Cores: (${teamData.cores.size})
                Units: (${teamData.units.size})
                """.trimIndent()
            )
        } else if (team == Team.blue) {
            sender.sendSuccess(
                """
                Team: No Team
                Players: (${teamData.players.size}) ${teamData.players.joinToString(", ") { it.plainName() }}
                """.trimIndent()
            )
        } else {
            sender.sendError("Invalid team '${team.name}'.")
        }
    }

    @Command(["teamleave"])
    @Description("Leave your current team.")
    suspend fun teamLeave(sender: PlayerCommandSender) {
        if (sender.player.team() == Team.blue)
            return sender.sendError("You are not in any team.")

        PlagueVars.stateLock.withLock {
            if (PlagueVars.state == PlagueState.Prepare) {
                runOnMindustryThread {
                    runBlocking {
                        if (isValidSurvivorTeam(sender.player.team()))
                            leaveSurvivorTeam(sender.player)

                        val randomPlagueCore = getHigestRandomPlagueCore()

                        CoreBlock.playerSpawn(randomPlagueCore!!.tile, sender.player)

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

                changePlayerTeam(sender.player, Team.malis)

                sender.player.unit().kill()

                sender.sendSuccess("You are now in plague team.")
            }
        }
    }

    @Command(["teamkick"])
    @Description("Kick someone from your team. Only for survivor team's owner.")
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
    @Description("Transfer team's ownership to someone in your team. Only for survivor team's owner.")
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
    @Description("Prevent new player from joining your team. Only for survivor team's owner.")
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
                    checkTile.block() != Blocks.air ||
                    // Any liquid floor
                    checkTile.floor().isLiquid ||
                    // Exactly same block
                    (block == checkTile.block() && checkTile.build != null && block.rotate) ||
                    !checkTile.floor().placeableOn
                )
                    return false
            }
        }

        return true
    }

    private fun findValidSurvivorCornerTile(): Tile? {
        if (cornerSearchComplete) {
            return cachedSafeCornerTilePosition?.let { Vars.world.tile(it) }
        }

        val margin = max(5, Blocks.coreFoundation.size + 2)
        val width = Vars.world.width()
        val height = Vars.world.height()

        if (width <= margin * 2 || height <= margin * 2) {
            cornerSearchComplete = true
            return null
        }

        val anchors = CornerSpawnRules.cornerAnchors(width, height, margin)
            .sortedByDescending { anchor ->
                Team.malis.cores().minOfOrNull { core ->
                    core.dst(anchor.x * Vars.tilesize.toFloat(), anchor.y * Vars.tilesize.toFloat())
                } ?: Float.MAX_VALUE
            }
        val candidates = CornerSpawnRules.boundedCandidates(
            anchors = anchors,
            width = width,
            height = height,
            maximumRadius = minOf(minOf(width, height) / 4, 96),
            stride = 1,
            maximumCandidates = 8192,
        )

        for (candidate in candidates) {
            val tile = Vars.world.tile(candidate.x, candidate.y) ?: continue
            if (!validPlace(Blocks.coreFoundation, tile)) continue

            val farEnough = Team.malis.cores().all { core ->
                SurvivorStartRules.isFarEnoughFromPlague(
                    core.dst(tile),
                    Vars.tilesize,
                    PlagueVars.survivorsMinBuildRangeFromPlagueCoreInTiles
                )
            }

            if (farEnough) {
                cornerSearchComplete = true
                cachedSafeCornerTilePosition = tile.pos()
                return tile
            }
        }

        cornerSearchComplete = true
        cachedSafeCornerTilePosition = null
        return null
    }

    fun getClosestEnemyCore(
        x: Float,
        y: Float,
        distanceRange: ClosedFloatingPointRange<Float> = 0f..Float.MAX_VALUE,
        ignoredTeams: List<Team> = listOf(),
    ): DistanceData<CoreBuild?> {
        var closest: CoreBuild? = null
        var closestDistance = Float.MAX_VALUE

        for (activeTeam in Vars.state.teams.active) {
            if (ignoredTeams.contains(activeTeam.team)) continue

            for (core in activeTeam.cores) {
                val distance = core.dst(x, y)

                if (distance !in distanceRange) continue

                if (closestDistance > distance) {
                    closest = core

                    closestDistance = distance
                }
            }
        }

        return DistanceData(closest, closestDistance)
    }

    fun clearTeam(team: Team) {
        val teamData = Vars.state.teams[team]

        teamData.units.forEach { it.kill() }

        Vars.world.tiles.forEach {
            if (it.team() == team) {
                it.build?.kill()
            }
        }
    }

    @EventHandler(true)
    suspend fun onCoreDestroyed(event: EventType.BlockDestroyEvent) {
        if (event.tile.build !is CoreBuild) return

        val coreBuild = event.tile.build as CoreBuild

        if (coreBuild.team == Team.malis) {
            if (!RoundEndRules.isLastCoreBeforeRemoval(coreBuild.team.cores().size)) return

            restartWithWinner(RoundEndReason.PLAGUE_CORE_DESTROYED) {
                val winnerTeam = prepareSurvivorVictory()
                when (RoundEndRules.winnerWhenPlagueCoreDestroyed(winnerTeam != null)) {
                    RoundWinner.PLAGUE -> {
                        Call.infoMessage("[green]Plague team won the game. No Survivor team remained.")
                        Team.malis
                    }

                    RoundWinner.SURVIVORS -> {
                        Call.infoMessage("[green]The Plague core was destroyed. Survivor teams won the game.")
                        requireNotNull(winnerTeam)
                    }
                }
            }
            return
        }

        // Claim the team before cleanup. clearTeam() can try to kill this same core again.
        RoundEndRules.claimEliminatedTeam(
            coreBuild.team,
            coreBuild.team.cores().size,
            survivorTeamsData,
        ) ?: return

        val teamData = Vars.state.teams[coreBuild.team]

        Call.sendMessage("[scarlet]'${coreBuild.team.name}' survivor team lost.")

        clearTeam(coreBuild.team)

        teamData.players.forEach {
            changePlayerTeam(it, Team.malis)

            it.unit().kill()

            Call.sendMessage("[scarlet]'${it.plainName()}' has been infected.")
        }

        CoroutineScopes.Main.launch {
            onSurvivorTeamDestroyed(RoundEndReason.LAST_SURVIVOR_CORE_DESTROYED)
        }
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


    @EventHandler(true)
    fun survivorsMinimumBuildRangeFromPlagueCoreEventHandler(event: EventType.BuildSelectEvent) {
        if (event.builder.player == null) return
        if (!isValidSurvivorTeam(event.builder.team())) return

        for (core in Team.malis.cores()) {
            if (core.dst(event.tile) < PlagueVars.survivorsMinBuildRangeFromPlagueCoreInTiles * Vars.tilesize) {
                event.tile.removeNet()

                event.builder.player.sendMessage("[scarlet]Building must be at least 100 tiles away from nearest plague's core.")

                return
            }
        }
    }

    @Command(["survivor"])
    @Description("Create or join a survivor team at your current position.")
    fun survivorCommand(sender: PlayerCommandSender) {
        runOnMindustryThread {
            val tile: Tile? = sender.player.tileOn()
            if (tile == null) {
                sender.player.sendMessage("[scarlet]You are outside the map. Move over a valid tile and try /survivor again.")
                return@runOnMindustryThread
            }

            runBlocking {
                createSurvivorCore(sender.player, tile, SurvivorCreationSource.COMMAND)
            }
        }
    }

    @EventHandler(true)
    suspend fun createSurvivorCoreEventHandler(event: EventType.BuildSelectEvent) {
        if (event.builder.player == null) return
        if (event.builder.team() != Team.blue) return
        if (event.breaking) return

        createSurvivorCore(event.builder.player, event.tile, SurvivorCreationSource.BUILD)
    }

    private suspend fun createSurvivorCore(
        player: Player,
        tile: Tile,
        source: SurvivorCreationSource,
        requireFirstSurvivor: Boolean = false,
    ): SurvivorCreationResult = survivorCreationMutex.withLock {
        val isPrepare = PlagueVars.stateLock.withLock { PlagueVars.state == PlagueState.Prepare }
        if (!isPrepare) {
            player.sendMessage("[scarlet]Survivor teams can only be started during the Prepare stage.")
            return@withLock SurvivorCreationResult.NOT_ELIGIBLE
        }

        if (player.team() != Team.blue) {
            if (!requireFirstSurvivor) player.sendMessage("[scarlet]You are already on a team.")
            return@withLock SurvivorCreationResult.NOT_ELIGIBLE
        }

        if (requireFirstSurvivor && survivorTeamsData.isNotEmpty()) {
            return@withLock SurvivorCreationResult.NOT_ELIGIBLE
        }

        // A blue build is only a placement trigger. Consume it after eligibility is confirmed.
        if (source == SurvivorCreationSource.BUILD) tile.removeNet()

        if (!validPlace(Blocks.coreFoundation, tile)) {
            player.sendMessage("[scarlet]Invalid core position. Move to a clear 4 by 4 area and try /survivor again.")
            return@withLock SurvivorCreationResult.FAILED
        }

        for (core in Team.malis.cores()) {
            if (!SurvivorStartRules.isFarEnoughFromPlague(
                    core.dst(tile),
                    Vars.tilesize,
                    PlagueVars.survivorsMinBuildRangeFromPlagueCoreInTiles
                )
            ) {
                player.sendMessage("[scarlet]Core must be at least 100 tiles away from nearest plague's core.")
                return@withLock SurvivorCreationResult.FAILED
            }
        }

        val (closestEnemyCoreInRange, distanceToClosestEnemyCoreInRange) = getClosestEnemyCore(
            tile.x.toFloat() * Vars.tilesize,
            tile.y.toFloat() * Vars.tilesize,
            0f..((PlagueVars.survivorCoreMaxJoinDistanceInTiles * Vars.tilesize).toFloat()),
            listOf(Team.malis)
        )

        if (distanceToClosestEnemyCoreInRange < PlagueVars.newSurvivorCoreMinDistanceFromPlagueCoreInTiles * Vars.tilesize) {
            player.sendMessage("[scarlet]Core must be at least 70 tiles away from nearest survivor's core.")
            return@withLock SurvivorCreationResult.FAILED
        }

        if (closestEnemyCoreInRange != null) {
            val survivorTeamData = survivorTeamsData[closestEnemyCoreInRange.team()]
            if (survivorTeamData == null) {
                player.sendMessage("[scarlet]The nearby Survivor team is no longer available. Try again.")
                return@withLock SurvivorCreationResult.FAILED
            }

            if (teamsPlayersUUIDBlacklist[closestEnemyCoreInRange.team]?.contains(player.uuid()) == true) {
                player.sendMessage("[scarlet]You are blacklisted from joining the team '${closestEnemyCoreInRange.team.name}'.")
                return@withLock SurvivorCreationResult.NOT_ELIGIBLE
            }

            if (survivorTeamData.locked) {
                player.sendMessage("[scarlet]The closest team '${closestEnemyCoreInRange.team.name}' is locked.")
                return@withLock SurvivorCreationResult.NOT_ELIGIBLE
            }

            val playerWasAdded = survivorTeamData.playersUUID.add(player.uuid())
            try {
                changePlayerTeam(player, closestEnemyCoreInRange.team)
                tile.setNet(Blocks.coreFoundation, closestEnemyCoreInRange.team, 0)
                val coreBuild = tile.build as? CoreBuild
                    ?: error("Core foundation did not create a CoreBuild")
                Vars.state.teams.registerCore(coreBuild)
                if (player.dead()) CoreBlock.playerSpawn(tile, player) else player.unit().kill()
                player.sendMessage("[green]You joined the '${closestEnemyCoreInRange.team.name}' Survivor team.")
                return@withLock SurvivorCreationResult.JOINED
            } catch (error: Exception) {
                if (playerWasAdded) survivorTeamData.playersUUID.remove(player.uuid())
                if (tile.build?.team == closestEnemyCoreInRange.team) tile.removeNet()
                player.team(Team.blue)
                player.setRules(Vars.state.rules)
                observeCurrentRoundPlayers()
                Logger.error("Failed to join Survivor team: ${error.message}")
                player.sendMessage("[scarlet]Could not create the Survivor core. Please try again.")
                return@withLock SurvivorCreationResult.FAILED
            }
        }

        val newTeam = getNewEmptySurvivorTeam()
        if (newTeam == null) {
            player.sendMessage("[scarlet]No Survivor team is available.")
            return@withLock SurvivorCreationResult.FAILED
        }

        survivorTeamsData[newTeam] = SurvivorTeamData(
            player.uuid(), mutableSetOf(player.uuid())
        )
        teamsPlayersUUIDBlacklist[newTeam] = Collections.synchronizedSet(mutableSetOf())

        try {
            changePlayerTeam(player, newTeam)
            tile.setNet(Blocks.coreFoundation, newTeam, 0)
            val coreBuild = tile.build as? CoreBuild
                ?: error("Core foundation did not create a CoreBuild")
            Vars.state.teams.registerCore(coreBuild)

            Vars.state.rules.loadout.forEach {
                coreBuild.items.add(it.item, it.amount.coerceAtMost(coreBuild.storageCapacity))
            }

            if (player.dead()) CoreBlock.playerSpawn(tile, player) else player.unit().kill()
            val successMessage = if (source == SurvivorCreationSource.AUTOMATIC) {
                "[green]Your Survivor core was created near a safe map corner. Build defenses now."
            } else {
                "[green]Your Survivor core was created. Build defenses now."
            }
            player.sendMessage(successMessage)
            return@withLock SurvivorCreationResult.CREATED
        } catch (error: Exception) {
            survivorTeamsData.remove(newTeam)
            teamsPlayersUUIDBlacklist.remove(newTeam)
            if (tile.build?.team == newTeam) tile.removeNet()
            player.team(Team.blue)
            player.setRules(Vars.state.rules)
            observeCurrentRoundPlayers()
            Logger.error("Failed to create Survivor team: ${error.message}")
            player.sendMessage("[scarlet]Could not create the Survivor core. Please try again.")
            return@withLock SurvivorCreationResult.FAILED
        }
    }

    fun getDefaultPlayerHUDInfo(): PlayerHUDInfo {
        val defaultPreset = PlagueVars.playerHUDPresets[PlagueVars.playerHUDPresetDefaultIndex]

        return PlayerHUDInfo(
            true,
            defaultPreset.align,
            defaultPreset.top,
            defaultPreset.left,
            defaultPreset.bottom,
            defaultPreset.right
        )
    }

    fun safeGetPlayerHUDInfo(player: Player): PlayerHUDInfo {
        return PlagueVars.playersHUDInfo.getOrPut(player, this::getDefaultPlayerHUDInfo)
    }

    @Command(["hud"])
    @Description("Toggle HUD.")
    fun toggleHUD(sender: PlayerCommandSender) {
        val playerHUDInfo = safeGetPlayerHUDInfo(sender.player)

        playerHUDInfo.active = !playerHUDInfo.active

        sender.sendSuccess("HUD will be ${if (playerHUDInfo.active) "shown" else "hidden"} in a moment.")
    }

    @Command(["hudreset"])
    @Description("Reset HUD.")
    fun resetHUD(sender: PlayerCommandSender) {
        PlagueVars.playersHUDInfo.remove(sender.player)

        sender.sendSuccess("HUD will be reset in a moment.")
    }

    @Command(["hudinfo"])
    @Description("Reset HUD.")
    fun hudInfo(sender: PlayerCommandSender) {
        val playerHUDInfo = safeGetPlayerHUDInfo(sender.player)

        sender.sendSuccess(
            """
            HUD Info:
            Active: ${playerHUDInfo.active.toDisplayString()}
            Align: ${playerHUDInfo.align.displayName}
            Top: ${playerHUDInfo.top}
            Left: ${playerHUDInfo.left}
            Bottom: ${playerHUDInfo.bottom}
            Right: ${playerHUDInfo.right}
            """.trimIndent()
        )
    }

    @Command(["hudpreset"])
    @Description("Reset HUD.")
    suspend fun hudPreset(sender: PlayerCommandSender, @GTE(0) presetIndex: Int) {
        val preset = PlagueVars.playerHUDPresets.getOrNull(presetIndex)
            ?: return sender.sendError("Preset with index $presetIndex not found.")

        val playerHUDInfo = safeGetPlayerHUDInfo(sender.player)

        playerHUDInfo.align = preset.align
        playerHUDInfo.top = preset.top
        playerHUDInfo.left = preset.left
        playerHUDInfo.bottom = preset.bottom
        playerHUDInfo.right = preset.right

        updatePlayerHUD(sender.player)

        sender.sendSuccess("HUD updated.")
    }

    @Command(["hudchange"])
    @Description("Change HUD.")
    suspend fun changeHUD(
        sender: PlayerCommandSender,
        align: Align? = null,
        top: Int? = null,
        left: Int? = null,
        bottom: Int? = null,
        right: Int? = null,
    ) {
        val playerHUDInfo = safeGetPlayerHUDInfo(sender.player)

        playerHUDInfo.align = align ?: playerHUDInfo.align
        playerHUDInfo.top = top ?: playerHUDInfo.top
        playerHUDInfo.left = left ?: playerHUDInfo.left
        playerHUDInfo.bottom = bottom ?: playerHUDInfo.bottom
        playerHUDInfo.right = right ?: playerHUDInfo.right

        updatePlayerHUD(sender.player)

        sender.sendSuccess("HUD updated.")
    }

    private suspend fun updatePlayerHUD(player: Player) {
        val playerHUDInfo = safeGetPlayerHUDInfo(player)

        if (!playerHUDInfo.active) return

        val state = PlagueVars.stateLock.withLock { PlagueVars.state }

        val durationToNextInfo = (PlagueVars.mapTime.inWholeMinutes + 1).minutes - PlagueVars.mapTime

        player.infoPopup(
            """
            State: ${state.displayName}
            Map Time: ${PlagueVars.mapTime.toMinutesDisplayString()}
            Plague Unit Multiplier: ${round(getPlagueUnitMultiplier()).toInt()}x
            ${if (PlagueVars.totalMapSkipDuration == Duration.ZERO) "" else "Map Skip Duration: ${PlagueVars.totalMapSkipDuration.toDisplayString()}"}
            
            Run [accent]/hud[white] to toggle this.
            Run [accent]/state[white] for more detailed data.
            """.trimIndent(),
            durationToNextInfo.inWholeSeconds.toFloat(),
            playerHUDInfo.align.arcAlign,
            playerHUDInfo.top,
            playerHUDInfo.left,
            playerHUDInfo.bottom,
            playerHUDInfo.right
        )
    }

    @EventHandler(true)
    @EventHandlerTrigger(Trigger.update)
    suspend fun onUpdate() {
        val plagueBannedUnits = PlagueBanned.getCurrentPlagueBannedUnits(false)
        val survivorsBannedUnits = PlagueBanned.getCurrentSurvivorsBannedUnits(false)

        if (Vars.state.gameOver) return

        val currentRoundSecond = PlagueVars.mapTime.inWholeSeconds
        if (lastRoundHistorySampleSecond != currentRoundSecond) {
            lastRoundHistorySampleSecond = currentRoundSecond
            observeCurrentRoundPlayers()
        }

        PlagueVars.stateLock.withLock {
            if (PlagueVars.state == PlagueState.Prepare) {
                val now = Clock.System.now()
                val elapsedMillis = PlagueVars.prepareTimer.elapsedMillis(
                    now.toEpochMilliseconds(),
                    Groups.player.size()
                )
                PlagueVars.mapStartTime = Instant.fromEpochMilliseconds(now.toEpochMilliseconds() - elapsedMillis)
            }
        }

        // Make sure blue team units cannot be killed
        Groups.unit.forEach {
            if (it.team != Team.blue) return@forEach

            it.health = Float.MAX_VALUE
        }

        // Make sure malis core cannot be destroyed
        Team.malis.cores().forEach {
            it.health = Float.MAX_VALUE
        }

        Groups.build.forEach {
            if (!(it.team == Team.malis || isValidSurvivorTeam(it.team))) return@forEach

            val bannedUnits = if (it.team == Team.malis) plagueBannedUnits else survivorsBannedUnits

            if (it is UnitFactoryBuild) {
                if (it.currentPlan == -1) return@forEach

                val block = it.block as UnitFactory

                if (bannedUnits.contains(block.plans[it.currentPlan].unit)) {
                    it.enabled = false
                } else {
                    it.enabled = true
                }
            } else if (it is ReconstructorBuild) {
                if (it.payload == null) return@forEach

                if (bannedUnits.contains(it.upgrade(it.payload.unit.type))) {
                    it.enabled = false
                } else {
                    it.enabled = true
                }
            }
        }

        if (lastMinuteUpdatesInMapTimeMinute != PlagueVars.mapTime.inWholeMinutes) {
            lastMinuteUpdatesInMapTimeMinute = PlagueVars.mapTime.inWholeMinutes

            val plagueUnitMultiplier = getPlagueUnitMultiplier()

            Vars.state.rules.teams[Team.malis].unitDamageMultiplier =
                (Vars.state.map.rules().teams[Team.malis]?.unitDamageMultiplier
                    ?: Vars.state.map.rules().unitDamageMultiplier) * plagueUnitMultiplier

            Vars.state.rules.teams[Team.malis].unitHealthMultiplier =
                (Vars.state.map.rules().teams[Team.malis]?.unitHealthMultiplier
                    ?: Vars.state.map.rules().unitHealthMultiplier) * plagueUnitMultiplier

            runBlocking {
                updateAllPlayerSpecificRules()

                Groups.player.forEach {
                    updatePlayerHUD(it)
                }
            }
        }

        // Like this to prevent locking state deadlock and also prevent locking mindustry thread
        CoroutineScopes.Main.launch {
            PlagueVars.stateLock.withLock {
                if (PlagueVars.state == PlagueState.Prepare && PlagueVars.mapTime >= PlagueVars.state.startTime) {
                    CoroutineScopes.Main.launch { onFirstPhase() }
                } else if (PlagueVars.state == PlagueState.PlayingFirstPhase && PlagueVars.mapTime >= PlagueVars.state.startTime) {
                    CoroutineScopes.Main.launch { onSecondPhase() }
                } else if (PlagueVars.state == PlagueState.PlayingSecondPhase && PlagueVars.mapTime >= PlagueVars.state.startTime) {
                    CoroutineScopes.Main.launch { onSuddenDeath() }
                } else {
                    // Empty else because this 'if' is seen as an expression
                }
            }
        }
    }

    @EventHandler(true)
    suspend fun onPlay(event: EventType.PlayEvent) {
        survivorCreationMutex.withLock {
            // Survivor teams and their cores belong to one map only.
            // Clear this before connection confirmation can assign players for the new map.
            survivorTeamsData.clear()
            teamsPlayersUUIDBlacklist.clear()
            pendingRoleChoices.clear()
            completedRoleChoices.clear()
            roleChoiceMapGeneration.incrementAndGet()
            registerRoleChoiceMenu()
            lastMinuteUpdatesInMapTimeMinute = -1
            lastRoundHistorySampleSecond = -1
            cornerSearchComplete = false
            cachedSafeCornerTilePosition = null

            PlagueVars.stateLock.withLock {
                PlagueVars.state = PlagueState.Prepare
            }
        }

        PlagueVars.totalMapSkipDuration = 0.seconds
        PlagueVars.mapStartTime = Clock.System.now()
        PlagueVars.prepareTimer.elapsedMillis(PlagueVars.mapStartTime.toEpochMilliseconds(), 0)
        roundHistory.startRound(Vars.state.map.plainName())
        observeCurrentRoundPlayers()

        // Clear mono tree units weapons on every map start
        clearUnitWeapons(UnitTypes.alpha)
        clearUnitWeapons(UnitTypes.beta)
        clearUnitWeapons(UnitTypes.gamma)
        clearUnitWeapons(UnitTypes.flare)
        clearUnitWeapons(UnitTypes.mono)
        clearUnitWeapons(UnitTypes.poly)
        clearUnitWeapons(UnitTypes.mega)
        clearUnitWeapons(UnitTypes.quad)
        clearUnitWeapons(UnitTypes.oct)

        Team.malis.items()?.clear()

        // Make sure power source cannot be destroyed and cannot be disabled
        Vars.world.tiles.forEach {
            if (it.build == null) return@forEach
            if (it.build.block != Blocks.powerSource) return@forEach
            if (it.build.team != Team.malis) return@forEach

            it.build.health = Float.MAX_VALUE
        }
    }

    @EventHandler(true)
    fun onPlayerLeave(event: EventType.PlayerLeave) {
        event.player.con?.let { connection ->
            pendingRoleChoices.cancel(event.player.uuid(), event.player, connection)
            completedRoleChoices.cancel(event.player.uuid(), event.player, connection)
        }
        runBlocking {
            observeCurrentRoundPlayers(excludedPlayer = event.player)
        }

        val teamOwned = survivorTeamsData.entries.find { it.value.ownerUUID == event.player.uuid() }

        if (teamOwned == null) return

        val teamData = Vars.state.teams[teamOwned.key]

        if (teamData.players.size == 0)
            return

        // Team's owner is not given to other player because the owner might join again.
        Groups.player.filter { survivorTeamsData[teamOwned.key]?.playersUUID?.contains(it.uuid()) ?: false }
            .forEach {
                it.sendMessage("[accent]Team owner left.")
            }
    }

    suspend fun restart(winner: Team) = restartWithWinner(RoundEndReason.MANUAL) { winner }

    private suspend fun restartWithWinner(
        reason: RoundEndReason,
        resolveWinner: suspend () -> Team,
    ) {
        val restartResult = survivorCreationMutex.withLock {
            val mayRestart = PlagueVars.stateLock.withLock {
                if (!RoundEndRules.canClaimGameOver(PlagueVars.state)) {
                    false
                } else {
                    PlagueVars.state = PlagueState.GameOver
                    true
                }
            }
            if (!mayRestart) return@withLock null

            var resolutionFailed = false
            val endPlayers = try {
                runOnMindustryThreadSuspended { currentRoundPlayerCounts() }
            } catch (error: Exception) {
                resolutionFailed = true
                Logger.error("Failed to capture final round player counts: ${error.message}")
                RoundPlayerCounts(plague = 0, survivors = 0, other = 0)
            }
            val resolvedWinner = try {
                resolveWinner()
            } catch (error: Exception) {
                resolutionFailed = true
                Logger.error("Failed to resolve round winner: ${error.message}")
                null
            }

            survivorTeamsData.clear()
            teamsPlayersUUIDBlacklist.clear()
            pendingRoleChoices.clear()
            completedRoleChoices.clear()
            roleChoiceMapGeneration.incrementAndGet()
            lastMinuteUpdatesInMapTimeMinute = -1
            lastRoundHistorySampleSecond = -1
            cornerSearchComplete = false
            cachedSafeCornerTilePosition = null

            Triple(resolvedWinner, endPlayers, resolutionFailed)
        } ?: return

        val (winner, endPlayers, resolutionFailed) = restartResult

        activePlagueAttackerUnitsMutex.withLock {
            activePlagueAttackerUnits.clear()
        }

        val roundExtraTimeDuration = Config.roundExtraTime.num().seconds

        val fallbackMap = Vars.state.map
        val map = try {
            runOnMindustryThreadSuspended {
            val selectedMap = try {
                Vars.maps.getNextMap(ServerControl.instance.lastMode, Vars.state.map)
            } catch (error: Exception) {
                Logger.error("Failed to select the next map; replaying the current map: ${error.message}")
                null
            }
            val map = selectedMap ?: Vars.state.map.also {
                Logger.warn("No next map was available; replaying '${it.plainName()}'.")
            }

            runCatching {
                Call.infoMessage(
                    """
                    [scarlet]Game over!
                    [white]Next selected map: [white]${map.name()}[white]${if (map.hasTag("author")) " by [white]${map.author()}" else ""}.
                    [white]New game begins in ${roundExtraTimeDuration.toDisplayString()}.
                    """.trimIndent()
                )
            }.onFailure { Logger.error("Failed to send game-over message: ${it.message}") }

            val publicationSucceeded = if (!resolutionFailed && winner != null) {
                try {
                    Call.updateGameOver(winner)
                    true
                } catch (error: Exception) {
                    Logger.error("Failed to publish game-over winner: ${error.message}")
                    false
                }
            } else {
                false
            }

            when {
                resolutionFailed || winner == null -> roundHistory.interruptRound(
                    RoundInterruptionReason.ROUND_RESOLUTION_FAILED,
                    endPlayers,
                )
                !publicationSucceeded -> roundHistory.interruptRound(
                    RoundInterruptionReason.GAME_OVER_PUBLICATION_FAILED,
                    endPlayers,
                )
                else -> roundHistory.completeRound(roundSideFor(winner), reason, endPlayers)
            }

            Logger.info("Selected next map to be '${map.plainName()}'.")

            ServerControl.instance.inGameOverWait = true

            // TODO: When v147 released replace this with ServerControl.instance.cancelPlayTask()
            runCatching { Reflect.get<Timer.Task>(ServerControl.instance, "lastTask")?.cancel() }
                .onFailure { Logger.error("Failed to cancel prior map task: ${it.message}") }

                return@runOnMindustryThreadSuspended map
            } ?: fallbackMap
        } catch (error: Exception) {
            Logger.error("Post-game transition failed; replaying the current map: ${error.message}")
            roundHistory.interruptRound(RoundInterruptionReason.POST_CLAIM_TRANSITION_FAILED, endPlayers)
            fallbackMap
        }

        delay(roundExtraTimeDuration)

        runOnMindustryThread {
            try {
                val reloader = PlagueWorldReloader()

                reloader.begin()

                Vars.world.loadMap(map)

                Vars.state.rules = PlagueRules.initRules(Vars.state.map.rules())

                Vars.logic.play()

                reloader.end()

                ServerControl.instance.inGameOverWait = false
            } catch (error: MapException) {
                Logger.error("${error.map.plainName()}: ${error.message}")

                Vars.net.closeServer()
            }
        }
    }

    fun getHigestRandomPlagueCore(): CoreBuild? {
        val sortedPlagueCores = Team.malis.cores().toList().sortedByDescending {
            when (it.block.name) {
                "core-shard" -> 1
                "core-foundation" -> 2
                "core-nucleus" -> 3
                else -> 1
            }
        }

        val bestCoreName = sortedPlagueCores[0]?.block?.name ?: return null

        val bestCores = sortedPlagueCores.filter { it.block.name == bestCoreName }

        return bestCores.random()
    }

    fun getRandomPlagueCore(): CoreBuild? = Team.malis.cores().toList().random()

    suspend fun setupPlayer(player: Player) {
        if (player.team() == Team.blue) {
            val randomPlagueCore = getHigestRandomPlagueCore()

            CoreBlock.playerSpawn(randomPlagueCore!!.tile, player)
        }

        updatePlayerHUD(player)

        updatePlayerSpecificRules(player)
    }

    fun getPlagueUnitMultiplier(): Float {
        val mapTimeInMinutes = max(1, PlagueVars.mapTime.inWholeMinutes.toInt())

        if (mapTimeInMinutes < 40)
            return 1.2f.pow(mapTimeInMinutes / 10f)

        if (mapTimeInMinutes in 40..<80)
            return 2.07f * 1.4f.pow((mapTimeInMinutes - 40) / 10f)

        if (mapTimeInMinutes in 80..<120)
            return 7.96f * 1.6f.pow((mapTimeInMinutes - 80) / 10f)

        return 52.2f * 1.8f.pow((mapTimeInMinutes - 120) / 10f)
    }

    fun getPlagueAttackerUnitsCount(): Int {
        val mapTimeInMinutes = max(1, PlagueVars.mapTime.inWholeMinutes.toInt())

        return floor(((mapTimeInMinutes - 50f) / 10f).pow(1.5f)).toInt()
    }

    /**
     * This is also called when player is done loading new map
     */
    @EventHandler(true)
    fun onPlayerConnectionConfirmed(event: EventType.PlayerConnectionConfirmed) {
        runBlocking {
            if (event.player.dead()) {
                val shouldCreateFirstSurvivor = PlagueVars.stateLock.withLock {
                    PlagueVars.state == PlagueState.Prepare &&
                        event.player.team() == Team.blue &&
                        survivorTeamsData.isEmpty()
                }

                if (shouldCreateFirstSurvivor) {
                    val cornerTile = findValidSurvivorCornerTile()

                    if (cornerTile != null) {
                        createSurvivorCore(
                            event.player,
                            cornerTile,
                            SurvivorCreationSource.AUTOMATIC,
                            requireFirstSurvivor = true,
                        )
                    } else {
                        event.player.sendMessage("[scarlet]No safe corner core position was found. Fly to a clear area and type /survivor.")
                    }
                }

                // Initialize HUD and player-specific rules after team/spawn selection.
                setupPlayer(event.player)
            }

            showRoleChoiceMenuIfEligible(event.player)
            observeCurrentRoundPlayers()
        }
    }

    @Command(["state"])
    @Description("Get detailed game state/data.")
    suspend fun getState(sender: CommandSender) {
        PlagueVars.stateLock.withLock {
            sender.sendSuccess(
                """
                State: ${PlagueVars.state.displayName}
                Map Time: ${PlagueVars.mapTime.toDisplayString()}
                Map Skip Duration: ${PlagueVars.totalMapSkipDuration.toDisplayString()}
                Plague Unit Multiplier: ${getPlagueUnitMultiplier()}x
                """.trimIndent()
            )
        }
    }

    @TimerTask(0f, 60f)
    suspend fun spawnPlagueAttackerZenithTimerTask() {
        PlagueVars.stateLock.withLock {
            if (!(PlagueVars.state == PlagueState.PlayingSecondPhase || PlagueVars.state == PlagueState.SuddenDeath)) return
        }

        val zenithCount = getPlagueAttackerUnitsCount()

        runOnMindustryThreadSuspended {
            val core = getRandomPlagueCore()

            val units = mutableListOf<mindustry.gen.Unit>()

            for (i in 0..<zenithCount) {
                val unit = UnitTypes.zenith.spawn(core, Team.malis)

                unit.team.data().updateCount(unit.type, -1)

                unit.controller(PlagueAttackerFlyingAI())

                units.add(unit)
            }

            runBlocking {
                activePlagueAttackerUnitsMutex.withLock {
                    activePlagueAttackerUnits.addAll(units)
                }
            }
        }
    }

    suspend fun onFirstPhase() {
        val phaseStarted = survivorCreationMutex.withLock {
            PlagueVars.stateLock.withLock {
                if (PlagueVars.state != PlagueState.Prepare) {
                    false
                } else {
                    PlagueVars.state = PlagueState.PlayingFirstPhase
                    true
                }
            }
        }

        if (!phaseStarted) return

        runOnMindustryThread {
            pendingRoleChoices.clear().forEach { pending ->
                if (isConnectedPendingRoleChoice(pending)) {
                    Call.hideFollowUpMenu(pending.connection, pending.offerId)
                }
            }

            Vars.state.rules.enemyCoreBuildRadius = Vars.state.map.rules().enemyCoreBuildRadius

            if (!Vars.state.teams.active.any { isValidSurvivorTeam(it.team) }) {
                CoroutineScopes.Main.launch {
                    restartWithWinner(RoundEndReason.NO_SURVIVORS) {
                        Call.infoMessage("No survivors. Plague team won the game.")
                        Team.malis
                    }
                }

                return@runOnMindustryThread
            }

            // Move every no team player to plague team
            Groups.player.filter { it.team() == Team.blue }.forEach {
                runBlocking {
                    changePlayerTeam(it, Team.malis)

                    it.unit().kill()
                }
            }

            runBlocking {
                updateAllPlayerSpecificRules()
            }
        }
    }

    private suspend fun onSecondPhase() {
        val phaseStarted = PlagueVars.stateLock.withLock {
            if (!RoundEndRules.canStartSecondPhase(PlagueVars.state)) {
                false
            } else {
                PlagueVars.state = PlagueState.PlayingSecondPhase
                true
            }
        }

        if (!phaseStarted) return

        // Restore mono tree units weapons
        restoreUnitWeapons(UnitTypes.quad)
        restoreUnitWeapons(UnitTypes.oct)

        runOnMindustryThread {
            if (PlagueVars.restorePreSecondPhaseMonoTreeUnitsWeapons) {
                Groups.unit.forEach {
                    if (
                        it.type == UnitTypes.quad ||
                        it.type == UnitTypes.oct
                    ) {
                        it.setupWeapons(it.type)
                    }
                }
            }

            runBlocking {
                Team.all.filter { isValidSurvivorTeam(it) }.forEach {
                    Vars.state.rules.teams[it].blockDamageMultiplier *= 1.3f
                }

                updateAllPlayerSpecificRules()
            }
        }
    }

    private suspend fun onSuddenDeath() {
        val phaseStarted = PlagueVars.stateLock.withLock {
            if (!RoundEndRules.canStartTimeLimitEnd(PlagueVars.state)) {
                false
            } else {
                PlagueVars.state = PlagueState.SuddenDeath
                true
            }
        }

        if (!phaseStarted) return

        restartWithWinner(RoundEndReason.TIME_LIMIT) {
            runOnMindustryThreadSuspended {
                runBlocking {
                    updateAllPlayerSpecificRules()
                }

                val survivorTeams = Vars.state.teams.active.toList().filter { isValidSurvivorTeam(it.team) }

                when (RoundEndRules.winnerAtTimeLimit(survivorTeams.size)) {
                    RoundWinner.PLAGUE -> {
                        Call.infoMessage("[green]Plague team won the game. No Survivor team remained.")
                        Team.malis
                    }

                    RoundWinner.SURVIVORS -> {
                        val winnerTeam = runBlocking {
                            prepareSurvivorVictory(survivorTeams.map { it.team })
                                ?: error("Survivor winner disappeared during game-over resolution")
                        }
                        Call.infoMessage(
                            """
                            [green]Survivor teams won.
                            [green]${survivorTeams.joinToString(", ") { "'${it.team.name}'" }} survived the time limit.
                            [scarlet]Plague lost.
                            """.trimIndent()
                        )
                        winnerTeam
                    }
                }
            }
        }
    }

    @EventHandler(true)
    fun onDoubleTap(event: DoubleTap) {
        if (!isValidSurvivorTeam(event.player.team())) return

        if (event.tile.build !is StorageBuild) return

        if (event.tile.block().name != "vault") return

        if (event.tile.build.team != event.player.team()) return

        val vault = event.tile.build as StorageBuild

        if (vault.linkedCore != null) {
            event.tile.build.tile.setNet(Blocks.coreShard, event.tile.team(), 0)

            return
        }

        val enoughResources = PlagueVars.newCoreCost.all { vault.items.get(it.item) >= it.amount }

        if (!enoughResources)
            return event.player.sendMessage("[scarlet]Not enough resources to convert vault to core.")

        PlagueVars.newCoreCost.forEach {
            vault.items.remove(it)
        }

        val remainingItems = vault.items

        event.tile.build.tile.setNet(Blocks.coreShard, event.tile.team(), 0)

        // Refund remaining items in vault if it wasn't linked to core
        remainingItems.each { item, amount ->
            event.tile.team().items().add(item, amount)
        }
    }

    @EventHandler
    suspend fun onPlagueAttackerUnitDestroyed(event: EventType.UnitDestroyEvent) {
        activePlagueAttackerUnitsMutex.withLock {
            if (!activePlagueAttackerUnits.contains(event.unit)) return

            activePlagueAttackerUnits.remove((event.unit))
        }
    }

    @EventHandler(true)
    fun onMonoUnitDestroyed(event: EventType.UnitDestroyEvent) {
        if (event.unit.type != UnitTypes.mono) return

        if (event.unit.team.core() == null) return

        Call.label(
            "${Iconc.unitMono} Created",
            5f,
            event.unit.x,
            event.unit.y
        )

        PlagueVars.monoReward.forEach {
            val availableSpace = event.unit.team.core().storageCapacity - event.unit.team.items().get(it.item)

            event.unit.team.items().add(it.item, it.amount.coerceAtMost(availableSpace))
        }
    }

    @EventHandler(true)
    fun onMonoUnitCreate(event: EventType.UnitCreateEvent) {
        if (event.unit.type != UnitTypes.mono) return

        if (event.unit.team.core() == null) return

        // .kill() instantly kill the unit makes it weird because the unit just disappear
        event.unit.health = 0f
        event.unit.dead = true
    }

    @EventHandler(true)
    suspend fun onUnitCreate(event: EventType.UnitCreateEvent) {
        if (event.unit.team.core() == null) return

        if (event.unit.team == Team.malis) {
            if (PlagueBanned.getCurrentPlagueBannedUnits().contains(event.unit.type)) {
                Call.label(
                    "${event.unit.type.localizedName} is banned.",
                    5f,
                    event.spawner.x,
                    event.spawner.y
                )

                // .kill() instantly kill the unit makes it weird because the unit just disappear
                event.unit.health = 0f
                event.unit.dead = true
            }
        } else if (isValidSurvivorTeam(event.unit.team)) {
            if (PlagueBanned.getCurrentSurvivorsBannedUnits().contains(event.unit.type)) {
                Call.label(
                    "${event.unit.type.localizedName} is banned.",
                    5f,
                    event.spawner.x,
                    event.spawner.y
                )

                // .kill() instantly kill the unit makes it weird because the unit just disappear
                event.unit.health = 0f
                event.unit.dead = true
            }
        }
    }
}