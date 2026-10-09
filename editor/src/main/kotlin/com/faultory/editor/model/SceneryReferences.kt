package com.faultory.editor.model

import com.faultory.core.content.TileCorners
import com.faultory.core.shop.ScenerySpec
import com.faultory.editor.repository.AssetRepository
import com.faultory.editor.ui.tree.AssetSelection

/**
 * Everything that points at a scenery entry, and how a rename or delete of one cascades: terrains
 * are named by tile corners and by every blueprint's terrain fields, tiles and wall pieces by
 * blueprint pins, styles by a blueprint's `styleId`. [IdRenamer], [IdDeleter] and
 * [ReferenceIndex] all go through here, so the three can never disagree about what a reference is.
 */
class SceneryReferences(private val repository: AssetRepository) {

    fun referencesTo(kind: SceneryKind, id: String): List<Reference> = when (kind) {
        SceneryKind.TERRAIN -> tileCornerReferences(id) + blueprintReferences(id) { spec, terrain ->
            buildList {
                if (spec.floorTerrain == terrain) add("scenery.floorTerrain")
                if (spec.outsideTerrain == terrain) add("scenery.outsideTerrain")
                spec.regions.forEachIndexed { index, region ->
                    if (region.terrain == terrain) add("scenery.regions[$index].terrain")
                }
                spec.patches.forEachIndexed { index, patch ->
                    if (patch.terrain == terrain) add("scenery.patches[$index].terrain")
                }
            }
        }
        SceneryKind.TILE -> blueprintReferences(id) { spec, tile ->
            spec.tilePins.indices.filter { spec.tilePins[it].tileId == tile }.map { "scenery.tilePins[$it]" }
        }
        SceneryKind.WALL -> blueprintReferences(id) { spec, wall ->
            spec.wallPins.indices.filter { spec.wallPins[it].wallId == wall }.map { "scenery.wallPins[$it]" }
        }
        SceneryKind.STYLE -> blueprintReferences(id) { spec, style ->
            if (spec.styleId == style) listOf("scenery.styleId") else emptyList()
        }
    }

    /** Rewrites every reference to [oldId] so it names [newId] instead. */
    fun rename(kind: SceneryKind, oldId: String, newId: String) {
        val swap = { value: String -> if (value == oldId) newId else value }
        when (kind) {
            SceneryKind.TERRAIN -> {
                rewriteTileCorners(swap)
                rewriteBlueprints { spec ->
                    spec.copy(
                        floorTerrain = swap(spec.floorTerrain),
                        outsideTerrain = swap(spec.outsideTerrain),
                        regions = spec.regions.map { it.copy(terrain = swap(it.terrain)) },
                        patches = spec.patches.map { it.copy(terrain = swap(it.terrain)) }
                    )
                }
            }
            SceneryKind.TILE -> rewriteBlueprints { spec ->
                spec.copy(tilePins = spec.tilePins.map { it.copy(tileId = swap(it.tileId)) })
            }
            SceneryKind.WALL -> rewriteBlueprints { spec ->
                spec.copy(wallPins = spec.wallPins.map { it.copy(wallId = swap(it.wallId)) })
            }
            SceneryKind.STYLE -> rewriteBlueprints { spec -> spec.copy(styleId = spec.styleId?.let(swap)) }
        }
    }

    /**
     * Removes every reference to [id] that can go - the regions and patches a deleted terrain
     * painted, the pins of a deleted tile or wall piece - and blanks the rest (a blueprint's floor
     * and outside terrain, tile corners, a style id), which the validators then flag for a new
     * choice rather than the editor guessing one.
     */
    fun delete(kind: SceneryKind, id: String) {
        when (kind) {
            SceneryKind.TERRAIN -> {
                rewriteTileCorners { if (it == id) "" else it }
                rewriteBlueprints { spec ->
                    spec.copy(
                        floorTerrain = spec.floorTerrain.takeUnless { it == id }.orEmpty(),
                        outsideTerrain = spec.outsideTerrain.takeUnless { it == id }.orEmpty(),
                        regions = spec.regions.filterNot { it.terrain == id },
                        patches = spec.patches.filterNot { it.terrain == id }
                    )
                }
            }
            SceneryKind.TILE -> rewriteBlueprints { spec ->
                spec.copy(tilePins = spec.tilePins.filterNot { it.tileId == id })
            }
            SceneryKind.WALL -> rewriteBlueprints { spec ->
                spec.copy(wallPins = spec.wallPins.filterNot { it.wallId == id })
            }
            SceneryKind.STYLE -> rewriteBlueprints { spec -> spec.copy(styleId = spec.styleId.takeUnless { it == id }) }
        }
    }

    private fun tileCornerReferences(terrain: String): List<Reference> =
        repository.tileCatalog.tiles.filter { terrain in it.corners.terrainIds() }.map { tile ->
            Reference(
                source = AssetSelection.Scenery(SceneryKind.TILE, tile.id),
                field = "corners",
                description = "Tile '${tile.id}' has a corner of this terrain",
            )
        }

    private fun blueprintReferences(id: String, fieldsOf: (ScenerySpec, String) -> List<String>): List<Reference> =
        repository.blueprints.flatMap { (path, blueprint) ->
            fieldsOf(blueprint.scenery, id).map { field ->
                Reference(
                    source = AssetSelection.Blueprint(path),
                    field = field,
                    description = "Blueprint '${blueprint.id}' uses it in $field",
                )
            }
        }

    private fun rewriteTileCorners(rewrite: (String) -> String) {
        val tiles = repository.tileCatalog.tiles.map { tile ->
            val corners = with(tile.corners) {
                TileCorners(rewrite(northWest), rewrite(northEast), rewrite(southEast), rewrite(southWest))
            }
            if (corners == tile.corners) tile else tile.copy(corners = corners)
        }
        repository.tileCatalog = repository.tileCatalog.copy(tiles = tiles)
    }

    private fun rewriteBlueprints(rewrite: (ScenerySpec) -> ScenerySpec) {
        for (path in repository.blueprints.keys.toList()) {
            val blueprint = repository.blueprints[path] ?: continue
            val scenery = rewrite(blueprint.scenery)
            if (scenery != blueprint.scenery) {
                repository.blueprints[path] = blueprint.copy(scenery = scenery)
            }
        }
    }
}
