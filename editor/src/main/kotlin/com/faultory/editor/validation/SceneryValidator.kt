package com.faultory.editor.validation

import com.faultory.core.content.SceneryStyle
import com.faultory.core.content.TerrainDefinition
import com.faultory.core.content.TileDefinition
import com.faultory.core.content.WallDefinition
import com.faultory.core.content.WallSegment
import com.faultory.core.scenery.SceneryColor
import com.faultory.editor.graphics.RawArtProbe
import com.faultory.editor.model.SceneryCollection
import com.faultory.editor.model.SceneryKind
import com.faultory.editor.model.StyleCollection
import com.faultory.editor.model.TerrainCollection
import com.faultory.editor.model.TileCollection
import com.faultory.editor.model.WallCollection
import com.faultory.editor.repository.AssetRepository
import com.faultory.editor.ui.tree.AssetSelection

/** Validates whichever scenery entry is selected, through the checks for its kind. */
object SceneryValidator : Validator<AssetSelection.Scenery> {
    override fun validate(selection: AssetSelection.Scenery, context: ValidationContext): List<ValidationIssue> {
        val repository = context.repository
        val ids = selection.kind.collection.ids(repository)
        if (selection.id !in ids) return emptyList()
        val id = selection.id
        val kindIssues = when (selection.kind) {
            SceneryKind.TERRAIN -> issuesOf(TerrainCollection, id, repository, TerrainValidator::issues)
            SceneryKind.TILE -> issuesOf(TileCollection, id, repository, TileValidator::issues)
            SceneryKind.WALL -> issuesOf(WallCollection, id, repository, WallValidator::issues)
            SceneryKind.STYLE -> issuesOf(StyleCollection, id, repository, SceneryStyleValidator::issues)
        }
        return idIssues(selection.kind, id, ids) + kindIssues
    }

    private fun <T : Any> issuesOf(
        collection: SceneryCollection<T>,
        id: String,
        repository: AssetRepository,
        check: (T, AssetRepository) -> List<ValidationIssue>,
    ): List<ValidationIssue> = collection.find(repository, id)?.let { check(it, repository) }.orEmpty()

    private fun idIssues(kind: SceneryKind, id: String, ids: List<String>): List<ValidationIssue> = buildList {
        if (id.isBlank()) add(ValidationIssue(Severity.ERROR, "${kind.label} id must not be blank", fieldName = "id"))
        if (id.isNotBlank() && ids.count { it == id } > 1) {
            add(ValidationIssue(Severity.ERROR, "Duplicate ${kind.label} id '$id'", fieldName = "id"))
        }
    }
}

object TerrainValidator {
    fun issues(terrain: TerrainDefinition, repository: AssetRepository): List<ValidationIssue> = buildList {
        if (SceneryColor.parseOrNull(terrain.fallbackColor) == null) {
            add(
                ValidationIssue(
                    Severity.ERROR,
                    "fallbackColor '${terrain.fallbackColor}' must be #RRGGBB or #RRGGBBAA",
                    fieldName = "fallbackColor",
                )
            )
        }
        val hasFullTile = repository.tileCatalog.tiles.any { it.corners.isFull && it.corners.northWest == terrain.id }
        if (!hasFullTile) {
            add(
                ValidationIssue(
                    Severity.WARNING,
                    "No full tile (all four corners '${terrain.id}') yet: its ground shows the fallback colour",
                )
            )
        }
    }
}

object TileValidator {
    private const val TILE_PIXELS = 40

    fun issues(tile: TileDefinition, repository: AssetRepository): List<ValidationIssue> = buildList {
        val terrains = repository.terrainCatalog.terrains.map { it.id }.toSet()
        listOf(
            "northWest" to tile.corners.northWest,
            "northEast" to tile.corners.northEast,
            "southEast" to tile.corners.southEast,
            "southWest" to tile.corners.southWest,
        ).forEach { (corner, terrain) ->
            if (terrain !in terrains) {
                val message = "Corner '$terrain' is not a terrain"
                add(ValidationIssue(Severity.ERROR, message, fieldName = "corners.$corner"))
            }
        }
        addAll(PieceChecks.weightIssues(tile.weight))
        addAll(PieceChecks.skinIssues(tile.skin))
        val size = tile.skin.takeIf(String::isNotBlank)?.let { RawArtProbe.idleFrameSize(repository.rootPath, it) }
        when {
            tile.skin.isBlank() -> Unit
            size == null -> add(PieceChecks.noArtYet())
            size != (TILE_PIXELS to TILE_PIXELS) -> add(
                ValidationIssue(
                    Severity.WARNING,
                    "Art is ${size.first}x${size.second}; ground tiles are stretched to ${TILE_PIXELS}x$TILE_PIXELS",
                    fieldName = "skin",
                )
            )
        }
    }
}

