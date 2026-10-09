package com.faultory.editor.ui.tree

import com.faultory.editor.model.SceneryKind

sealed class AssetSelection {
    data class Product(val id: String) : AssetSelection()
    data class Worker(val id: String) : AssetSelection()
    data class Machine(val id: String) : AssetSelection()
    data class Level(val id: String) : AssetSelection()
    data class Blueprint(val shopAssetPath: String) : AssetSelection()

    /** An entry of one of the scenery catalogs - a terrain, ground tile, wall piece or style. */
    data class Scenery(val kind: SceneryKind, val id: String) : AssetSelection()
}
