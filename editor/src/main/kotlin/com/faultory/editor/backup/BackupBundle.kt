package com.faultory.editor.backup

import com.faultory.core.content.LevelCatalog
import com.faultory.core.content.SceneryStyleCatalog
import com.faultory.core.content.ShopCatalog
import com.faultory.core.content.TerrainCatalog
import com.faultory.core.content.TileCatalog
import com.faultory.core.content.WallCatalog
import com.faultory.core.shop.ShopBlueprint
import kotlinx.serialization.Serializable

@Serializable
data class BackupBundle(
    val version: Int = CURRENT_VERSION,
    val shopCatalog: ShopCatalog,
    val levelCatalog: LevelCatalog,
    val blueprints: Map<String, ShopBlueprint>,
    val terrainCatalog: TerrainCatalog,
    val tileCatalog: TileCatalog,
    val wallCatalog: WallCatalog,
    val sceneryStyleCatalog: SceneryStyleCatalog,
) {
    companion object {
        const val CURRENT_VERSION = 2
    }
}