object WallValidator {
    private const val TILE_PIXELS = 40

    fun issues(wall: WallDefinition, repository: AssetRepository): List<ValidationIssue> = buildList {
        addAll(PieceChecks.weightIssues(wall.weight))
        addAll(PieceChecks.skinIssues(wall.skin))
        val size = wall.skin.takeIf(String::isNotBlank)?.let { RawArtProbe.idleFrameSize(repository.rootPath, it) }
        if (wall.skin.isNotBlank() && size == null) add(PieceChecks.noArtYet())
        if (size != null) floorEdgeIssue(wall.segment, size)?.let(::add)
        val authored = repository.wallCatalog.walls.map { it.segment }.toSet()
        val missing = WallSegment.entries.filter { it !in authored }
        if (missing.isNotEmpty()) {
            add(
                ValidationIssue(
                    Severity.INFO,
                    "No wall pieces yet for ${missing.joinToString()}; those cells use the fallbacks",
                )
            )
        }
    }

    /** A side piece must span exactly one tile along the floor's edge, or the wall will not meet up. */
    private fun floorEdgeIssue(segment: WallSegment, size: Pair<Int, Int>): ValidationIssue? {
        val (width, height) = size
        val alongEdge = when {
            segment.towardFloorX == 0 -> width
            segment.towardFloorY == 0 -> height
            else -> return null
        }
        if (alongEdge == TILE_PIXELS) return null
        return ValidationIssue(
            Severity.WARNING,
            "Art is ${width}x$height; a $segment piece should be $TILE_PIXELS px along the floor's edge",
            fieldName = "skin",
        )
    }
}

object SceneryStyleValidator {
    fun issues(style: SceneryStyle, repository: AssetRepository): List<ValidationIssue> =
        TagWeightChecks.issues(style.tagWeights, "tagWeights", repository)
}

/** Checks shared by everything that carries tag weights: a scenery style and a blueprint's own. */
object TagWeightChecks {
    fun issues(
        tagWeights: List<com.faultory.core.content.TagWeight>,
        field: String,
        repository: AssetRepository,
    ): List<ValidationIssue> = buildList {
        val carried = repository.tileCatalog.tiles.flatMap { it.tags }.toSet() +
            repository.wallCatalog.walls.flatMap { it.tags }
        tagWeights.forEachIndexed { index, tagWeight ->
            if (tagWeight.tag.isBlank()) {
                add(ValidationIssue(Severity.ERROR, "Tag must not be blank", fieldName = "$field[$index].tag"))
            } else if (tagWeight.tag !in carried) {
                add(
                    ValidationIssue(
                        Severity.WARNING,
                        "No tile or wall piece carries tag '${tagWeight.tag}' - a typo?",
                        fieldName = "$field[$index].tag",
                    )
                )
            }
            if (tagWeight.multiplier < 0f) {
                add(
                    ValidationIssue(
                        Severity.ERROR,
                        "Multiplier must not be negative (0 excludes)",
                        fieldName = "$field[$index].multiplier",
                    )
                )
            }
        }
    }
}

private object PieceChecks {
    fun weightIssues(weight: Float): List<ValidationIssue> = when {
        weight < 0f -> listOf(ValidationIssue(Severity.ERROR, "Weight must not be negative", fieldName = "weight"))
        weight == 0f -> listOf(
            ValidationIssue(Severity.WARNING, "Weight 0: never placed unless pinned", fieldName = "weight")
        )
        else -> emptyList()
    }

    fun skinIssues(skin: String): List<ValidationIssue> =
        if (skin.isBlank()) {
            listOf(ValidationIssue(Severity.ERROR, "Skin must not be blank", fieldName = "skin"))
        } else {
            emptyList()
        }

    fun noArtYet() = ValidationIssue(
        Severity.WARNING,
        "No idle art facing south uploaded yet; the game draws the fallback here",
        fieldName = "skin",
    )
}
