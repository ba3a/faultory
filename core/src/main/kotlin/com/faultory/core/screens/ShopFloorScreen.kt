package com.faultory.core.screens

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.InputAdapter
import com.badlogic.gdx.ScreenAdapter
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.math.Rectangle
import com.badlogic.gdx.math.Vector3
import com.badlogic.gdx.utils.ScreenUtils
import com.badlogic.gdx.utils.viewport.FitViewport
import com.faultory.core.FaultoryGame
import com.faultory.core.config.GameConfig
import com.faultory.core.content.ShopCatalog
import com.faultory.core.save.GameSave
import com.faultory.core.shop.BeltNode
import com.faultory.core.shop.ShopFloor
import com.faultory.core.systems.ProductionDayDirector

class ShopFloorScreen(
    private val game: FaultoryGame,
    private val shopFloor: ShopFloor,
    private val saveSnapshot: GameSave,
    private val shopCatalog: ShopCatalog
) : ScreenAdapter() {
    private val viewport = FitViewport(GameConfig.virtualWidth, GameConfig.virtualHeight)
    private val backButtonBounds = Rectangle(GameConfig.virtualWidth - 264f, GameConfig.virtualHeight - 92f, 216f, 40f)
    private val scratchVector = Vector3()
    private val titleLayout = GlyphLayout()
    private val hintLayout = GlyphLayout()
    private val dayDirector = ProductionDayDirector(
        shiftLengthSeconds = shopFloor.blueprint.shiftLengthSeconds,
        targetQualityPercent = saveSnapshot.activeShift.targetQualityPercent,
        initialShippedProducts = saveSnapshot.activeShift.shippedProducts,
        initialFaultyProducts = saveSnapshot.activeShift.faultyProducts
    )
    private var isBackButtonHovered = false

    private val inputProcessor = object : InputAdapter() {
        override fun keyDown(keycode: Int): Boolean {
            if (keycode == Input.Keys.ESCAPE) {
                returnToLevelSelection()
                return true
            }
            return false
        }

        override fun mouseMoved(screenX: Int, screenY: Int): Boolean {
            isBackButtonHovered = isBackButtonHit(screenX, screenY)
            return isBackButtonHovered
        }

        override fun touchDown(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean {
            if (button != Input.Buttons.LEFT) {
                return false
            }

            if (isBackButtonHit(screenX, screenY)) {
                returnToLevelSelection()
                return true
            }
            return false
        }
    }

    override fun show() {
        viewport.update(Gdx.graphics.width, Gdx.graphics.height, true)
        Gdx.input.inputProcessor = inputProcessor
    }

    override fun hide() {
        if (Gdx.input.inputProcessor === inputProcessor) {
            Gdx.input.inputProcessor = null
        }
    }

    override fun render(delta: Float) {
        shopFloor.update(delta)
        dayDirector.update(delta)

        ScreenUtils.clear(0.06f, 0.07f, 0.09f, 1f)
        viewport.apply()
        viewport.camera.update()

        val renderer = game.shapeRenderer
        renderer.projectionMatrix = viewport.camera.combined

        drawFilledLayer(renderer)
        drawLineLayer(renderer)
        drawText()
    }

    override fun resize(width: Int, height: Int) {
        viewport.update(width, height, true)
    }

    override fun dispose() {
        shopFloor.dispose()
    }

    private fun drawFilledLayer(renderer: ShapeRenderer) {
        val workerCards = shopCatalog.workers.size.coerceAtLeast(1)
        val unitCards = shopCatalog.inspectionUnits.size.coerceAtLeast(1)
        val productCards = shopCatalog.products.size.coerceAtLeast(1)
        val qualityRatio = (dayDirector.currentQualityPercent / 100f).coerceIn(0f, 1f)
        val shiftRatio = dayDirector.shiftProgress

        renderer.begin(ShapeRenderer.ShapeType.Filled)
        renderer.color = Color(0.10f, 0.12f, 0.14f, 1f)
        renderer.rect(0f, 0f, GameConfig.virtualWidth, GameConfig.virtualHeight)

        renderer.color = Color(0.13f, 0.16f, 0.19f, 1f)
        renderer.rect(32f, GameConfig.virtualHeight - 144f, 520f, 104f)
        renderer.rect(GameConfig.virtualWidth - 344f, 40f, 304f, 112f)

        renderer.color = if (isBackButtonHovered) {
            Color(0.24f, 0.31f, 0.37f, 1f)
        } else {
            Color(0.16f, 0.20f, 0.24f, 1f)
        }
        renderer.rect(backButtonBounds.x, backButtonBounds.y, backButtonBounds.width, backButtonBounds.height)

        renderer.color = Color(0.20f, 0.62f, 0.49f, 1f)
        renderer.rect(56f, GameConfig.virtualHeight - 112f, 240f * qualityRatio, 22f)

        renderer.color = Color(0.88f, 0.76f, 0.30f, 1f)
        renderer.rect(56f, GameConfig.virtualHeight - 76f, 240f * shiftRatio, 18f)

        renderer.color = Color(0.78f, 0.43f, 0.25f, 1f)
        val thresholdOffset = 56f + 240f * (dayDirector.targetQualityPercent / 100f).coerceIn(0f, 1f)
        renderer.rect(thresholdOffset, GameConfig.virtualHeight - 118f, 4f, 34f)

        renderer.color = Color(0.21f, 0.69f, 0.82f, 1f)
        repeat(workerCards) { index ->
            renderer.circle(88f + index * 44f, 112f, 14f)
        }

        renderer.color = Color(0.76f, 0.51f, 0.18f, 1f)
        for (anchor in shopFloor.blueprint.inspectionAnchors) {
            renderer.rect(anchor.x - 16f, anchor.y - 16f, 32f, 32f)
        }

        renderer.color = Color(0.71f, 0.73f, 0.75f, 1f)
        repeat(unitCards) { index ->
            renderer.rect(88f + index * 52f, 72f, 28f, 20f)
        }

        renderer.color = Color(0.86f, 0.86f, 0.89f, 1f)
        repeat(productCards) { index ->
            renderer.rect(
                GameConfig.virtualWidth - 312f + index * 40f,
                72f,
                24f,
                28f
            )
        }
        renderer.end()
    }

    private fun drawLineLayer(renderer: ShapeRenderer) {
        renderer.begin(ShapeRenderer.ShapeType.Line)
        renderer.color = Color(0.48f, 0.55f, 0.60f, 1f)
        drawBelts(renderer)

        renderer.color = Color(0.20f, 0.62f, 0.49f, 1f)
        for (spawnPoint in shopFloor.blueprint.workerSpawnPoints) {
            renderer.circle(spawnPoint.x, spawnPoint.y, 18f)
        }

        renderer.color = Color(0.87f, 0.55f, 0.20f, 1f)
        for (anchor in shopFloor.blueprint.inspectionAnchors) {
            renderer.circle(anchor.x, anchor.y, anchor.reach)
        }

        renderer.color = if (isBackButtonHovered) {
            Color(0.98f, 0.88f, 0.61f, 1f)
        } else {
            Color(0.55f, 0.61f, 0.66f, 1f)
        }
        renderer.rect(backButtonBounds.x, backButtonBounds.y, backButtonBounds.width, backButtonBounds.height)
        renderer.end()
    }

    private fun drawBelts(renderer: ShapeRenderer) {
        for (belt in shopFloor.blueprint.conveyorBelts) {
            val nodes = belt.checkpoints
            for (index in 0 until nodes.lastIndex) {
                val current = nodes[index]
                val next = nodes[index + 1]
                renderer.line(current.x, current.y, next.x, next.y)
            }

            for (node in nodes) {
                drawNode(renderer, node)
            }
        }
    }

    private fun drawNode(renderer: ShapeRenderer, node: BeltNode) {
        renderer.circle(node.x, node.y, 10f)
    }

    private fun drawText() {
        val batch = game.spriteBatch
        val font = game.uiFont
        batch.projectionMatrix = viewport.camera.combined

        batch.begin()
        font.color = Color(0.95f, 0.96f, 0.97f, 1f)
        titleLayout.setText(font, shopFloor.blueprint.displayName)
        font.draw(batch, titleLayout, 56f, GameConfig.virtualHeight - 48f)

        font.color = Color(0.76f, 0.80f, 0.84f, 1f)
        hintLayout.setText(font, "Esc or click the button to return to level selection")
        font.draw(batch, hintLayout, 56f, GameConfig.virtualHeight - 72f)

        font.color = if (isBackButtonHovered) {
            Color(1f, 0.94f, 0.71f, 1f)
        } else {
            Color(0.90f, 0.93f, 0.95f, 1f)
        }
        titleLayout.setText(font, "Back To Level Selection")
        font.draw(batch, titleLayout, backButtonBounds.x + 16f, backButtonBounds.y + 26f)
        batch.end()
    }

    private fun isBackButtonHit(screenX: Int, screenY: Int): Boolean {
        scratchVector.set(screenX.toFloat(), screenY.toFloat(), 0f)
        viewport.unproject(scratchVector)
        return backButtonBounds.contains(scratchVector.x, scratchVector.y)
    }

    private fun returnToLevelSelection() {
        game.openLevelSelection()
    }
}
