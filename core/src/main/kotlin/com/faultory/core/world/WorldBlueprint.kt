package com.faultory.core.world

import kotlinx.serialization.Serializable

@Serializable
data class WorldBlueprint(
    val id: String,
    val displayName: String,
    val pathNodes: List<PathNode>,
    val towerPads: List<TowerPad>
)

@Serializable
data class PathNode(
    val x: Float,
    val y: Float
)

@Serializable
data class TowerPad(
    val id: String,
    val x: Float,
    val y: Float,
    val size: Float = 28f
)
