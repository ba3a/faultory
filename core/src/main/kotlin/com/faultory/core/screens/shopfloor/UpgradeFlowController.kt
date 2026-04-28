package com.faultory.core.screens.shopfloor

import com.badlogic.gdx.math.Rectangle
import com.faultory.core.config.GameConfig
import com.faultory.core.shop.PlacedShopObject
import com.faultory.core.shop.PlacedShopObjectKind
import com.faultory.core.shop.ShopFloor

class UpgradeFlowController(
    private val shopFloor: ShopFloor,
    private val catalogLookup: CatalogLookup,
    private val shiftLifecycle: ShiftLifecycleController
) {
    var modal: UpgradeModalState? = null
        private set
    var hoveredOptionIndex: Int? = null
        private set

    val isModalOpen: Boolean get() = modal != null

    fun closeModal() {
        modal = null
        hoveredOptionIndex = null
    }

    fun hasUpgradesFor(objectId: String): Boolean {
        val obj = shopFloor.findObjectById(objectId) ?: return false
        return upgradeOptionsFor(obj).isNotEmpty()
    }

    /**
     * Opens the upgrade modal when there are two branches; otherwise applies
     * the single available upgrade directly. No-ops when nothing is available.
     */
    fun beginUpgrade(objectId: String) {
        val obj = shopFloor.findObjectById(objectId) ?: return
        val options = upgradeOptionsFor(obj)
        when (options.size) {
            0 -> return
            1 -> applyUpgrade(objectId, options.first())
            else -> openModal(obj, options)
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
            applyUpgrade(current.objectId, option)
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

    private fun applyUpgrade(objectId: String, option: UpgradeOption) {
        if (shopFloor.tryUpgradeObject(objectId, option.targetCatalogId, option.cost)) {
            shiftLifecycle.persist()
        }
    }

    private fun upgradeOptionsFor(obj: PlacedShopObject): List<UpgradeOption> {
        return when (obj.kind) {
            PlacedShopObjectKind.WORKER -> {
                val profile = catalogLookup.workerProfilesById[obj.catalogId] ?: return emptyList()
                profile.upgradeTree?.upgradeIds().orEmpty().mapNotNull { upgradeId ->
                    val upgraded = catalogLookup.workerProfilesById[upgradeId] ?: return@mapNotNull null
                    UpgradeOption(
                        targetCatalogId = upgraded.id,
                        displayName = upgraded.displayName,
                        kind = PlacedShopObjectKind.WORKER,
                        cost = upgraded.hireCost
                    )
                }
            }

            PlacedShopObjectKind.MACHINE -> {
                val spec = catalogLookup.machineSpecsById[obj.catalogId] ?: return emptyList()
                spec.upgradeTree?.upgradeIds().orEmpty().mapNotNull { upgradeId ->
                    val upgraded = catalogLookup.machineSpecsById[upgradeId] ?: return@mapNotNull null
                    UpgradeOption(
                        targetCatalogId = upgraded.id,
                        displayName = upgraded.displayName,
                        kind = PlacedShopObjectKind.MACHINE,
                        cost = upgraded.installCost
                    )
                }
            }
        }
    }

    private fun openModal(obj: PlacedShopObject, options: List<UpgradeOption>) {
        modal = UpgradeModalLayout.build(obj.id, obj.kind, options)
        hoveredOptionIndex = null
    }
}

data class UpgradeOption(
    val targetCatalogId: String,
    val displayName: String,
    val kind: PlacedShopObjectKind,
    val cost: Int,
    val bounds: Rectangle = Rectangle()
)

data class UpgradeModalState(
    val objectId: String,
    val kind: PlacedShopObjectKind,
    val bounds: Rectangle,
    val options: List<UpgradeOption>
)

object UpgradeModalLayout {
    private const val cardWidth = 220f
    private const val cardHeight = 132f
    private const val cardGap = 32f
    private const val verticalPadding = 32f
    private const val titleSpace = 64f

    fun build(
        objectId: String,
        kind: PlacedShopObjectKind,
        options: List<UpgradeOption>
    ): UpgradeModalState {
        val width = options.size * cardWidth + (options.size - 1).coerceAtLeast(0) * cardGap + 64f
        val height = cardHeight + titleSpace + verticalPadding
        val x = (GameConfig.virtualWidth - width) / 2f
        val y = (GameConfig.virtualHeight - height) / 2f
        val cardsTotal = options.size * cardWidth + (options.size - 1).coerceAtLeast(0) * cardGap
        val cardsStartX = x + (width - cardsTotal) / 2f
        val cardsY = y + verticalPadding
        val laidOut = options.mapIndexed { index, option ->
            option.copy(
                bounds = Rectangle(
                    cardsStartX + index * (cardWidth + cardGap),
                    cardsY,
                    cardWidth,
                    cardHeight
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
