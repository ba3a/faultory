package com.faultory.editor.backup

import com.faultory.core.content.LevelCatalog
import com.faultory.core.content.ShopCatalog
import com.faultory.core.shop.ShopBlueprint
import kotlinx.serialization.Serializable

@Serializable
data class BackupBundle(
    val version: Int = CURRENT_VERSION,
    val shopCatalog: ShopCatalog,
    val levelCatalog: LevelCatalog,
    val blueprints: Map<String, ShopBlueprint>,
) {
    companion object {
        const val CURRENT_VERSION = 1
    }
}
