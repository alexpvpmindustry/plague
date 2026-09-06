package com.github.kennarddh.mindustry.plague.core.handlers

import com.github.kennarddh.mindustry.genesis.core.Genesis
import com.github.kennarddh.mindustry.genesis.core.commands.CommandSide
import com.github.kennarddh.mindustry.genesis.core.commands.annotations.Command
import com.github.kennarddh.mindustry.genesis.core.commands.annotations.Description
import com.github.kennarddh.mindustry.genesis.core.commands.senders.CommandSender
import com.github.kennarddh.mindustry.genesis.core.commands.senders.PlayerCommandSender
import com.github.kennarddh.mindustry.genesis.core.commons.runOnMindustryThread
import com.github.kennarddh.mindustry.genesis.core.commons.runOnMindustryThreadSuspended
import com.github.kennarddh.mindustry.genesis.core.handlers.Handler
import com.github.kennarddh.mindustry.genesis.standard.commands.parameters.validations.numbers.GTE
import com.github.kennarddh.mindustry.genesis.standard.commands.parameters.validations.numbers.LTE
import com.github.kennarddh.mindustry.plague.core.commands.validations.Admin
import com.github.kennarddh.mindustry.plague.core.commons.PlagueVars
import com.github.kennarddh.mindustry.plague.core.commons.extensions.toDisplayString
import kotlinx.coroutines.runBlocking
import mindustry.Vars
import mindustry.game.Team
import mindustry.gen.Player
import mindustry.type.Item
import mindustry.type.UnitType
import kotlin.math.floor
import kotlin.time.Duration

class CheatHandler : Handler {
    override suspend fun onInit() {
        Genesis.commandRegistry.removeCommand("gameover", CommandSide.Server)
        Genesis.commandRegistry.removeCommand("fillitems", CommandSide.Server)
    }

    @Command(["skiptime"])
    @Admin
    @Description("Skip map time. Only for admin.")
    fun skipTime(sender: CommandSender, duration: Duration) {
        PlagueVars.totalMapSkipDuration += duration

        sender.sendSuccess("Skipped '${duration.toDisplayString()}'. Current map time is '${PlagueVars.mapTime.toDisplayString()}'.")
    }

    @Command(["spawnunit"])
    @Admin
    @Description("Spawn unit. Only for admin.")
    fun spawnUnit(
        sender: PlayerCommandSender,
        unitType: UnitType,
        @GTE(1) @LTE(20) count: Int = 1,
        team: Team = sender.player.team(),
    ) {
        runOnMindustryThread {
            var validSpawnedUnits = 0

            repeat(count) {
                val unit = unitType.spawn(sender.player, team)

                if (unit.isValid)
                    validSpawnedUnits += 1
            }

            sender.sendSuccess(
                "Spawned $validSpawnedUnits '${unitType.name}' at (${floor(sender.player.x / Vars.tilesize).toInt()}, ${
                    floor(sender.player.y / Vars.tilesize).toInt()
                })"
            )
        }
    }

    @Command(["gameover"])
    @Admin
    @Description("Restart game. Only for admin.")
    suspend fun gameOver(sender: CommandSender, winner: Team = Team.derelict) {
        Genesis.getHandler<PlagueHandler>()?.restart(winner)
    }

    @Command(["sandbox"])
    @Admin
    @Description("Toggle sandbox. Only for admin.")
    suspend fun sandbox(sender: CommandSender) {
        runOnMindustryThreadSuspended {
            Vars.state.rules.infiniteResources = !Vars.state.rules.infiniteResources
        }

        Genesis.getHandler<PlagueHandler>()?.updateAllPlayerSpecificRules()

        sender.sendSuccess("Sandbox is now ${if (Vars.state.rules.infiniteResources) "enabled" else "disabled"}.")
    }

    @Command(["fillitems"])
    @Admin
    @Description("Fill the core with items. Only for admin.")
    fun fillItems(
        sender: CommandSender,
        team: Team = if (sender is PlayerCommandSender) sender.player.team() else Vars.state.map.rules().defaultTeam,
    ) {
        runOnMindustryThread {
            if (Vars.state.teams.cores(team).isEmpty)
                return@runOnMindustryThread sender.sendError("Team '${team.name}' doesn't have any cores.")

            Vars.content.items().forEach {
                team.items().set(it, Vars.state.teams.cores(team).first().storageCapacity)
            }

            sender.sendSuccess("Team '${team.name}' core filled.")
        }
    }

    @Command(["additem"])
    @Admin
    @Description("Add an item to a team. Only for admin.")
    fun addItem(
        sender: CommandSender,
        item: Item,
        @GTE(0) amount: Int,
        team: Team = if (sender is PlayerCommandSender) sender.player.team() else Vars.state.map.rules().defaultTeam,
    ) {
        runOnMindustryThread {
            if (Vars.state.teams.cores(team).isEmpty)
                return@runOnMindustryThread sender.sendError("Team '${team.name}' doesn't have any cores.")

            val addedAmount =
                amount.coerceAtMost(Vars.state.teams.cores(team).first().storageCapacity - team.items().get(item))

            team.items().add(item, addedAmount)

            sender.sendSuccess("Added $addedAmount ${item.name} to team '${team.name}' core.")
        }
    }

    @Command(["removeitem"])
    @Admin
    @Description("Remove an item to a team. Only for admin.")
    fun removeItem(
        sender: CommandSender,
        item: Item,
        @GTE(0) amount: Int,
        team: Team = if (sender is PlayerCommandSender) sender.player.team() else Vars.state.map.rules().defaultTeam,
    ) {
        runOnMindustryThread {
            if (Vars.state.teams.cores(team).isEmpty)
                return@runOnMindustryThread sender.sendError("Team '${team.name}' doesn't have any cores.")

            val removedAmount = amount.coerceAtMost(team.items().get(item))

            team.items().remove(item, removedAmount)

            sender.sendSuccess("Removed $removedAmount ${item.name} from team '${team.name}' core.")
        }
    }

    @Command(["changeteam"])
    @Admin
    @Description("Change team. Only for admin.")
    fun changeTeam(
        sender: CommandSender,
        team: Team = if (sender is PlayerCommandSender) sender.player.team() else Vars.state.map.rules().defaultTeam,
        player: Player? = if (sender is PlayerCommandSender) sender.player else null,
    ) {
        if (player == null) return sender.sendError("Player cannot be null.")
        
        runOnMindustryThread {
            runBlocking {
                val plagueHandler = Genesis.getHandler<PlagueHandler>()
                    ?: return@runBlocking sender.sendError("Plague handler is unavailable.")
                plagueHandler.changePlayerTeam(player, team)

                sender.sendSuccess("Changed '${player.plainName()}' team to '${team.name}'.")
            }
        }
    }
}