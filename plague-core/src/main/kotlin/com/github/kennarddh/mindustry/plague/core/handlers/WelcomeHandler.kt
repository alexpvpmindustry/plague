package com.github.kennarddh.mindustry.plague.core.handlers

import com.github.kennarddh.mindustry.genesis.core.events.annotations.EventHandler
import com.github.kennarddh.mindustry.genesis.core.handlers.Handler
import mindustry.game.EventType
import mindustry.net.Administration.Config

class WelcomeHandler : Handler {
    override suspend fun onInit() {
        Config.motd.set("Welcome to Plague server.")
    }

    @EventHandler
    fun onPlayerJoin(event: EventType.PlayerJoin) {
        event.player.sendMessage(
            """
            [gold]PLAGUE[]
            [yellow]Survivor[]: Build defenses and protect your core.
            [green]Plague[]: Build units and conquer the Survivors.
            Choose in the popup. Backup: [gold]/survivor[] or [gold]/plague[]. Full rules: [accent]/discord[].
            """.trimIndent()
        )
    }
}