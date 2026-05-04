package com.faultory.core.screens.shopfloor

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.math.Rectangle
import com.faultory.core.config.GameConfig
import com.faultory.core.i18n.CatalogMessageKey
import com.faultory.core.content.LevelDefinition
import com.faultory.core.content.MachineType
import com.faultory.core.i18n.LocaleManager
import com.faultory.core.i18n.Messages
import com.faultory.core.i18n.UiMessageKey
import com.faultory.core.shop.PlacedShopObjectKind
import com.faultory.core.shop.ShopFloor

class HudRenderer(
    private val level: LevelDefinition,
    private val shopFloor: ShopFloor,
    private val catalogLookup: CatalogLookup,
    private val bankPanel: BankPanel,
    private val workerAssignment: WorkerAssignmentController,
    private val shiftLifecycle: ShiftLifecycleController,
    private val hoverState: HoverState
) : ShopFloorLayer {
    override fun drawFill(ctx: ShopFloorRenderContext) {
        val renderer = ctx.shapeRenderer
        val backButtonBounds = backButtonBounds()
        val languageButtonBounds = languageButtonBounds()
        renderer.color = if (hoverState.isBackButtonHovered) BUTTON_FILL_HOVERED else BUTTON_FILL_DEFAULT
        renderer.rect(backButtonBounds.x, backButtonBounds.y, backButtonBounds.width, backButtonBounds.height)

        renderer.color = if (hoverState.isLanguageButtonHovered) BUTTON_FILL_HOVERED else BUTTON_FILL_DEFAULT
        renderer.rect(languageButtonBounds.x, languageButtonBounds.y, languageButtonBounds.width, languageButtonBounds.height)
    }

    override fun drawLine(ctx: ShopFloorRenderContext) {
        val renderer = ctx.shapeRenderer
        val backButtonBounds = backButtonBounds()
        val languageButtonBounds = languageButtonBounds()
        renderer.color = if (hoverState.isBackButtonHovered) BUTTON_BORDER_HOVERED else BUTTON_BORDER_DEFAULT
        renderer.rect(backButtonBounds.x, backButtonBounds.y, backButtonBounds.width, backButtonBounds.height)

        renderer.color = if (hoverState.isLanguageButtonHovered) BUTTON_BORDER_HOVERED else BUTTON_BORDER_DEFAULT
        renderer.rect(languageButtonBounds.x, languageButtonBounds.y, languageButtonBounds.width, languageButtonBounds.height)
    }

    override fun drawText(ctx: ShopFloorRenderContext) {
        val batch = ctx.spriteBatch
        val font = ctx.font
        val titleLayout = ctx.titleLayout
        val hintLayout = ctx.hintLayout
        val backButtonBounds = backButtonBounds()
        val languageButtonBounds = languageButtonBounds()

        font.color = TITLE_TEXT
        titleLayout.setText(font, Messages.catalog(CatalogMessageKey.LEVEL_DISPLAYNAME, level.id))
        font.draw(batch, titleLayout, 32f, GameConfig.virtualHeight - 28f)

        font.color = ShopFloorPalette.TEXT_SECONDARY
        hintLayout.setText(
            font,
            Messages.format(
                UiMessageKey.HUD_STATUS,
                shopFloor.cash,
                shiftLifecycle.dayDirector.deliveredGoodProducts,
                shiftLifecycle.dayDirector.deliveredFaultyProducts,
                shiftLifecycle.dayDirector.earnedStars
            )
        )
        font.draw(batch, hintLayout, 32f, GameConfig.virtualHeight - 52f)

        hintLayout.setText(
            font,
            Messages.format(
                UiMessageKey.HUD_PROGRESS,
                (shiftLifecycle.dayDirector.shiftProgress * 100f).toInt(),
                level.starThresholds.oneStar,
                level.starThresholds.twoStar,
                level.starThresholds.threeStar,
                selectedItemText()
            )
        )
        font.draw(batch, hintLayout, 32f, GameConfig.virtualHeight - 76f)

        font.color = if (hoverState.isBackButtonHovered) ShopFloorPalette.TEXT_HIGHLIGHT_GOLD else BUTTON_TEXT_DEFAULT
        titleLayout.setText(font, Messages.text(UiMessageKey.HUD_BACK_TO_LEVELS))
        font.draw(batch, titleLayout, backButtonBounds.x + 16f, backButtonBounds.y + 26f)

        font.color = if (hoverState.isLanguageButtonHovered) ShopFloorPalette.TEXT_HIGHLIGHT_GOLD else BUTTON_TEXT_DEFAULT
        val languageLabel = LocaleManager.currentLocale.toLanguageTag().uppercase()
        titleLayout.setText(font, languageLabel)
        font.draw(
            batch,
            titleLayout,
            languageButtonBounds.x + (languageButtonBounds.width - titleLayout.width) / 2f,
            languageButtonBounds.y + 26f
        )
    }

    private fun selectedItemText(): String {
        if (shiftLifecycle.isShiftEnded) {
            return Messages.text(UiMessageKey.HUD_SHIFT_COMPLETE)
        }
        val assignmentWorker = workerAssignment.assignmentPendingWorkerId
            ?.let(shopFloor::findObjectById)
            ?.let {
                if (catalogLookup.workerProfilesById[it.catalogId] != null) {
                    Messages.catalog(CatalogMessageKey.WORKER_DISPLAYNAME, it.catalogId)
                } else {
                    Messages.text(UiMessageKey.HUD_WORKER_FALLBACK)
                }
            }
        if (assignmentWorker != null) {
            return Messages.format(UiMessageKey.HUD_ASSIGNING, assignmentWorker)
        }

        val entry = bankPanel.selectedEntry()
            ?: return Messages.text(UiMessageKey.HUD_HELP_DEFAULT)
        val displayName = when (entry.key.kind) {
            PlacedShopObjectKind.WORKER -> Messages.catalog(CatalogMessageKey.WORKER_DISPLAYNAME, entry.key.catalogId)
            PlacedShopObjectKind.MACHINE -> Messages.catalog(CatalogMessageKey.MACHINE_DISPLAYNAME, entry.key.catalogId)
        }
        return when (entry.key.kind) {
            PlacedShopObjectKind.WORKER -> Messages.format(UiMessageKey.HUD_HELP_WORKER, displayName)
            PlacedShopObjectKind.MACHINE -> {
                val machine = catalogLookup.machineSpecsById[entry.key.catalogId]
                if (machine?.type == MachineType.QA) {
                    Messages.format(UiMessageKey.HUD_HELP_QA, displayName)
                } else {
                    Messages.format(UiMessageKey.HUD_HELP_PRODUCER, displayName)
                }
            }
        }
    }

    companion object {
        fun backButtonBounds(): Rectangle {
            return Rectangle(
                GameConfig.virtualWidth - GameConfig.hudButtonRightInset - GameConfig.hudBackButtonWidth,
                GameConfig.virtualHeight - GameConfig.hudButtonTopInset - GameConfig.hudButtonHeight,
                GameConfig.hudBackButtonWidth,
                GameConfig.hudButtonHeight
            )
        }

        fun languageButtonBounds(): Rectangle {
            return Rectangle(
                GameConfig.virtualWidth -
                    GameConfig.hudButtonRightInset -
                    GameConfig.hudBackButtonWidth -
                    GameConfig.hudButtonGap -
                    GameConfig.hudLanguageButtonWidth,
                GameConfig.virtualHeight - GameConfig.hudButtonTopInset - GameConfig.hudButtonHeight,
                GameConfig.hudLanguageButtonWidth,
                GameConfig.hudButtonHeight
            )
        }

        private val BUTTON_FILL_HOVERED = Color(0.24f, 0.31f, 0.37f, 1f)
        private val BUTTON_FILL_DEFAULT = Color(0.16f, 0.20f, 0.24f, 1f)
        private val BUTTON_BORDER_HOVERED = Color(0.98f, 0.88f, 0.61f, 1f)
        private val BUTTON_BORDER_DEFAULT = Color(0.55f, 0.61f, 0.66f, 1f)
        private val TITLE_TEXT = Color(0.95f, 0.96f, 0.97f, 1f)
        private val BUTTON_TEXT_DEFAULT = Color(0.90f, 0.93f, 0.95f, 1f)
    }
}
