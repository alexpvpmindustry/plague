package com.github.kennarddh.mindustry.plague.core.commons

object SurvivorStartRules {
    fun isFarEnoughFromPlague(distanceWorldUnits: Float, tileSize: Int, minimumTiles: Int): Boolean {
        return distanceWorldUnits >= minimumTiles * tileSize
    }
}
