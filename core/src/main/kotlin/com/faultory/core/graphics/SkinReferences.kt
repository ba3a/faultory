package com.faultory.core.graphics

import com.faultory.core.content.ShopCatalog

object SkinReferences {
    fun referencedSkinIds(catalog: ShopCatalog): List<String> =
        (catalog.workers.map { it.skin } + catalog.machines.map { it.skin })
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()

    fun referencedAtlasPaths(
        catalog: ShopCatalog,
        definitionLookup: (String) -> SkinDefinition?
    ): List<String> =
        referencedSkinIds(catalog)
            .mapNotNull { definitionLookup(it)?.atlas }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
}
