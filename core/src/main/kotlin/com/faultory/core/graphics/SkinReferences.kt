package com.faultory.core.graphics

import com.faultory.core.assets.AssetPaths
import com.faultory.core.content.ShopCatalog
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

    fun referencedAtlasPaths(
        blueprint: ShopBlueprint,
        definitionLookup: (String) -> SkinDefinition?
    ): List<String> = atlasPathsFor(referencedBeltSkinIds(blueprint), definitionLookup)

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
