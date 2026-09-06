package com.github.kennarddh.mindustry.plague.core.commons

data class MapPoint(val x: Int, val y: Int)

object CornerSpawnRules {
    fun cornerAnchors(width: Int, height: Int, margin: Int): List<MapPoint> {
        require(width > margin * 2)
        require(height > margin * 2)

        val maxX = width - 1 - margin
        val maxY = height - 1 - margin

        return listOf(
            MapPoint(margin, margin),
            MapPoint(maxX, margin),
            MapPoint(maxX, maxY),
            MapPoint(margin, maxY),
        )
    }

    fun boundedCandidates(
        anchors: List<MapPoint>,
        width: Int,
        height: Int,
        maximumRadius: Int,
        stride: Int,
        maximumCandidates: Int,
    ): List<MapPoint> {
        require(width > 0 && height > 0)
        require(maximumRadius >= 0)
        require(stride > 0)
        require(maximumCandidates > 0)

        val candidates = LinkedHashSet<MapPoint>(maximumCandidates)

        fun add(x: Int, y: Int): Boolean {
            if (x in 0 until width && y in 0 until height) {
                candidates.add(MapPoint(x, y))
            }
            return candidates.size >= maximumCandidates
        }

        for (radius in 0..maximumRadius step stride) {
            for (anchor in anchors) {
                for (offset in -radius..radius step stride) {
                    if (add(anchor.x + offset, anchor.y - radius)) return candidates.toList()
                    if (add(anchor.x + offset, anchor.y + radius)) return candidates.toList()
                    if (add(anchor.x - radius, anchor.y + offset)) return candidates.toList()
                    if (add(anchor.x + radius, anchor.y + offset)) return candidates.toList()
                }
            }
        }

        return candidates.toList()
    }
}
