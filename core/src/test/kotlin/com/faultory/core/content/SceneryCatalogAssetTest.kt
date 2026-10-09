package com.faultory.core.content

import com.faultory.core.TestAssets
import com.faultory.core.config.FaultoryJson
import com.faultory.core.scenery.SceneryColor
import com.faultory.core.shop.ShopBlueprint
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The shipped scenery catalogs decode, and every cross-reference inside them resolves: a broken
 * one would not crash the game - the scenery degrades to fallback colours - so it would otherwise
 * go unnoticed until someone looked closely at a level.
 */
class SceneryCatalogAssetTest {
    private val terrains = decode("terrains.json", TerrainCatalog.serializer())
    private val tiles = decode("tiles.json", TileCatalog.serializer())
    private val walls = decode("walls.json", WallCatalog.serializer())
    private val styles = decode("scenery-styles.json", SceneryStyleCatalog.serializer())

    @Test
    fun `ids are unique within each catalog`() {
        assertUnique(terrains.terrains.map { it.id }, "terrain")
        assertUnique(tiles.tiles.map { it.id }, "tile")
        assertUnique(walls.walls.map { it.id }, "wall")
        assertUnique(styles.styles.map { it.id }, "style")
    }

    @Test
    fun `every terrain has a drawable fallback colour`() {
        assertTrue(terrains.terrains.isNotEmpty(), "the game needs at least one terrain to lay ground with")
        terrains.terrains.forEach { terrain ->
            assertNotNull(SceneryColor.parseOrNull(terrain.fallbackColor), "terrain '${terrain.id}' colour")
        }
    }

    @Test
    fun `every tile corner names a shipped terrain`() {
        val known = terrains.terrains.map { it.id }.toSet()
        tiles.tiles.forEach { tile ->
            tile.corners.terrainIds().forEach { corner ->
                assertTrue(corner in known, "tile '${tile.id}' corner '$corner' is not a terrain")
            }
        }
    }

    @Test
    fun `weights and multipliers are never negative`() {
        tiles.tiles.forEach { assertTrue(it.weight >= 0f, "tile '${it.id}' weight") }
        walls.walls.forEach { assertTrue(it.weight >= 0f, "wall '${it.id}' weight") }
        styles.styles.forEach { style ->
            style.tagWeights.forEach { assertTrue(it.multiplier >= 0f, "style '${style.id}' tag '${it.tag}'") }
        }
    }

    @Test
    fun `fallback colours parse in both authored forms and reject anything else`() {
        assertEquals(0x1F262EFF, SceneryColor.parseOrNull("#1F262E"))
        assertEquals(0x1F262E80, SceneryColor.parseOrNull("#1f262e80"))
        listOf("1F262E", "#1F262", "#1F262E8", "#GGGGGG", "").forEach { malformed ->
            assertEquals(null, SceneryColor.parseOrNull(malformed), "'$malformed'")
        }
    }

    @Test
    fun `shipped blueprints build their scenery from shipped content`() {
        val terrainIds = terrains.terrains.map { it.id }.toSet()
        val shops = TestAssets.root.resolve("shops").listDirectoryEntries("*.json")
        assertTrue(shops.isNotEmpty())
        shops.forEach { path ->
            val scenery = FaultoryJson.instance.decodeFromString(ShopBlueprint.serializer(), path.readText()).scenery
            val named = listOf(scenery.floorTerrain, scenery.outsideTerrain) +
                scenery.regions.map { it.terrain } + scenery.patches.map { it.terrain }
            named.forEach { assertTrue(it in terrainIds, "${path.fileName} names unknown terrain '$it'") }
            scenery.styleId?.let { id -> assertTrue(styles.styles.any { it.id == id }, "${path.fileName} style '$id'") }
            scenery.tilePins.forEach { pin -> assertTrue(tiles.tiles.any { it.id == pin.tileId }, "${path.fileName}") }
            scenery.wallPins.forEach { pin -> assertTrue(walls.walls.any { it.id == pin.wallId }, "${path.fileName}") }
        }
    }

    private fun <T> decode(file: String, serializer: kotlinx.serialization.KSerializer<T>): T =
        FaultoryJson.instance.decodeFromString(serializer, TestAssets.root.resolve("content").resolve(file).readText())

    private fun assertUnique(ids: List<String>, kind: String) {
        assertEquals(ids.distinct(), ids, "duplicate $kind ids")
    }
}
