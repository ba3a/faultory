package com.faultory.core.screens.shopfloor

import com.badlogic.gdx.graphics.Color

class WorkerContextMenuRenderer(
    private val workerAssignment: WorkerAssignmentController
) : ShopFloorLayer {
    override fun drawFill(ctx: ShopFloorRenderContext) {
        val contextMenu = workerAssignment.contextMenu ?: return
        val renderer = ctx.shapeRenderer
        renderer.color = Color(0.14f, 0.16f, 0.19f, 0.98f)
        renderer.rect(contextMenu.bounds.x, contextMenu.bounds.y, contextMenu.bounds.width, contextMenu.bounds.height)
        for (option in contextMenu.options) {
            renderer.color = if (workerAssignment.hoveredContextAction == option.action) {
                Color(0.28f, 0.34f, 0.40f, 1f)
            } else {
                Color(0.19f, 0.23f, 0.28f, 1f)
            }
            renderer.rect(
                option.bounds.x,
                option.bounds.y,
                option.bounds.width,
                option.bounds.height
            )
        }
    }

    override fun drawLine(ctx: ShopFloorRenderContext) {
        val contextMenu = workerAssignment.contextMenu ?: return
        val renderer = ctx.shapeRenderer
        renderer.color = Color(0.55f, 0.61f, 0.66f, 1f)
        renderer.rect(contextMenu.bounds.x, contextMenu.bounds.y, contextMenu.bounds.width, contextMenu.bounds.height)
        for (option in contextMenu.options) {
            renderer.color = if (workerAssignment.hoveredContextAction == option.action) {
                Color(0.99f, 0.90f, 0.62f, 1f)
            } else {
                Color(0.68f, 0.74f, 0.79f, 1f)
            }
            renderer.rect(
                option.bounds.x,
                option.bounds.y,
                option.bounds.width,
                option.bounds.height
            )
        }
    }

    override fun drawText(ctx: ShopFloorRenderContext) {
        val contextMenu = workerAssignment.contextMenu ?: return
        val batch = ctx.spriteBatch
        val font = ctx.font
        val titleLayout = ctx.titleLayout
        for (option in contextMenu.options) {
            font.color = if (workerAssignment.hoveredContextAction == option.action) {
                Color(1f, 0.94f, 0.71f, 1f)
            } else {
                Color(0.92f, 0.95f, 0.97f, 1f)
            }
            titleLayout.setText(font, option.label)
            font.draw(
                batch,
                titleLayout,
                option.bounds.x + 12f,
                option.bounds.y + option.bounds.height - 12f
            )
        }
    }
}
