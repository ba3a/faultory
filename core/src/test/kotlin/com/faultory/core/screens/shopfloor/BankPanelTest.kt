package com.faultory.core.screens.shopfloor

import com.faultory.core.content.LevelDefinition
import com.faultory.core.content.LevelStarThresholds
import com.faultory.core.content.MachineRecipe
import com.faultory.core.content.MachineSpec
import com.faultory.core.content.MachineType
import com.faultory.core.content.Manuality
import com.faultory.core.content.ShopCatalog
import com.faultory.core.content.WorkerProfile
import com.faultory.core.content.WorkerRole
import com.faultory.core.content.WorkerRoleProfile
import com.faultory.core.shop.PlacedShopObjectKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BankPanelTest {
    private val workerNoPrereq = workerProfile("worker-a", requiredLevels = emptyList())
    private val workerWithPrereq = workerProfile("worker-b", requiredLevels = listOf("level-1"))
    private val machineNoPrereq = machineSpec("machine-a", requiredLevels = emptyList())
    private val machineWithPrereq = machineSpec("machine-b", requiredLevels = listOf("level-1"))

    private val catalog = ShopCatalog(
        workers = listOf(workerNoPrereq, workerWithPrereq),
        machines = listOf(machineNoPrereq, machineWithPrereq),
        products = emptyList()
    )
    private val panel = BankPanel(CatalogLookup(catalog))

    @Test
    fun `rebuild includes all items when no prerequisites are required`() {
        val level = levelWith(
            workers = listOf("worker-a"),
            machines = listOf("machine-a")
        )

        panel.rebuild(level)

        val keys = panel.entries.map { it.key }
        assertEquals(1, keys.count { it.kind == PlacedShopObjectKind.WORKER })
        assertEquals(1, keys.count { it.kind == PlacedShopObjectKind.MACHINE })
    }

    @Test
    fun `rebuild excludes items whose prerequisite level is not completed`() {
        val level = levelWith(
            workers = listOf("worker-a", "worker-b"),
            machines = listOf("machine-a", "machine-b")
        )

        panel.rebuild(level, isLevelCompleted = { false })

        val keys = panel.entries.map { it.key }
        assertEquals(listOf("worker-a"), keys.filter { it.kind == PlacedShopObjectKind.WORKER }.map { it.catalogId })
        assertEquals(listOf("machine-a"), keys.filter { it.kind == PlacedShopObjectKind.MACHINE }.map { it.catalogId })
    }

    @Test
    fun `rebuild includes prerequisite-gated items when all prereqs are completed`() {
        val level = levelWith(
            workers = listOf("worker-a", "worker-b"),
            machines = listOf("machine-a", "machine-b")
        )

        panel.rebuild(level, isLevelCompleted = { true })

        assertEquals(4, panel.entries.size)
    }

    @Test
    fun `rebuild replaces previous entries`() {
        val level = levelWith(workers = listOf("worker-a"), machines = emptyList())
        panel.rebuild(level)
        assertEquals(1, panel.entries.size)

        panel.rebuild(levelWith(workers = emptyList(), machines = emptyList()))

        assertEquals(0, panel.entries.size)
    }

    @Test
    fun `toggleSelect selects unselected key`() {
        val level = levelWith(workers = listOf("worker-a"), machines = emptyList())
        panel.rebuild(level)
        val key = panel.entries.first().key

        panel.toggleSelect(key)

        assertEquals(key, panel.selectedKey)
    }

    @Test
    fun `toggleSelect deselects already-selected key`() {
        val level = levelWith(workers = listOf("worker-a"), machines = emptyList())
        panel.rebuild(level)
        val key = panel.entries.first().key
        panel.toggleSelect(key)

        panel.toggleSelect(key)

        assertNull(panel.selectedKey)
    }

    @Test
    fun `clearSelection removes the current selection`() {
        val level = levelWith(workers = listOf("worker-a"), machines = emptyList())
        panel.rebuild(level)
        val key = panel.entries.first().key
        panel.toggleSelect(key)

        panel.clearSelection()

        assertNull(panel.selectedKey)
    }

    private fun levelWith(workers: List<String>, machines: List<String>): LevelDefinition {
        return LevelDefinition(
            id = "test-level",
            shopAssetPath = "shops/test.json",
            starThresholds = LevelStarThresholds(5, 10, 15),
            availableWorkerIds = workers,
            availableMachineIds = machines
        )
    }

    private fun workerProfile(id: String, requiredLevels: List<String>): WorkerProfile {
        return WorkerProfile(
            id = id,
            level = 1,
            hireCost = 50,
            walkSpeed = 200f,
            skin = "skin_$id",
            roleProfiles = listOf(
                WorkerRoleProfile(role = WorkerRole.PRODUCER_OPERATOR, taskDurationSeconds = 1f, defectChance = 0f)
            ),
            requiredCompletedLevelIds = requiredLevels
        )
    }

    private fun machineSpec(id: String, requiredLevels: List<String>): MachineSpec {
        return MachineSpec(
            id = id,
            level = 1,
            type = MachineType.PRODUCER,
            manuality = Manuality.AUTOMATIC,
            skin = "skin_$id",
            installCost = 100,
            operationDurationSeconds = 1f,
            requiredCompletedLevelIds = requiredLevels,
            recipe = MachineRecipe(inputs = emptyList(), outputProductId = "product-a", durationSeconds = 1f, defectChance = 0f)
        )
    }
}
