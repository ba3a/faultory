package com.faultory.core.screens.shopfloor

import com.faultory.core.shop.TileCoordinate

class HoverState {
    var hoveredTile: TileCoordinate? = null
    var isBackButtonHovered: Boolean = false
    var isLanguageButtonHovered: Boolean = false
    var hoveredCompletionAction: CompletionAction? = null

    fun clearForShiftEnd() {
        hoveredTile = null
        isBackButtonHovered = false
        isLanguageButtonHovered = false
    }

    fun reset() {
        hoveredTile = null
        isBackButtonHovered = false
        isLanguageButtonHovered = false
        hoveredCompletionAction = null
    }
}
