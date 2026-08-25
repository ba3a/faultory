package com.faultory.core.graphics

import com.faultory.core.content.MachineSpec
import com.faultory.core.content.MachineType
import com.faultory.core.content.Manuality
import com.faultory.core.content.ShopCatalog
import com.faultory.core.content.ProductDefinition
import com.faultory.core.content.WorkerProfile
import com.faultory.core.assets.AssetPaths
import com.faultory.core.shop.BeltNode
import com.faultory.core.shop.ConveyorBelt
import com.faultory.core.shop.ShopBlueprint
import kotlin.test.Test
import kotlin.test.assertEquals

class SkinReferencesTest {
    @Test
    fun `referencedSkinIds returns deduped sorted ids from workers machines and products`() {
        val catalog = ShopCatalog(
            workers = listOf(
                worker("a", "worker_alpha"),
                worker("b", "worker_beta"),
                worker("c", "worker_alpha")
            ),
            machines = listOf(
                machine("m1", "machine_zeta"),
                machine("m2", "machine_alpha")
            ),
            products = listOf(product("p1", "product_mug"), product("p2", "product_mug"))
        )

        val ids = SkinReferences.referencedSkinIds(catalog)

        assertEquals(
            listOf("machine_alpha", "machine_zeta", "product_mug", "worker_alpha", "worker_beta"),
            ids
        )
    }

    @Test
    fun `referencedSkinIds drops blank skin fields`() {
        val catalog = ShopCatalog(
            workers = listOf(worker("a", ""), worker("b", "   "), worker("c", "worker_x")),
            machines = listOf(machine("m1", "")),
            products = listOf(product("p1", ""))
        )

        assertEquals(listOf("worker_x"), SkinReferences.referencedSkinIds(catalog))
    }

    @Test
    fun `referencedSkinIds is empty when catalog has no workers or machines`() {
        val catalog = ShopCatalog(workers = emptyList(), machines = emptyList(), products = emptyList())
        assertEquals(emptyList(), SkinReferences.referencedSkinIds(catalog))
    }

    @Test
    fun `referencedBeltSkinIds falls back to the default skin and honours overrides`() {
        val blueprint = blueprint(belt("belt-a", null), belt("belt-b", "belt_brass"), belt("belt-c", "  "))

        assertEquals(
            listOf("belt_brass", AssetPaths.defaultBeltSkin),
            SkinReferences.referencedBeltSkinIds(blueprint)
        )
    }

    @Test
    fun `referencedBeltSkinIds is empty when a blueprint has no belts`() {
        assertEquals(emptyList(), SkinReferences.referencedBeltSkinIds(blueprint()))
    }

    private fun belt(id: String, skin: String?): ConveyorBelt = ConveyorBelt(
        id = id,
        checkpoints = listOf(BeltNode(200f, 200f), BeltNode(280f, 200f)),
        skin = skin
    )

    private fun blueprint(vararg belts: ConveyorBelt): ShopBlueprint = ShopBlueprint(
        id = "test",
        displayName = "Test",
        qualityThresholdPercent = 90f,
        shiftLengthSeconds = 60f,
        conveyorBelts = belts.toList(),
        machineSlots = emptyList(),
        workerSpawnPoints = emptyList()
    )

    private fun product(id: String, skin: String): ProductDefinition = ProductDefinition(
        id = id,
        saleValue = 1,
        skin = skin
    )

    private fun worker(id: String, skin: String): WorkerProfile = WorkerProfile(
        id = id,
        level = 1,
        hireCost = 0,
        walkSpeed = 1f,
        skin = skin,
        roleProfiles = emptyList()
    )

    private fun machine(id: String, skin: String): MachineSpec = MachineSpec(
        id = id,
        level = 1,
        type = MachineType.QA,
        manuality = Manuality.AUTOMATIC,
        skin = skin,
        installCost = 0,
        operationDurationSeconds = 1f
    )
}
