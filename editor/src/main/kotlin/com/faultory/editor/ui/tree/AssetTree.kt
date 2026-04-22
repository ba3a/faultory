package com.faultory.editor.ui.tree

import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.faultory.editor.repository.AssetRepository
import com.kotcrab.vis.ui.widget.VisTree

class AssetTree(
    repository: AssetRepository,
    private val selectionBus: SelectionBus = SelectionBus,
) : VisTree<AssetTreeNode, AssetSelection>() {

    init {
        val itemsNode = TreeNodeFactories.buildItemsNode(repository.shopCatalog)
        add(itemsNode)
        itemsNode.expandAll()

        addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent, actor: Actor) {
                val node = selection.lastSelected
                selectionBus.select(node?.selection)
            }
        })
    }
}
