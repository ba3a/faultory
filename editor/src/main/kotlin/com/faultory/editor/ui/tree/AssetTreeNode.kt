package com.faultory.editor.ui.tree

import com.badlogic.gdx.scenes.scene2d.ui.Tree
import com.kotcrab.vis.ui.widget.VisLabel

class AssetTreeNode(
    label: String,
    val selection: AssetSelection?,
) : Tree.Node<AssetTreeNode, AssetSelection, VisLabel>(VisLabel(label)) {

    init {
        isSelectable = selection != null
        if (selection != null) {
            value = selection
        }
    }
}
