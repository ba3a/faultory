package com.faultory.core.graphics

import com.faultory.core.assets.AssetPaths
import com.faultory.core.content.ShopCatalog
import com.faultory.core.scenery.SceneryContent
import com.faultory.core.shop.ShopBlueprint

object SkinReferences {
    fun referencedSkinIds(catalog: ShopCatalog): List<String> =
        (catalog.workers.map { it.skin } + catalog.machines.map { it.skin } + catalog.products.map { it.skin })
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()

    /** Belt skins are authored per blueprint, so they are discovered when a level loads. */
    fun referencedBeltSkinIds(blueprint: ShopBlueprint): List<String> =
        blueprint.conveyorBelts
            .map { it.skin?.takeIf(String::isNotBlank) ?: AssetPaths.defaultBeltSkin }
            .distinct()
            .sorted()

    fun referencedAtlasPaths(
        catalog: ShopCatalog,
        definitionLookup: (String) -> SkinDefinition?
    ): List<String> = atlasPathsFor(referencedSkinIds(catalog), definitionLookup)

    /**
     * The scenery skins a level can draw: every tile whose four corners are all terrains the
     * blueprint names (no other tile can ever be chosen there), every wall piece, and every pinned
     * piece. A little more than is drawn - styles and noise decide the rest at layout time - but
     * never less.
     */
    fun referencedScenerySkinIds(blueprint: ShopBlueprint, scenery: SceneryContent): List<String> {
        val spec = blueprint.scenery
        val terrains = buildSet {
            add(spec.floorTerrain)
            add(spec.outsideTerrain)
            spec.regions.forEach { add(it.terrain) }
            spec.patches.forEach { add(it.terrain) }
        }
        val pinnedTiles = spec.tilePins.map { it.tileId }.toSet()
        val tileSkins = scenery.tiles.tiles
            .filter { tile -> tile.id in pinnedTiles || tile.corners.terrainIds().all { it in terrains } }
            .map { it.skin }
        return (tileSkins + scenery.walls.walls.map { it.skin })
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
    }

    /** Every skin a level adds on top of the shop catalog's: its belts and its scenery. */
    fun referencedLevelSkinIds(blueprint: ShopBlueprint, scenery: SceneryContent): List<String> =
        (referencedBeltSkinIds(blueprint) + referencedScenerySkinIds(blueprint, scenery)).distinct().sorted()

    fun referencedAtlasPaths(
        blueprint: ShopBlueprint,
        scenery: SceneryContent,
        definitionLookup: (String) -> SkinDefinition?
    ): List<String> = atlasPathsFor(referencedLevelSkinIds(blueprint, scenery), definitionLookup)

    private fun atlasPathsFor(
        skinIds: List<String>,
        definitionLookup: (String) -> SkinDefinition?
    ): List<String> =
        skinIds
            .mapNotNull { definitionLookup(it)?.atlas }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
}
