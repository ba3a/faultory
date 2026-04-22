package com.faultory.editor.ui.tree

sealed class AssetSelection {
    data class Product(val id: String) : AssetSelection()
    data class Worker(val id: String) : AssetSelection()
    data class Machine(val id: String) : AssetSelection()
    data class Level(val id: String) : AssetSelection()
    data class Blueprint(val shopAssetPath: String) : AssetSelection()
}
