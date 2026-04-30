package com.faultory.core.screens.shopfloor

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.math.Rectangle
import com.faultory.core.config.GameConfig
import com.faultory.core.content.LevelDefinition
import com.faultory.core.content.MachineType
import com.faultory.core.i18n.LocaleManager
import com.faultory.core.i18n.MessageKey
import com.faultory.core.i18n.Messages
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
        renderer.color = if (hoverState.isBackButtonHovered) {
            Color(0.24f, 0.31f, 0.37f, 1f)
        } else {
            Color(0.16f, 0.20f, 0.24f, 1f)
        }
        renderer.rect(BACK_BUTTON_BOUNDS.x, BACK_BUTTON_BOUNDS.y, BACK_BUTTON_BOUNDS.width, BACK_BUTTON_BOUNDS.height)

        renderer.color = if (hoverState.isLanguageButtonHovered) {
            Color(0.24f, 0.31f, 0.37f, 1f)
        } else {
            Color(0.16f, 0.20f, 0.24f, 1f)
        }
        renderer.rect(LANGUAGE_BUTTON_BOUNDS.x, LANGUAGE_BUTTON_BOUNDS.y, LANGUAGE_BUTTON_BOUNDS.width, LANGUAGE_BUTTON_BOUNDS.height)
    }

    override fun drawLine(ctx: ShopFloorRenderContext) {
        val renderer = ctx.shapeRenderer
        renderer.color = if (hoverState.isBackButtonHovered) {
            Color(0.98f, 0.88f, 0.61f, 1f)
        } else {
            Color(0.55f, 0.61f, 0.66f, 1f)
        }
        renderer.rect(BACK_BUTTON_BOUNDS.x, BACK_BUTTON_BOUNDS.y, BACK_BUTTON_BOUNDS.width, BACK_BUTTON_BOUNDS.height)

        renderer.color = if (hoverState.isLanguageButtonHovered) {
            Color(0.98f, 0.88f, 0.61f, 1f)
        } else {
            Color(0.55f, 0.61f, 0.66f, 1f)
        }
        renderer.rect(LANGUAGE_BUTTON_BOUNDS.x, LANGUAGE_BUTTON_BOUNDS.y, LANGUAGE_BUTTON_BOUNDS.width, LANGUAGE_BUTTON_BOUNDS.height)
    }

    override fun drawText(ctx: ShopFloorRenderContext) {
        val batch = ctx.spriteBatch
        val font = ctx.font
        val titleLayout = ctx.titleLayout
        val hintLayout = ctx.hintLayout

        font.color = Color(0.95f, 0.96f, 0.97f, 1f)
        titleLayout.setText(font, Messages.catalog(MessageKey.LEVEL_DISPLAYNAME, level.id))
        font.draw(batch, titleLayout, 32f, GameConfig.virtualHeight - 28f)

        font.color = Color(0.76f, 0.80f, 0.84f, 1f)
        hintLayout.setText(
            font,
            Messages.format(
                MessageKey.HUD_STATUS,
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
                MessageKey.HUD_PROGRESS,
                (shiftLifecycle.dayDirector.shiftProgress * 100f).toInt(),
                level.starThresholds.oneStar,
                level.starThresholds.twoStar,
                level.starThresholds.threeStar,
                selectedItemText()
            )
        )
        font.draw(batch, hintLayout, 32f, GameConfig.virtualHeight - 76f)

        font.color = if (hoverState.isBackButtonHovered) {
            Color(1f, 0.94f, 0.71f, 1f)
        } else {
            Color(0.90f, 0.93f, 0.95f, 1f)
        }
        titleLayout.setText(font, Messages.text(MessageKey.HUD_BACK_TO_LEVELS))
        font.draw(batch, titleLayout, BACK_BUTTON_BOUNDS.x + 16f, BACK_BUTTON_BOUNDS.y + 26f)

        font.color = if (hoverState.isLanguageButtonHovered) {
            Color(1f, 0.94f, 0.71f, 1f)
        } else {
            Color(0.90f, 0.93f, 0.95f, 1f)
        }
        val languageLabel = LocaleManager.currentLocale.toLanguageTag().uppercase()
        titleLayout.setText(font, languageLabel)
        font.draw(
            batch,
            titleLayout,
            LANGUAGE_BUTTON_BOUNDS.x + (LANGUAGE_BUTTON_BOUNDS.width - titleLayout.width) / 2f,
            LANGUAGE_BUTTON_BOUNDS.y + 26f
        )
    }

    private fun selectedItemText(): String {
        if (shiftLifecycle.isShiftEnded) {
            return Messages.text(MessageKey.HUD_SHIFT_COMPLETE)
        }
        val assignmentWorker = workerAssignment.assignmentPendingWorkerId
            ?.let(shopFloor::findObjectById)
            ?.let {
                if (catalogLookup.workerProfilesById[it.catalogId] != null) {
                    Messages.catalog(MessageKey.WORKER_DISPLAYNAME, it.catalogId)
                } else {
                    Messages.text(MessageKey.HUD_WORKER_FALLBACK)
                }
            }
        if (assignmentWorker != null) {
            return Messages.format(MessageKey.HUD_ASSIGNING, assignmentWorker)
        }

        val entry = bankPanel.selectedEntry()
            ?: return Messages.text(MessageKey.HUD_HELP_DEFAULT)
        val displayName = when (entry.key.kind) {
            PlacedShopObjectKind.WORKER -> Messages.catalog(MessageKey.WORKER_DISPLAYNAME, entry.key.catalogId)
            PlacedShopObjectKind.MACHINE -> Messages.catalog(MessageKey.MACHINE_DISPLAYNAME, entry.key.catalogId)
        }
        return when (entry.key.kind) {
            PlacedShopObjectKind.WORKER -> Messages.format(MessageKey.HUD_HELP_WORKER, displayName)
            PlacedShopObjectKind.MACHINE -> {
                val machine = catalogLookup.machineSpecsById[entry.key.catalogId]
                if (machine?.type == MachineType.QA) {
                    Messages.format(MessageKey.HUD_HELP_QA, displayName)
                } else {
                    Messages.format(MessageKey.HUD_HELP_PRODUCER, displayName)
                }
            }
        }
    }

    companion object {
        val BACK_BUTTON_BOUNDS: Rectangle = Rectangle(
            GameConfig.virtualWidth - 248f,
            GameConfig.virtualHeight - 70f,
            216f,
            40f
        )

        val LANGUAGE_BUTTON_BOUNDS: Rectangle = Rectangle(
            GameConfig.virtualWidth - 336f,
            GameConfig.virtualHeight - 70f,
            80f,
            40f
        )
    }
}
