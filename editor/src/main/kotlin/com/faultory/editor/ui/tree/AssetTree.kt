package com.faultory.editor.ui.tree

import com.badlogic.gdx.Input
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener
import com.faultory.editor.repository.AssetRepository
import com.kotcrab.vis.ui.widget.VisTree

class AssetTree(
    repository: AssetRepository,
    private val selectionBus: SelectionBus = SelectionBus,
) : VisTree<AssetTreeNode, AssetSelection>() {

    var onContextMenu: ((AssetSelection) -> Unit)? = null

    private val primaryIndex: Map<AssetSelection, AssetTreeNode>

    init {
        val itemsNode = TreeNodeFactories.buildItemsNode(repository.shopCatalog)
        val scenesNode = TreeNodeFactories.buildScenesNode(repository.levelCatalog)
        add(itemsNode)
        add(scenesNode)
        itemsNode.expandAll()
        scenesNode.expandAll()

        primaryIndex = buildPrimaryIndex()

        addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent, actor: Actor) {
                val node = selection.lastSelected
                if (node == null) {
                    selectionBus.select(null)
                    return
                }
                if (node.isLink) {
                    val target = node.selection?.let { primaryIndex[it] }
                    if (target != null) {
                        target.expandTo()
                        selection.set(target)
                    }
                    return
                }
                selectionBus.select(node.selection)
            }
        })

        addListener(object : ClickListener(Input.Buttons.RIGHT) {
            override fun clicked(event: InputEvent, x: Float, y: Float) {
                val node = getNodeAt(y) ?: return
                if (node.isLink) return
                val target = node.selection ?: return
                selection.set(node)
                selectionBus.select(target)
                onContextMenu?.invoke(target)
            }
        })
    }

    private fun buildPrimaryIndex(): Map<AssetSelection, AssetTreeNode> {
        val map = mutableMapOf<AssetSelection, AssetTreeNode>()
        rootNodes.forEach { collectPrimary(it, map) }
        return map
    }

    private fun collectPrimary(
        node: AssetTreeNode,
        map: MutableMap<AssetSelection, AssetTreeNode>,
    ) {
        if (!node.isLink && node.selection != null) {
            map.putIfAbsent(node.selection, node)
        }
        node.children.forEach { collectPrimary(it, map) }
    }
}
