package com.faultory.core.screens.shopfloor

import com.badlogic.gdx.math.Rectangle
import com.faultory.core.shop.PlacedShopObjectKind

data class ObjectContextMenuState(
    val objectId: String,
    val kind: PlacedShopObjectKind,
    val bounds: Rectangle,
    val options: List<ObjectContextMenuOption>
)

data class ObjectContextMenuOption(
    val action: ObjectContextAction,
    val label: String,
    val bounds: Rectangle
)

enum class ObjectContextAction {
    ASSIGN_TO_MACHINE,
    ASSIGN_TO_QA,
    UPGRADE
}
