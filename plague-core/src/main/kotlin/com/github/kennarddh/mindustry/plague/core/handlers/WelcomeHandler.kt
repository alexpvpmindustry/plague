package com.github.kennarddh.mindustry.plague.core.handlers

import com.github.kennarddh.mindustry.genesis.core.events.annotations.EventHandler
import com.github.kennarddh.mindustry.genesis.core.handlers.Handler
import com.github.kennarddh.mindustry.genesis.standard.extensions.infoMessage
import mindustry.game.EventType
import mindustry.net.Administration.Config

class WelcomeHandler : Handler {
    override suspend fun onInit() {
        Config.motd.set("Welcome to Plague server.")
    }

    @EventHandler
    fun onPlayerJoin(event: EventType.PlayerJoin) {
        event.player.infoMessage(
            """
            [maroon]=== [gold]QUICK START[] ===[]
            This is a game of [forest]Plague[](default) vs [yellow]Survivor[]. Last one standing.
            [maroon]=== [gold]GAME STAGES[] ===[]
            [royal]Prepare state (2min)[]: The timer starts when the first player joins. Place any block at least 100 tiles from the nearest plague core to become [yellow]Survivor[].
            [royal]First Phase (45min)[]: [forest]Plague[] make units, [yellow]Survivor[] make defense. Air units don't do damage.
            [royal]Second Phase (15min)[]: Air units do damage.
            [royal]Sudden Death[]: [yellow]Survivors[] won, but [forest]Plague[] multiplies in strength. [yellow]Survive[] as long as possible.
            
            Full Rules in discord. Use [accent]/discord[] command.
            """.trimIndent()
        )
    }
}