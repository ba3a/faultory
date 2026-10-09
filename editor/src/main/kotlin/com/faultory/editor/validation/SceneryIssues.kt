package com.faultory.editor.validation

import com.faultory.core.content.WallSegment
import com.faultory.core.scenery.StyleWeights
import com.faultory.core.scenery.TerrainMap
import com.faultory.core.scenery.WallRing
import com.faultory.core.screens.shopfloor.CameraBounds
import com.faultory.core.screens.shopfloor.CameraTuning
import com.faultory.core.shop.ScenerySpec
import com.faultory.core.shop.ShopBlueprint
import com.faultory.core.shop.ShopGrid
import com.faultory.core.shop.TileCoordinate
import com.faultory.editor.repository.AssetRepository

/**
 * A blueprint's `scenery` block, checked against the scenery catalogs: every name resolves, the
 * noise patches make sense, and the shop's style does not quietly strip a terrain or a wall segment
 * of every piece the catalog has for it. A dangling name never crashes the game - the scenery
 * degrades to its fallbacks - which is exactly why the editor has to catch it.
 */
object SceneryIssues {
    fun of(blueprint: ShopBlueprint, repository: AssetRepository): List<ValidationIssue> {
        val spec = blueprint.scenery
        return referenceIssues(spec, repository) +
            patchIssues(spec) +
            TagWeightChecks.issues(spec.tagWeights, "scenery.tagWeights", repository) +
            styleCoverageIssues(spec, repository) +
            tilePinIssues(blueprint, repository) +
            wallPinIssues(blueprint, repository)
    }

    /**
     * A pinned tile is drawn whatever the terrains around it are - that is what a pin is for - so a
     * mismatch is only a warning: it is usually a terrain painted after the pin went down.
     */
    private fun tilePinIssues(blueprint: ShopBlueprint, repository: AssetRepository): List<ValidationIssue> {
        val map = TerrainMap(blueprint.scenery, blueprint.floor, repository.terrainCatalog)
        val extent = CameraBounds.of(blueprint.camera).sceneryExtent(CameraTuning.ZOOM_LADDER)
        val tiles = repository.tileCatalog.tiles.associateBy { it.id }
        return blueprint.scenery.tilePins.mapIndexedNotNull { index, pin ->
            val tile = tiles[pin.tileId] ?: return@mapIndexedNotNull null
            val field = "scenery.tilePins[$index]"
            if (!extent.expandedBy(1).contains(pin.x, pin.y)) {
                return@mapIndexedNotNull ValidationIssue(Severity.WARNING, "Pin is outside the scenery", field)
            }
            val around = listOf(
                map.terrainAt(pin.x - 1, pin.y),
                map.terrainAt(pin.x, pin.y),
                map.terrainAt(pin.x, pin.y - 1),
                map.terrainAt(pin.x - 1, pin.y - 1)
            ).map { map.terrainIds.getOrElse(it) { "?" } }
            if (around == tile.corners.terrainIds()) return@mapIndexedNotNull null
            ValidationIssue(
                Severity.WARNING,
                "Pinned tile '${tile.id}' has corners ${tile.corners.terrainIds()} " +
                    "but the cells around (${pin.x}, ${pin.y}) are $around",
                field,
            )
        }
    }

    /** A wall pin must sit on the ring, and a piece made for another segment will look wrong there. */
    private fun wallPinIssues(blueprint: ShopBlueprint, repository: AssetRepository): List<ValidationIssue> {
        val floor = blueprint.floor
        val openings = WallRing.openingsThrough(floor, ShopGrid(blueprint).orderedBeltPaths)
        val walls = repository.wallCatalog.walls.associateBy { it.id }
        return blueprint.scenery.wallPins.mapIndexedNotNull { index, pin ->
            val wall = walls[pin.wallId] ?: return@mapIndexedNotNull null
            val field = "scenery.wallPins[$index]"
            val cellSegment = WallRing.roleAt(floor, openings, TileCoordinate(pin.x, pin.y))
                ?: return@mapIndexedNotNull ValidationIssue(
                    Severity.WARNING,
                    "Cell (${pin.x}, ${pin.y}) is not on the wall ring; the pin is ignored",
                    field,
                )
            if (wall.segment == cellSegment) return@mapIndexedNotNull null
            ValidationIssue(
                Severity.WARNING,
                "Pinned ${wall.segment} piece '${wall.id}' sits on a $cellSegment cell",
                field,
            )
        }
    }

