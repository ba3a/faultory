package com.faultory.core.graphics

import com.faultory.core.content.ShopCatalog

object SkinReferences {
    fun referencedSkinIds(catalog: ShopCatalog): List<String> =
        (catalog.workers.map { it.skin } + catalog.machines.map { it.skin })
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
}
