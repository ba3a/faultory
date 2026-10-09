package com.faultory.editor.model

import com.faultory.core.content.SceneryStyle
import com.faultory.core.content.TerrainDefinition
import com.faultory.core.content.TileCorners
import com.faultory.core.content.TileDefinition
import com.faultory.core.content.WallDefinition
import com.faultory.core.content.WallSegment
import com.faultory.editor.repository.AssetRepository
import kotlinx.serialization.KSerializer

/** The four scenery catalogs, each a flat list of entries identified by id. */
enum class SceneryKind(val label: String, val pluralLabel: String) {
    TERRAIN("terrain", "Terrains"),
    TILE("tile", "Tiles"),
    WALL("wall", "Walls"),
    STYLE("style", "Styles");

    val collection: SceneryCollection<*>
        get() = when (this) {
            TERRAIN -> TerrainCollection
            TILE -> TileCollection
            WALL -> WallCollection
            STYLE -> StyleCollection
        }
}

/**
 * One scenery catalog seen as what the editor's per-asset machinery needs - list, find, replace,
 * add, remove, re-id, and a blank entry to create - so the tree, the inspector, duplication and
 * creation handle all four catalogs through one code path instead of four copies of it.
 */
abstract class SceneryCollection<T : Any>(
    val kind: SceneryKind,
    val serializer: KSerializer<T>
) {
    abstract fun entries(repository: AssetRepository): List<T>

    protected abstract fun store(repository: AssetRepository, entries: List<T>)

    abstract fun idOf(entry: T): String

    abstract fun withId(entry: T, id: String): T

    /** A fresh entry named [id], filled with defaults that need the least fixing to be valid. */
    abstract fun blank(repository: AssetRepository, id: String): T

    fun ids(repository: AssetRepository): List<String> = entries(repository).map(::idOf)

    fun find(repository: AssetRepository, id: String): T? = entries(repository).firstOrNull { idOf(it) == id }

    fun contains(repository: AssetRepository, id: String): Boolean = find(repository, id) != null

    /** Replaces the entry currently named [id]; false when there is none. */
    fun replace(repository: AssetRepository, id: String, updated: T): Boolean {
        val entries = entries(repository)
        val index = entries.indexOfFirst { idOf(it) == id }
        if (index < 0) return false
        store(repository, entries.toMutableList().also { it[index] = updated })
        return true
    }

    fun add(repository: AssetRepository, entry: T) {
        store(repository, entries(repository) + entry)
    }

    fun remove(repository: AssetRepository, id: String): Boolean {
        val entries = entries(repository)
        val kept = entries.filterNot { idOf(it) == id }
        if (kept.size == entries.size) return false
        store(repository, kept)
        return true
    }
}

object TerrainCollection : SceneryCollection<TerrainDefinition>(SceneryKind.TERRAIN, TerrainDefinition.serializer()) {
    override fun entries(repository: AssetRepository) = repository.terrainCatalog.terrains

    override fun store(repository: AssetRepository, entries: List<TerrainDefinition>) {
        repository.terrainCatalog = repository.terrainCatalog.copy(terrains = entries)
    }

    override fun idOf(entry: TerrainDefinition) = entry.id

    override fun withId(entry: TerrainDefinition, id: String) = entry.copy(id = id)

    override fun blank(repository: AssetRepository, id: String) = TerrainDefinition(id = id, fallbackColor = "#808080")
}

object TileCollection : SceneryCollection<TileDefinition>(SceneryKind.TILE, TileDefinition.serializer()) {
    override fun entries(repository: AssetRepository) = repository.tileCatalog.tiles

    override fun store(repository: AssetRepository, entries: List<TileDefinition>) {
        repository.tileCatalog = repository.tileCatalog.copy(tiles = entries)
    }

    override fun idOf(entry: TileDefinition) = entry.id

    override fun withId(entry: TileDefinition, id: String) = entry.copy(id = id)

    /** A full tile of the first terrain - valid at once - with a skin named after it, ready for art. */
    override fun blank(repository: AssetRepository, id: String): TileDefinition {
        val terrain = repository.terrainCatalog.terrains.firstOrNull()?.id.orEmpty()
        return TileDefinition(id = id, skin = "tile_$id", corners = TileCorners(terrain, terrain, terrain, terrain))
    }
}

object WallCollection : SceneryCollection<WallDefinition>(SceneryKind.WALL, WallDefinition.serializer()) {
    override fun entries(repository: AssetRepository) = repository.wallCatalog.walls

    override fun store(repository: AssetRepository, entries: List<WallDefinition>) {
        repository.wallCatalog = repository.wallCatalog.copy(walls = entries)
    }

    override fun idOf(entry: WallDefinition) = entry.id

    override fun withId(entry: WallDefinition, id: String) = entry.copy(id = id)

    override fun blank(repository: AssetRepository, id: String) =
        WallDefinition(id = id, skin = "wall_$id", segment = WallSegment.FAR)
}

object StyleCollection : SceneryCollection<SceneryStyle>(SceneryKind.STYLE, SceneryStyle.serializer()) {
    override fun entries(repository: AssetRepository) = repository.sceneryStyleCatalog.styles

    override fun store(repository: AssetRepository, entries: List<SceneryStyle>) {
        repository.sceneryStyleCatalog = repository.sceneryStyleCatalog.copy(styles = entries)
    }

    override fun idOf(entry: SceneryStyle) = entry.id

    override fun withId(entry: SceneryStyle, id: String) = entry.copy(id = id)

    override fun blank(repository: AssetRepository, id: String) = SceneryStyle(id = id)
}
