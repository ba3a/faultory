package com.faultory.core.screens.shopfloor

import com.badlogic.gdx.math.Rectangle
import com.faultory.core.config.GameConfig
import com.faultory.core.content.LevelDefinition
import com.faultory.core.shop.PlacedShopObjectKind

class BankPanel(private val catalogLookup: CatalogLookup) {
    private val mutableEntries = mutableListOf<BankEntry>()
    val entries: List<BankEntry> get() = mutableEntries

    var selectedKey: BankEntryKey? = null
        private set
    var hoveredKey: BankEntryKey? = null
        private set

    fun rebuild(
        level: LevelDefinition,
        isLevelCompleted: (String) -> Boolean = { false }
    ) {
        mutableEntries.clear()
        for (workerId in level.availableWorkerIds) {
            val worker = catalogLookup.workerProfilesById[workerId] ?: continue
            if (worker.requiredCompletedLevelIds.any { !isLevelCompleted(it) }) continue
            mutableEntries += BankEntry(
                key = BankEntryKey(PlacedShopObjectKind.WORKER, worker.id)
            )
        }
        for (machineId in level.availableMachineIds) {
            val machine = catalogLookup.machineSpecsById[machineId] ?: continue
            if (machine.requiredCompletedLevelIds.any { !isLevelCompleted(it) }) continue
            mutableEntries += BankEntry(
                key = BankEntryKey(PlacedShopObjectKind.MACHINE, machine.id)
            )
        }
    }

    fun layout() {
        val workerEntries = mutableEntries.filter { it.key.kind == PlacedShopObjectKind.WORKER }
        val machineEntries = mutableEntries.filter { it.key.kind == PlacedShopObjectKind.MACHINE }
        layoutSection(workerEntries, GameConfig.bankSectionPaddingX, GameConfig.bankSectionPaddingY)
        layoutSection(machineEntries, GameConfig.virtualWidth / 2f + GameConfig.bankSectionPaddingX, GameConfig.bankSectionPaddingY)
    }

    fun toggleSelect(key: BankEntryKey) {
        selectedKey = if (selectedKey == key) null else key
    }

    fun clearSelection() {
        selectedKey = null
    }

    fun clearHover() {
        hoveredKey = null
    }

    fun updateHover(worldX: Float, worldY: Float, enabled: Boolean) {
        hoveredKey = if (enabled) {
            mutableEntries.firstOrNull { it.bounds.contains(worldX, worldY) }?.key
        } else {
            null
        }
    }

    fun selectedEntry(): BankEntry? = mutableEntries.firstOrNull { it.key == selectedKey }

    private fun layoutSection(entries: List<BankEntry>, startX: Float, startY: Float) {
        var currentX = startX
        for (entry in entries) {
            entry.bounds.set(currentX, startY, GameConfig.bankCardWidth, GameConfig.bankCardHeight)
            currentX += GameConfig.bankCardWidth + GameConfig.bankCardGap
        }
    }
}

data class BankEntry(
    val key: BankEntryKey,
    val bounds: Rectangle = Rectangle()
)

data class BankEntryKey(
    val kind: PlacedShopObjectKind,
    val catalogId: String
)
