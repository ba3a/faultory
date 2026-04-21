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
import com.faultory.core.assets.AssetPaths
import com.faultory.core.config.GameConfig
import com.faultory.core.content.LevelCatalog
import com.faultory.core.content.LevelDefinition

class LevelSelectionScreen(
    private val game: FaultoryGame
) : ScreenAdapter() {
    private val viewport = FitViewport(GameConfig.virtualWidth, GameConfig.virtualHeight)
    private val scratchVector = Vector3()
    private val titleLayout = GlyphLayout()
    private val subtitleLayout = GlyphLayout()
    private val hintLayout = GlyphLayout()
    private val cardBounds = mutableListOf<Rectangle>()
    private lateinit var levelCatalog: LevelCatalog
    private var selectedIndex = 0

    private val inputProcessor = object : InputAdapter() {
        override fun keyDown(keycode: Int): Boolean {
            when (keycode) {
                Input.Keys.LEFT,
                Input.Keys.UP -> {
                    moveSelection(-1)
                    return true
                }

                Input.Keys.RIGHT,
                Input.Keys.DOWN -> {
                    moveSelection(1)
                    return true
                }

                Input.Keys.ENTER,
                Input.Keys.SPACE -> {
                    startSelectedLevel()
                    return true
                }
            }

            return false
        }

        override fun mouseMoved(screenX: Int, screenY: Int): Boolean {
            val hoveredIndex = levelIndexAt(screenX, screenY)
            if (hoveredIndex >= 0) {
                selectedIndex = hoveredIndex
            }
            return hoveredIndex >= 0
        }

        override fun touchDown(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean {
            if (button != Input.Buttons.LEFT) {
                return false
            }

            val hoveredIndex = levelIndexAt(screenX, screenY)
            if (hoveredIndex >= 0) {
                selectedIndex = hoveredIndex
                startSelectedLevel()
                return true
            }
            return false
        }
    }

    override fun show() {
        viewport.update(Gdx.graphics.width, Gdx.graphics.height, true)
        levelCatalog = game.assetManager.get(AssetPaths.levelCatalog, LevelCatalog::class.java)
        cardBounds.clear()
        layoutCards(levelCatalog.levels)
        selectedIndex = if (levelCatalog.levels.isEmpty()) {
            0
        } else {
            selectedIndex.coerceIn(0, levelCatalog.levels.lastIndex)
        }
        Gdx.input.inputProcessor = inputProcessor
    }

    override fun hide() {
        if (Gdx.input.inputProcessor === inputProcessor) {
            Gdx.input.inputProcessor = null
        }
    }

    override fun resize(width: Int, height: Int) {
        viewport.update(width, height, true)
        if (::levelCatalog.isInitialized) {
            layoutCards(levelCatalog.levels)
        }
    }

    override fun render(delta: Float) {
        ScreenUtils.clear(0.05f, 0.06f, 0.08f, 1f)
        viewport.apply()
        viewport.camera.update()

        val renderer = game.shapeRenderer
        renderer.projectionMatrix = viewport.camera.combined

        drawShapes(renderer)
        drawText()
    }

    private fun drawShapes(renderer: ShapeRenderer) {
        renderer.begin(ShapeRenderer.ShapeType.Filled)
        renderer.color = Color(0.10f, 0.12f, 0.14f, 1f)
        renderer.rect(0f, 0f, GameConfig.virtualWidth, GameConfig.virtualHeight)

        renderer.color = Color(0.14f, 0.17f, 0.20f, 1f)
        renderer.rect(72f, GameConfig.virtualHeight - 180f, GameConfig.virtualWidth - 144f, 96f)

        for (index in levelCatalog.levels.indices) {
            val bounds = cardBounds[index]
            renderer.color = if (index == selectedIndex) {
                Color(0.22f, 0.58f, 0.62f, 1f)
            } else {
                Color(0.18f, 0.21f, 0.24f, 1f)
            }
            renderer.rect(bounds.x, bounds.y, bounds.width, bounds.height)

            renderer.color = if (index == selectedIndex) {
                Color(0.90f, 0.74f, 0.29f, 1f)
            } else {
                Color(0.32f, 0.38f, 0.42f, 1f)
            }
            renderer.rect(bounds.x, bounds.y + bounds.height - 14f, bounds.width, 14f)
        }
        renderer.end()

        renderer.begin(ShapeRenderer.ShapeType.Line)
        for (index in levelCatalog.levels.indices) {
            val bounds = cardBounds[index]
            renderer.color = if (index == selectedIndex) {
                Color(0.98f, 0.88f, 0.61f, 1f)
            } else {
                Color(0.43f, 0.49f, 0.54f, 1f)
            }
            renderer.rect(bounds.x, bounds.y, bounds.width, bounds.height)
        }
        renderer.end()
    }

    private fun drawText() {
        val batch = game.spriteBatch
        val font = game.uiFont
        batch.projectionMatrix = viewport.camera.combined

        batch.begin()
        font.color = Color(0.94f, 0.95f, 0.96f, 1f)
        titleLayout.setText(font, "Choose Shop")
        font.draw(batch, titleLayout, 96f, GameConfig.virtualHeight - 118f)

        font.color = Color(0.76f, 0.80f, 0.84f, 1f)
        subtitleLayout.setText(font, "Arrow keys move selection. Enter, Space, or click starts a level.")
        font.draw(batch, subtitleLayout, 96f, GameConfig.virtualHeight - 146f)

        for (index in levelCatalog.levels.indices) {
            val level = levelCatalog.levels[index]
            val bounds = cardBounds[index]

            font.color = Color(0.98f, 0.99f, 1f, 1f)
            titleLayout.setText(font, level.displayName)
            font.draw(batch, titleLayout, bounds.x + 28f, bounds.y + bounds.height - 42f)

            font.color = Color(0.83f, 0.87f, 0.90f, 1f)
            subtitleLayout.setText(font, level.subtitle)
            font.draw(batch, subtitleLayout, bounds.x + 28f, bounds.y + bounds.height - 74f)

            font.color = if (index == selectedIndex) {
                Color(1f, 0.92f, 0.68f, 1f)
            } else {
                Color(0.74f, 0.78f, 0.82f, 1f)
            }
            hintLayout.setText(font, "Open Level")
            font.draw(batch, hintLayout, bounds.x + 28f, bounds.y + 42f)
        }
        batch.end()
    }

    private fun layoutCards(levels: List<LevelDefinition>) {
        cardBounds.clear()
        if (levels.isEmpty()) {
            return
        }

        val cardWidth = 360f
        val cardHeight = 220f
        val gap = 40f
        val totalWidth = levels.size * cardWidth + (levels.size - 1) * gap
        var currentX = (GameConfig.virtualWidth - totalWidth) / 2f
        val y = 320f

        repeat(levels.size) {
            cardBounds += Rectangle(currentX, y, cardWidth, cardHeight)
            currentX += cardWidth + gap
        }
    }

    private fun moveSelection(direction: Int) {
        if (levelCatalog.levels.isEmpty()) {
            return
        }
        val size = levelCatalog.levels.size
        selectedIndex = (selectedIndex + direction + size) % size
    }

    private fun startSelectedLevel() {
        if (levelCatalog.levels.isEmpty()) {
            return
        }
        game.openLevel(levelCatalog.levels[selectedIndex])
    }

    private fun levelIndexAt(screenX: Int, screenY: Int): Int {
        scratchVector.set(screenX.toFloat(), screenY.toFloat(), 0f)
        viewport.unproject(scratchVector)
        return cardBounds.indexOfFirst { it.contains(scratchVector.x, scratchVector.y) }
    }
}