    private fun referenceIssues(spec: ScenerySpec, repository: AssetRepository): List<ValidationIssue> = buildList {
        val terrains = repository.terrainCatalog.terrains.map { it.id }.toSet()
        fun error(message: String, field: String) = add(ValidationIssue(Severity.ERROR, message, fieldName = field))
        fun requireTerrain(terrain: String, field: String) {
            if (terrain !in terrains) error("'$terrain' is not a terrain", field)
        }
        requireTerrain(spec.floorTerrain, "scenery.floorTerrain")
        requireTerrain(spec.outsideTerrain, "scenery.outsideTerrain")
        spec.regions.forEachIndexed { index, region ->
            requireTerrain(region.terrain, "scenery.regions[$index].terrain")
            if (region.area.isEmpty) {
                val field = "scenery.regions[$index].area"
                add(ValidationIssue(Severity.WARNING, "Region covers no cells", fieldName = field))
            }
        }
        spec.patches.forEachIndexed { index, patch -> requireTerrain(patch.terrain, "scenery.patches[$index].terrain") }
        spec.styleId?.let { styleId ->
            if (repository.sceneryStyleCatalog.styles.none { it.id == styleId }) {
                error("'$styleId' is not a scenery style", "scenery.styleId")
            }
        }
        val tiles = repository.tileCatalog.tiles.map { it.id }.toSet()
        spec.tilePins.forEachIndexed { index, pin ->
            if (pin.tileId !in tiles) {
                error("'${pin.tileId}' is not a tile", "scenery.tilePins[$index]")
            }
        }
        val walls = repository.wallCatalog.walls.map { it.id }.toSet()
        spec.wallPins.forEachIndexed { index, pin ->
            if (pin.wallId !in walls) {
                error("'${pin.wallId}' is not a wall piece", "scenery.wallPins[$index]")
            }
        }
    }

    private fun patchIssues(spec: ScenerySpec): List<ValidationIssue> = buildList {
        spec.patches.forEachIndexed { index, patch ->
            if (patch.scale <= 0f) {
                val field = "scenery.patches[$index].scale"
                add(ValidationIssue(Severity.ERROR, "Scale must be above 0", fieldName = field))
            }
            if (patch.threshold !in 0f..1f) {
                add(
                    ValidationIssue(
                        Severity.ERROR,
                        "Threshold must be within 0..1",
                        fieldName = "scenery.patches[$index].threshold",
                    )
                )
            }
        }
    }

    /**
     * Only what the style takes away: a terrain or segment the catalog has no pieces for at all is
     * reported on the terrain or wall itself, not on every blueprint.
     */
    private fun styleCoverageIssues(spec: ScenerySpec, repository: AssetRepository): List<ValidationIssue> {
        val weights = StyleWeights.forSpec(spec, repository.sceneryStyleCatalog)
        val styleName = spec.styleId?.let { "style '$it'" } ?: "this shop's tag weights"
        val usedTerrains = (listOf(spec.floorTerrain, spec.outsideTerrain) +
            spec.regions.map { it.terrain } + spec.patches.map { it.terrain }).distinct()
        val terrainIssues = usedTerrains.mapNotNull { terrain ->
            val fullTiles = repository.tileCatalog.tiles.filter { it.corners.isFull && it.corners.northWest == terrain }
            val stripped = fullTiles.isNotEmpty() && fullTiles.none { weights.weightOf(it.weight, it.tags) > 0f }
            if (!stripped) return@mapNotNull null
            ValidationIssue(Severity.WARNING, "$styleName leaves terrain '$terrain' without a full tile", "scenery")
        }
        val wallIssues = WallSegment.entries.mapNotNull { segment ->
            val pieces = repository.wallCatalog.walls.filter { it.segment == segment }
            val stripped = pieces.isNotEmpty() && pieces.none { weights.weightOf(it.weight, it.tags) > 0f }
            if (!stripped) return@mapNotNull null
            ValidationIssue(Severity.WARNING, "$styleName leaves no piece for $segment walls", "scenery")
        }
        return terrainIssues + wallIssues
    }
}
