package com.faultory.core.screens.shopfloor

import com.badlogic.gdx.math.Rectangle
import com.faultory.core.config.GameConfig
import com.faultory.core.config.UiMetrics
import com.faultory.core.encounters.EvaluationContext
import com.faultory.core.shop.PlacedShopObjectKind
import com.faultory.core.shop.ShopFloor
import com.faultory.core.shop.UpgradeOption

/**
 * Thin adapter over the upgrade modal's pointer/hover state — the option list and unlock gating
 * come from [ShopFloor.upgradeOptionsFor] ([com.faultory.core.shop.systems.PlacementSystem] owns
 * that rule), so headless code sees the same options a player would.
 */
class UpgradeFlowController(
    private val shopFloor: ShopFloor,
    private val catalogLookup: CatalogLookup,
    private val shiftLifecycle: ShiftLifecycleController
) {
    var evaluationContext: EvaluationContext? = null

    var modal: UpgradeModalState? = null
        private set
    var hoveredOptionIndex: Int? = null
        private set

    val isModalOpen: Boolean get() = modal != null

    fun closeModal() {
        modal = null
        hoveredOptionIndex = null
    }

    fun hasUpgradesFor(objectId: String): Boolean = optionsFor(objectId).isNotEmpty()

    /**
     * Opens the upgrade modal when there are two branches; otherwise applies
     * the single available upgrade directly. No-ops when nothing is available.
     */
    fun beginUpgrade(objectId: String) {
        val obj = shopFloor.findObjectById(objectId) ?: return
        val options = optionsFor(objectId)
        when (options.size) {
            0 -> return
            1 -> options.first().let { applyUpgrade(objectId, it.targetCatalogId, it.cost) }
            else -> openModal(obj.id, obj.kind, options)
        }
    }

    fun updateHover(worldX: Float, worldY: Float): Boolean {
        val current = modal ?: return false
        hoveredOptionIndex = current.options
            .indexOfFirst { it.bounds.contains(worldX, worldY) }
            .takeIf { it >= 0 }
        return current.bounds.contains(worldX, worldY)
    }

    fun handleClick(worldX: Float, worldY: Float): Boolean {
        val current = modal ?: return false
        val option = current.options.firstOrNull { it.bounds.contains(worldX, worldY) }
        if (option != null) {
            applyUpgrade(current.objectId, option.targetCatalogId, option.cost)
            closeModal()
            return true
        }
        if (current.bounds.contains(worldX, worldY)) {
            return true
        }
        // Clicking outside dismisses the modal.
        closeModal()
        return true
    }

    private fun optionsFor(objectId: String): List<UpgradeOption> =
        shopFloor.upgradeOptionsFor(objectId, catalogLookup.workerProfilesById, evaluationContext)

    private fun applyUpgrade(objectId: String, targetCatalogId: String, cost: Int) {
        if (shopFloor.tryUpgradeObject(objectId, targetCatalogId, cost)) {
            shiftLifecycle.markDirty()
        }
    }

    private fun openModal(objectId: String, kind: PlacedShopObjectKind, options: List<UpgradeOption>) {
        modal = UpgradeModalLayout.build(objectId, kind, options)
        hoveredOptionIndex = null
    }
}

/** [UpgradeOption] laid out on the modal grid — the pure option plus this screen's own bounds. */
data class LaidOutUpgradeOption(
    val targetCatalogId: String,
    val kind: PlacedShopObjectKind,
    val cost: Int,
    val bounds: Rectangle = Rectangle()
)

private fun UpgradeOption.laidOutAt(bounds: Rectangle): LaidOutUpgradeOption =
    LaidOutUpgradeOption(targetCatalogId, kind, cost, bounds)

data class UpgradeModalState(
    val objectId: String,
    val kind: PlacedShopObjectKind,
    val bounds: Rectangle,
    val options: List<LaidOutUpgradeOption>
)

object UpgradeModalLayout {
    fun build(
        objectId: String,
        kind: PlacedShopObjectKind,
        options: List<UpgradeOption>
    ): UpgradeModalState {
        val width = options.size * UiMetrics.upgradeModalCardWidth +
            (options.size - 1).coerceAtLeast(0) * UiMetrics.upgradeModalCardGap +
            UiMetrics.upgradeModalHorizontalPadding * 2f
        val height = UiMetrics.upgradeModalCardHeight +
            UiMetrics.upgradeModalTitleSpace +
            UiMetrics.upgradeModalVerticalPadding
        val x = (GameConfig.virtualWidth - width) / 2f
        val y = (GameConfig.virtualHeight - height) / 2f
        val cardsTotal = options.size * UiMetrics.upgradeModalCardWidth +
            (options.size - 1).coerceAtLeast(0) * UiMetrics.upgradeModalCardGap
        val cardsStartX = x + (width - cardsTotal) / 2f
        val cardsY = y + UiMetrics.upgradeModalVerticalPadding
        val laidOut = options.mapIndexed { index, option ->
            option.laidOutAt(
                Rectangle(
                    cardsStartX + index * (UiMetrics.upgradeModalCardWidth + UiMetrics.upgradeModalCardGap),
                    cardsY,
                    UiMetrics.upgradeModalCardWidth,
                    UiMetrics.upgradeModalCardHeight
                )
            )
        }
        return UpgradeModalState(
            objectId = objectId,
            kind = kind,
            bounds = Rectangle(x, y, width, height),
            options = laidOut
        )
    }
}
