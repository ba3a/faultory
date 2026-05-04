package com.faultory.core.graphics

import com.faultory.core.content.MachineSpec
import com.faultory.core.content.MachineType
import com.faultory.core.content.Manuality
import com.faultory.core.content.ShopCatalog
import com.faultory.core.content.WorkerProfile
import kotlin.test.Test
import kotlin.test.assertEquals

class SkinReferencesTest {
    @Test
    fun `referencedSkinIds returns deduped sorted ids from workers and machines`() {
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
            products = emptyList()
        )

        val ids = SkinReferences.referencedSkinIds(catalog)

        assertEquals(
            listOf("machine_alpha", "machine_zeta", "worker_alpha", "worker_beta"),
            ids
        )
    }

    @Test
    fun `referencedSkinIds drops blank skin fields`() {
        val catalog = ShopCatalog(
            workers = listOf(worker("a", ""), worker("b", "   "), worker("c", "worker_x")),
            machines = listOf(machine("m1", "")),
            products = emptyList()
        )

        assertEquals(listOf("worker_x"), SkinReferences.referencedSkinIds(catalog))
    }

    @Test
    fun `referencedSkinIds is empty when catalog has no workers or machines`() {
        val catalog = ShopCatalog(workers = emptyList(), machines = emptyList(), products = emptyList())
        assertEquals(emptyList(), SkinReferences.referencedSkinIds(catalog))
    }

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
