package com.faultory.core.shop

import com.faultory.core.content.TagWeight
import kotlinx.serialization.Serializable

/**
 * How a shop's scenery - the ground in and around it and the walls that ring its floor - is laid
 * out. Visual only: nothing here touches the simulation, and `core.scenery` turns it into the same
 * picture on every run.
 *
 * A cell's terrain is the last [regions] entry covering it, else [floorTerrain] on the floor, else
 * the first of [patches] whose noise passes there, else [outsideTerrain]. [styleId] names a
 * [com.faultory.core.content.SceneryStyle] and [tagWeights] reweight pieces on top of it. Pins
 * place an exact piece and always win. [seed] re-rolls which variant lands where without changing
 * the layout's rules.
 */
@Serializable
data class ScenerySpec(
    val floorTerrain: String = "",
    val outsideTerrain: String = "",
    val styleId: String? = null,
    val tagWeights: List<TagWeight> = emptyList(),
    val regions: List<TerrainRegion> = emptyList(),
    val patches: List<TerrainPatch> = emptyList(),
    val tilePins: List<TilePin> = emptyList(),
    val wallPins: List<WallPin> = emptyList(),
    val seed: Long = 0L
)

/** Paints [terrain] over every cell of [area], floor or not. */
@Serializable
data class TerrainRegion(
    val area: TileRect,
    val terrain: String
)

/**
 * Scatters [terrain] over the cells outside the floor in organic blobs: smooth value noise whose
 * features are about [scale] tiles across, kept wherever it reaches [threshold] (0..1 - higher
 * means rarer, smaller patches).
 */
@Serializable
data class TerrainPatch(
    val terrain: String,
    val scale: Float = 6f,
    val threshold: Float = 0.6f
)

/**
 * Places ground tile [tileId] at the dual-grid vertex ([x], [y]) - the point where the corners of
 * cells (x-1, y-1), (x, y-1), (x-1, y) and (x, y) meet - whatever the terrains there call for.
 */
@Serializable
data class TilePin(
    val x: Int,
    val y: Int,
    val tileId: String
)

/** Places wall piece [wallId] on the wall cell ([x], [y]) - one of the ring just outside the floor. */
@Serializable
data class WallPin(
    val x: Int,
    val y: Int,
    val wallId: String
)
