package com.faultory.core.scenery

import com.faultory.core.content.SceneryStyleCatalog
import com.faultory.core.content.TerrainCatalog
import com.faultory.core.content.TileCatalog
import com.faultory.core.content.WallCatalog

/** The four catalogs scenery is laid out from, travelling together. */
data class SceneryContent(
    val terrains: TerrainCatalog = TerrainCatalog(),
    val tiles: TileCatalog = TileCatalog(),
    val walls: WallCatalog = WallCatalog(),
    val styles: SceneryStyleCatalog = SceneryStyleCatalog()
)

/** Colours scenery falls back to where neither art nor an authored colour exists. */
object SceneryPalette {
    /** A wall cell with no piece to draw. */
    const val WALL: Int = 0x4A4F57FF

    /** A terrain id nothing in the catalog defines - deliberately loud, so it is noticed. */
    val MISSING_TERRAIN: Int = 0xFF00FFFF.toInt()
}
