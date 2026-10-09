package com.faultory.editor.ui.inspector

import com.faultory.core.content.BinaryUpgradeTree
import com.faultory.core.content.LevelDefinition
import com.faultory.core.content.MachineSpec
import com.faultory.core.content.ProductDefinition
import com.faultory.core.content.TileCorners
import com.faultory.core.content.TileDefinition
import com.faultory.core.content.WorkerProfile
import com.faultory.core.shop.ScenerySpec
import com.faultory.core.shop.ShopBlueprint
import com.faultory.core.shop.TerrainRegion
import com.faultory.core.shop.TilePin
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IdReferenceRegistryTest {

    private val machineSpecName = serializer<MachineSpec>().descriptor.serialName
    private val workerProfileName = serializer<WorkerProfile>().descriptor.serialName
    private val levelDefinitionName = serializer<LevelDefinition>().descriptor.serialName
    private val upgradeTreeName = serializer<BinaryUpgradeTree>().descriptor.serialName
    private val productDefinitionName = serializer<ProductDefinition>().descriptor.serialName

    @Test
    fun `MachineSpec productIds resolves to PRODUCT`() {
        assertEquals(
            CatalogType.PRODUCT,
            IdReferenceRegistry.lookup(listOf(machineSpecName), "productIds"),
        )
    }

    @Test
    fun `MachineSpec minimumOperatorWorkerIds resolves to WORKER`() {
        assertEquals(
            CatalogType.WORKER,
            IdReferenceRegistry.lookup(listOf(machineSpecName), "minimumOperatorWorkerIds"),
        )
    }

    @Test
    fun `LevelDefinition availableWorkerIds resolves to WORKER`() {
        assertEquals(
            CatalogType.WORKER,
            IdReferenceRegistry.lookup(listOf(levelDefinitionName), "availableWorkerIds"),
        )
    }

    @Test
    fun `LevelDefinition availableMachineIds resolves to MACHINE`() {
        assertEquals(
            CatalogType.MACHINE,
            IdReferenceRegistry.lookup(listOf(levelDefinitionName), "availableMachineIds"),
        )
    }

    @Test
    fun `BinaryUpgradeTree under WorkerProfile resolves to WORKER`() {
        val chain = listOf(workerProfileName, upgradeTreeName)
        assertEquals(CatalogType.WORKER, IdReferenceRegistry.lookup(chain, "leftUpgradeId"))
        assertEquals(CatalogType.WORKER, IdReferenceRegistry.lookup(chain, "rightUpgradeId"))
    }

    @Test
    fun `BinaryUpgradeTree under MachineSpec resolves to MACHINE`() {
        val chain = listOf(machineSpecName, upgradeTreeName)
        assertEquals(CatalogType.MACHINE, IdReferenceRegistry.lookup(chain, "leftUpgradeId"))
        assertEquals(CatalogType.MACHINE, IdReferenceRegistry.lookup(chain, "rightUpgradeId"))
    }

    @Test
    fun `unregistered fields return null`() {
        assertNull(IdReferenceRegistry.lookup(listOf(machineSpecName), "skin"))
        assertNull(IdReferenceRegistry.lookup(listOf(productDefinitionName), "id"))
        assertNull(IdReferenceRegistry.lookup(listOf(upgradeTreeName), "leftUpgradeId"))
    }

    @Test
    fun `scenery fields resolve through owner chains of any depth`() {
        val blueprint = serializer<ShopBlueprint>().descriptor.serialName
        val scenery = serializer<ScenerySpec>().descriptor.serialName
        val region = serializer<TerrainRegion>().descriptor.serialName
        val pin = serializer<TilePin>().descriptor.serialName
        val tile = serializer<TileDefinition>().descriptor.serialName
        val corners = serializer<TileCorners>().descriptor.serialName

        assertEquals(CatalogType.TERRAIN, IdReferenceRegistry.lookup(listOf(blueprint, scenery), "floorTerrain"))
        assertEquals(CatalogType.STYLE, IdReferenceRegistry.lookup(listOf(blueprint, scenery), "styleId"))
        assertEquals(CatalogType.TERRAIN, IdReferenceRegistry.lookup(listOf(blueprint, scenery, region), "terrain"))
        assertEquals(CatalogType.TILE, IdReferenceRegistry.lookup(listOf(blueprint, scenery, pin), "tileId"))
        assertEquals(CatalogType.TERRAIN, IdReferenceRegistry.lookup(listOf(tile, corners), "southWest"))
        assertNull(IdReferenceRegistry.lookup(listOf(scenery, region), "terrain"), "the chain must match from the root")
    }
}
