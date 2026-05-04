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
import com.faultory.core.i18n.CatalogMessageKey
import com.faultory.core.content.LevelCatalog
import com.faultory.core.content.LevelDefinition
import com.faultory.core.content.LevelUnlockResolver
import com.faultory.core.i18n.LocaleManager
import com.faultory.core.i18n.Messages
import com.faultory.core.i18n.UiMessageKey

class LevelSelectionScreen(
    private val game: FaultoryGame
) : ScreenAdapter() {
    private val viewport = FitViewport(GameConfig.virtualWidth, GameConfig.virtualHeight)
    private val scratchVector = Vector3()
    private val titleLayout = GlyphLayout()
    private val subtitleLayout = GlyphLayout()
    private val hintLayout = GlyphLayout()
    private val cardBounds = mutableListOf<Rectangle>()
    private val languageButtonBounds = Rectangle(GameConfig.virtualWidth - 132f, GameConfig.virtualHeight - 80f, 96f, 40f)
    private lateinit var levelCatalog: LevelCatalog
    private var lockedLevelIds: Set<String> = emptySet()
    private val missingPrereqsByLevelId = mutableMapOf<String, List<String>>()
    private var selectedIndex = 0
    private var lockedMessageTimer = 0f

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

            if (languageButtonContains(screenX, screenY)) {
                LocaleManager.cycleLocale()
                return true
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

    private fun languageButtonContains(screenX: Int, screenY: Int): Boolean {
        scratchVector.set(screenX.toFloat(), screenY.toFloat(), 0f)
        viewport.unproject(scratchVector)
        return languageButtonBounds.contains(scratchVector.x, scratchVector.y)
    }

    override fun show() {
        viewport.update(Gdx.graphics.width, Gdx.graphics.height, true)
        levelCatalog = game.assetManager.get(AssetPaths.levelCatalog, LevelCatalog::class.java)
        refreshLockState()
        cardBounds.clear()
        layoutCards(levelCatalog.levels)
        selectedIndex = if (levelCatalog.levels.isEmpty()) {
            0
        } else {
            selectedIndex.coerceIn(0, levelCatalog.levels.lastIndex)
        }
        Gdx.input.inputProcessor = inputProcessor
    }

    private fun refreshLockState() {
        missingPrereqsByLevelId.clear()
        val locked = mutableSetOf<String>()
        for (level in levelCatalog.levels) {
            val missing = LevelUnlockResolver.missingPrerequisites(level, game.saveRepository)
            if (missing.isNotEmpty()) {
                locked += level.id
                missingPrereqsByLevelId[level.id] = missing
            }
        }
        lockedLevelIds = locked
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
        if (lockedMessageTimer > 0f) {
            lockedMessageTimer = (lockedMessageTimer - delta).coerceAtLeast(0f)
        }
        ScreenUtils.clear(0.05f, 0.06f, 0.08f, 1f)
        viewport.apply()
        viewport.camera.update()

        val renderer = game.renderContext.shapeRenderer
        renderer.projectionMatrix = viewport.camera.combined

        drawShapes(renderer)
        drawText()
    }

    private fun drawShapes(renderer: ShapeRenderer) {
        renderer.begin(ShapeRenderer.ShapeType.Filled)
        renderer.color = BACKGROUND
        renderer.rect(0f, 0f, GameConfig.virtualWidth, GameConfig.virtualHeight)

        renderer.color = TITLE_BAND
        renderer.rect(72f, GameConfig.virtualHeight - 180f, GameConfig.virtualWidth - 144f, 96f)

        renderer.color = LANGUAGE_BUTTON_FILL
        renderer.rect(languageButtonBounds.x, languageButtonBounds.y, languageButtonBounds.width, languageButtonBounds.height)

        for (index in levelCatalog.levels.indices) {
            val level = levelCatalog.levels[index]
            val locked = level.id in lockedLevelIds
            val bounds = cardBounds[index]
            renderer.color = when {
                locked && index == selectedIndex -> CARD_FILL_LOCKED_SELECTED
                locked -> CARD_FILL_LOCKED
                index == selectedIndex -> CARD_FILL_SELECTED
                else -> CARD_FILL_DEFAULT
            }
            renderer.rect(bounds.x, bounds.y, bounds.width, bounds.height)

            renderer.color = when {
                locked -> CARD_BAND_LOCKED
                index == selectedIndex -> CARD_BAND_SELECTED
                else -> CARD_BAND_DEFAULT
            }
            renderer.rect(bounds.x, bounds.y + bounds.height - 14f, bounds.width, 14f)
        }
        renderer.end()

        renderer.begin(ShapeRenderer.ShapeType.Line)
        renderer.color = LANGUAGE_BUTTON_BORDER
        renderer.rect(languageButtonBounds.x, languageButtonBounds.y, languageButtonBounds.width, languageButtonBounds.height)
        for (index in levelCatalog.levels.indices) {
            val level = levelCatalog.levels[index]
            val locked = level.id in lockedLevelIds
            val bounds = cardBounds[index]
            renderer.color = when {
                locked -> CARD_BORDER_LOCKED
                index == selectedIndex -> CARD_BORDER_SELECTED
                else -> CARD_BORDER_DEFAULT
            }
            renderer.rect(bounds.x, bounds.y, bounds.width, bounds.height)
        }
        renderer.end()
    }

    private fun drawText() {
        val batch = game.renderContext.spriteBatch
        val font = game.renderContext.uiFont
        batch.projectionMatrix = viewport.camera.combined

        batch.begin()
        font.color = TITLE_TEXT
        titleLayout.setText(font, Messages.text(UiMessageKey.LEVEL_SELECT_TITLE))
        font.draw(batch, titleLayout, 96f, GameConfig.virtualHeight - 118f)

        font.color = SUBTITLE_TEXT
        subtitleLayout.setText(font, Messages.text(UiMessageKey.LEVEL_SELECT_HINT))
        font.draw(batch, subtitleLayout, 96f, GameConfig.virtualHeight - 146f)

        font.color = LANGUAGE_BUTTON_TEXT
        val languageLabel = LocaleManager.currentLocale.toLanguageTag().uppercase()
        titleLayout.setText(font, languageLabel)
        font.draw(
            batch,
            titleLayout,
            languageButtonBounds.x + (languageButtonBounds.width - titleLayout.width) / 2f,
            languageButtonBounds.y + 26f
        )

        for (index in levelCatalog.levels.indices) {
            val level = levelCatalog.levels[index]
            val locked = level.id in lockedLevelIds
            val bounds = cardBounds[index]

            font.color = if (locked) CARD_TITLE_TEXT_LOCKED else CARD_TITLE_TEXT
            titleLayout.setText(font, Messages.catalog(CatalogMessageKey.LEVEL_DISPLAYNAME, level.id))
            font.draw(batch, titleLayout, bounds.x + 28f, bounds.y + bounds.height - 42f)

            font.color = if (locked) CARD_SUBTITLE_TEXT_LOCKED else CARD_SUBTITLE_TEXT
            subtitleLayout.setText(font, Messages.catalog(CatalogMessageKey.LEVEL_SUBTITLE, level.id))
            font.draw(batch, subtitleLayout, bounds.x + 28f, bounds.y + bounds.height - 74f)

            font.color = when {
                locked -> CARD_FOOTER_TEXT_LOCKED
                index == selectedIndex -> CARD_FOOTER_TEXT_SELECTED
                else -> CARD_FOOTER_TEXT
            }
            val footerText = if (locked) {
                val missing = missingPrereqsByLevelId[level.id].orEmpty()
                Messages.format(UiMessageKey.LEVEL_SELECT_LOCKED_REQUIRES, missing.joinToString(", "))
            } else {
                Messages.text(UiMessageKey.LEVEL_SELECT_OPEN)
            }
            hintLayout.setText(font, footerText)
            font.draw(batch, hintLayout, bounds.x + 28f, bounds.y + 42f)
        }

        if (lockedMessageTimer > 0f) {
            font.color = LOCKED_MESSAGE_TEXT
            val message = lockedSelectionMessage()
            if (message != null) {
                hintLayout.setText(font, message)
                val x = (GameConfig.virtualWidth - hintLayout.width) / 2f
                font.draw(batch, hintLayout, x, 220f)
            }
        }
        batch.end()
    }

    private fun lockedSelectionMessage(): String? {
        if (selectedIndex !in levelCatalog.levels.indices) return null
        val level = levelCatalog.levels[selectedIndex]
        val missing = missingPrereqsByLevelId[level.id] ?: return null
        return Messages.format(UiMessageKey.LEVEL_SELECT_LOCKED_HINT, missing.joinToString(", "))
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
        val level = levelCatalog.levels[selectedIndex]
        if (level.id in lockedLevelIds) {
            lockedMessageTimer = 2.5f
            return
        }
        game.openLevel(level)
    }

    private fun levelIndexAt(screenX: Int, screenY: Int): Int {
        scratchVector.set(screenX.toFloat(), screenY.toFloat(), 0f)
        viewport.unproject(scratchVector)
        return cardBounds.indexOfFirst { it.contains(scratchVector.x, scratchVector.y) }
    }

    private companion object {
        private val BACKGROUND = Color(0.10f, 0.12f, 0.14f, 1f)
        private val TITLE_BAND = Color(0.14f, 0.17f, 0.20f, 1f)
        private val LANGUAGE_BUTTON_FILL = Color(0.16f, 0.20f, 0.24f, 1f)
        private val CARD_FILL_LOCKED_SELECTED = Color(0.20f, 0.20f, 0.22f, 1f)
        private val CARD_FILL_LOCKED = Color(0.13f, 0.14f, 0.16f, 1f)
        private val CARD_FILL_SELECTED = Color(0.22f, 0.58f, 0.62f, 1f)
        private val CARD_FILL_DEFAULT = Color(0.18f, 0.21f, 0.24f, 1f)
        private val CARD_BAND_LOCKED = Color(0.28f, 0.24f, 0.20f, 1f)
        private val CARD_BAND_SELECTED = Color(0.90f, 0.74f, 0.29f, 1f)
        private val CARD_BAND_DEFAULT = Color(0.32f, 0.38f, 0.42f, 1f)
        private val LANGUAGE_BUTTON_BORDER = Color(0.55f, 0.61f, 0.66f, 1f)
        private val CARD_BORDER_LOCKED = Color(0.32f, 0.34f, 0.38f, 1f)
        private val CARD_BORDER_SELECTED = Color(0.98f, 0.88f, 0.61f, 1f)
        private val CARD_BORDER_DEFAULT = Color(0.43f, 0.49f, 0.54f, 1f)
        private val TITLE_TEXT = Color(0.94f, 0.95f, 0.96f, 1f)
        private val SUBTITLE_TEXT = Color(0.76f, 0.80f, 0.84f, 1f)
        private val LANGUAGE_BUTTON_TEXT = Color(0.90f, 0.93f, 0.95f, 1f)
        private val CARD_TITLE_TEXT_LOCKED = Color(0.62f, 0.64f, 0.68f, 1f)
        private val CARD_TITLE_TEXT = Color(0.98f, 0.99f, 1f, 1f)
        private val CARD_SUBTITLE_TEXT_LOCKED = Color(0.55f, 0.58f, 0.62f, 1f)
        private val CARD_SUBTITLE_TEXT = Color(0.83f, 0.87f, 0.90f, 1f)
        private val CARD_FOOTER_TEXT_LOCKED = Color(0.78f, 0.66f, 0.42f, 1f)
        private val CARD_FOOTER_TEXT_SELECTED = Color(1f, 0.92f, 0.68f, 1f)
        private val CARD_FOOTER_TEXT = Color(0.74f, 0.78f, 0.82f, 1f)
        private val LOCKED_MESSAGE_TEXT = Color(1f, 0.78f, 0.46f, 1f)
    }
}
