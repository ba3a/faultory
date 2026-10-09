package com.faultory.core.screens.shopfloor

import com.badlogic.gdx.math.Rectangle
import com.faultory.core.config.GameConfig
import com.faultory.core.config.UiMetrics
import com.faultory.core.content.LevelDefinition
import com.faultory.core.content.PurchaseOffer
import com.faultory.core.content.PurchaseOfferResolver
import com.faultory.core.encounters.EvaluationContext
import com.faultory.core.shop.PlacedShopObjectKind

class BankPanel(private val catalogLookup: CatalogLookup) {
    private val mutableEntries = mutableListOf<BankEntry>()
    val entries: List<BankEntry> get() = mutableEntries

    var selectedKey: PurchaseOffer? = null
        private set
    var hoveredKey: PurchaseOffer? = null
        private set

    fun rebuild(level: LevelDefinition, ctx: EvaluationContext) {
        mutableEntries.clear()
        val offers = PurchaseOfferResolver.offersFor(
            level = level,
            workerProfilesById = catalogLookup.workerProfilesById,
            machineSpecsById = catalogLookup.machineSpecsById,
            evaluationContext = ctx
        )
        for (offer in offers) {
            mutableEntries += BankEntry(key = offer)
        }
    }

    fun layout() {
        val workerEntries = mutableEntries.filter { it.key.kind == PlacedShopObjectKind.WORKER }
        val machineEntries = mutableEntries.filter { it.key.kind == PlacedShopObjectKind.MACHINE }
        layoutSection(workerEntries, UiMetrics.bankSectionPaddingX, UiMetrics.bankSectionPaddingY)
        layoutSection(
            machineEntries,
            GameConfig.virtualWidth / 2f + UiMetrics.bankSectionPaddingX,
            UiMetrics.bankSectionPaddingY
        )
    }

    fun toggleSelect(key: PurchaseOffer) {
        selectedKey = if (selectedKey == key) null else key
    }

    fun clearSelection() {
        selectedKey = null
    }

    fun clearHover() {
        hoveredKey = null
    }

    fun updateHover(uiX: Float, uiY: Float, enabled: Boolean) {
        hoveredKey = if (enabled) {
            mutableEntries.firstOrNull { it.bounds.contains(uiX, uiY) }?.key
        } else {
            null
        }
    }

    fun selectedEntry(): BankEntry? = mutableEntries.firstOrNull { it.key == selectedKey }

    private fun layoutSection(entries: List<BankEntry>, startX: Float, startY: Float) {
        var currentX = startX
        for (entry in entries) {
            entry.bounds.set(currentX, startY, UiMetrics.bankCardWidth, UiMetrics.bankCardHeight)
            currentX += UiMetrics.bankCardWidth + UiMetrics.bankCardGap
        }
    }
}

data class BankEntry(
    val key: PurchaseOffer,
    val bounds: Rectangle = Rectangle()
)
