package com.faultory.core.scenery

import com.faultory.core.content.TerrainCatalog
import com.faultory.core.shop.ScenerySpec
import com.faultory.core.shop.TileRect

/**
 * The terrain of every cell, as an index into [terrainIds] - the catalog's order - with [missing]
 * standing for any id the catalog does not define. Defined for every cell of the infinite grid, so
 * the scenery can be evaluated wherever the camera might look.
 *
 * Precedence, highest first: the last [ScenerySpec.regions] entry covering the cell, the floor
 * ([ScenerySpec.floorTerrain]), the first [ScenerySpec.patches] entry whose noise passes, and
 * finally [ScenerySpec.outsideTerrain].
 */
class TerrainMap(
    private val spec: ScenerySpec,
    private val floor: TileRect,
    terrains: TerrainCatalog
) {
    val terrainIds: List<String> = terrains.terrains.map { it.id }

    /** The index every unknown terrain id maps to; one past the catalog's last terrain. */
    val missing: Int = terrainIds.size

    /** Catalog terrains plus [missing]. */
    val terrainCount: Int = terrainIds.size + 1

    private val indexById: Map<String, Int> = buildMap {
        terrainIds.forEachIndexed { index, id -> putIfAbsent(id, index) }
    }
    private val floorIndex = indexOf(spec.floorTerrain)
    private val outsideIndex = indexOf(spec.outsideTerrain)
    private val regionIndexes = IntArray(spec.regions.size) { indexOf(spec.regions[it].terrain) }
    private val patchIndexes = IntArray(spec.patches.size) { indexOf(spec.patches[it].terrain) }

    fun indexOf(terrainId: String): Int = indexById[terrainId] ?: missing

    fun terrainAt(x: Int, y: Int): Int {
        for (index in spec.regions.indices.reversed()) {
            if (spec.regions[index].area.contains(x, y)) return regionIndexes[index]
        }
        if (floor.contains(x, y)) return floorIndex
        for (index in spec.patches.indices) {
            if (patchCovers(index, x, y)) return patchIndexes[index]
        }
        return outsideIndex
    }

    private fun patchCovers(index: Int, x: Int, y: Int): Boolean {
        val patch = spec.patches[index]
        if (patch.scale <= 0f) return false
        val sample = ValueNoise.sample(
            seed = spec.seed,
            salt = PATCH_SALT + index,
            x = (x + CELL_CENTRE) / patch.scale,
            y = (y + CELL_CENTRE) / patch.scale
        )
        return sample >= patch.threshold
    }

    private companion object {
        /** Patches start their salts here; each patch gets its own so their blobs do not coincide. */
        const val PATCH_SALT = 0x5041_0000
        const val CELL_CENTRE = 0.5
    }
}
