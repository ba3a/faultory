package com.faultory.core.screens.shopfloor

import com.faultory.core.shop.TileCoordinate

class HoverState {
    var hoveredTile: TileCoordinate? = null
    /**
     * The id, not the record: a hovered object is remembered across frames while the mouse rests,
     * and the renderer looks it up again so the highlight follows the live object and disappears
     * with it rather than outliving its removal from the floor.
     */
    var hoveredObjectId: String? = null
    var isBackButtonHovered: Boolean = false
    var isLanguageButtonHovered: Boolean = false
    var hoveredCompletionAction: CompletionAction? = null

    fun clearForShiftEnd() {
        hoveredTile = null
        hoveredObjectId = null
        isBackButtonHovered = false
        isLanguageButtonHovered = false
    }

    fun reset() {
        hoveredTile = null
        hoveredObjectId = null
        isBackButtonHovered = false
        isLanguageButtonHovered = false
        hoveredCompletionAction = null
    }
}
