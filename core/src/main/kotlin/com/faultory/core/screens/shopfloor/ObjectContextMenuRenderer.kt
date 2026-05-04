package com.faultory.core.screens.shopfloor

import com.badlogic.gdx.graphics.Color

class ObjectContextMenuRenderer(
    private val workerAssignment: WorkerAssignmentController
) : ShopFloorLayer {
    override fun drawFill(ctx: ShopFloorRenderContext) {
        val contextMenu = workerAssignment.contextMenu ?: return
        val renderer = ctx.shapeRenderer
        renderer.color = MENU_BG
        renderer.rect(contextMenu.bounds.x, contextMenu.bounds.y, contextMenu.bounds.width, contextMenu.bounds.height)
        for (option in contextMenu.options) {
            renderer.color = if (workerAssignment.hoveredContextAction == option.action) OPTION_FILL_HOVERED else OPTION_FILL_DEFAULT
            renderer.rect(option.bounds.x, option.bounds.y, option.bounds.width, option.bounds.height)
        }
    }

    override fun drawLine(ctx: ShopFloorRenderContext) {
        val contextMenu = workerAssignment.contextMenu ?: return
        val renderer = ctx.shapeRenderer
        renderer.color = MENU_BORDER
        renderer.rect(contextMenu.bounds.x, contextMenu.bounds.y, contextMenu.bounds.width, contextMenu.bounds.height)
        for (option in contextMenu.options) {
            renderer.color = if (workerAssignment.hoveredContextAction == option.action) ShopFloorPalette.HIGHLIGHT_GOLD else OPTION_BORDER_DEFAULT
            renderer.rect(option.bounds.x, option.bounds.y, option.bounds.width, option.bounds.height)
        }
    }

    override fun drawText(ctx: ShopFloorRenderContext) {
        val contextMenu = workerAssignment.contextMenu ?: return
        val batch = ctx.spriteBatch
        val font = ctx.font
        val titleLayout = ctx.titleLayout
        for (option in contextMenu.options) {
            font.color = if (workerAssignment.hoveredContextAction == option.action) ShopFloorPalette.TEXT_HIGHLIGHT_GOLD else OPTION_TEXT
            titleLayout.setText(font, option.label)
            font.draw(batch, titleLayout, option.bounds.x + 12f, option.bounds.y + option.bounds.height - 12f)
        }
    }

    private companion object {
        private val MENU_BG = Color(0.14f, 0.16f, 0.19f, 0.98f)
        private val OPTION_FILL_HOVERED = Color(0.28f, 0.34f, 0.40f, 1f)
        private val OPTION_FILL_DEFAULT = Color(0.19f, 0.23f, 0.28f, 1f)
        private val MENU_BORDER = Color(0.55f, 0.61f, 0.66f, 1f)
        private val OPTION_BORDER_DEFAULT = Color(0.68f, 0.74f, 0.79f, 1f)
        private val OPTION_TEXT = Color(0.92f, 0.95f, 0.97f, 1f)
    }
}
