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

    fun rebuild(level: LevelDefinition) {
        mutableEntries.clear()
        for (workerId in level.availableWorkerIds) {
            val worker = catalogLookup.workerProfilesById[workerId] ?: continue
            mutableEntries += BankEntry(
                key = BankEntryKey(PlacedShopObjectKind.WORKER, worker.id),
                displayName = worker.displayName
            )
        }
        for (machineId in level.availableMachineIds) {
            val machine = catalogLookup.machineSpecsById[machineId] ?: continue
            mutableEntries += BankEntry(
                key = BankEntryKey(PlacedShopObjectKind.MACHINE, machine.id),
                displayName = machine.displayName
            )
        }
    }

    fun layout() {
        val workerEntries = mutableEntries.filter { it.key.kind == PlacedShopObjectKind.WORKER }
        val machineEntries = mutableEntries.filter { it.key.kind == PlacedShopObjectKind.MACHINE }
        layoutSection(workerEntries, 40f, 24f)
        layoutSection(machineEntries, GameConfig.virtualWidth / 2f + 40f, 24f)
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
        val cardWidth = 150f
        val cardHeight = 92f
        val gap = 16f
        var currentX = startX
        for (entry in entries) {
            entry.bounds.set(currentX, startY, cardWidth, cardHeight)
            currentX += cardWidth + gap
        }
    }
}

data class BankEntry(
    val key: BankEntryKey,
    val displayName: String,
    val bounds: Rectangle = Rectangle()
)

data class BankEntryKey(
    val kind: PlacedShopObjectKind,
    val catalogId: String
)
