package com.faultory.editor.ui.inspector.animations

import com.faultory.core.assets.AssetPaths
import com.faultory.core.content.MachineSpec
import com.faultory.core.content.MachineType
import com.faultory.core.content.Manuality
import com.faultory.core.content.ProductDefinition
import com.faultory.core.content.ShopCatalog
import com.faultory.core.content.WorkerProfile
import com.faultory.core.graphics.InteractionCatalog
import com.faultory.core.graphics.InteractionDefinition
import com.faultory.core.graphics.SkinActionCatalog
import com.faultory.core.shop.BeltNode
import com.faultory.core.shop.ConveyorBelt
import com.faultory.core.shop.ShopBlueprint
import com.faultory.editor.ui.tree.AssetSelection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnimationTargetsTest {
    private val catalog = ShopCatalog(
        workers = listOf(worker("inspector", "worker_line_inspector")),
        machines = listOf(machine("bench", "machine_bench_assembler")),
        products = listOf(
            product("ceramic-mug", "product_ceramic_mug"),
            product("glass-jar", ""),
        ),
    )

    private val blueprints = mapOf(
        "shops/one-belt.json" to blueprint(belt("belt-a", null)),
        "shops/three-belts.json" to blueprint(
            belt("belt-a", null),
            belt("belt-b", "belt_brass"),
            belt("belt-c", "  "),
        ),
    )

    @Test
    fun `a product offers the full product action set`() {
        val targets = targetsFor(AssetSelection.Product("ceramic-mug"))

        assertEquals(1, targets.size)
        assertEquals("product_ceramic_mug", targets.single().skinId)
        assertEquals(SkinActionCatalog.product, targets.single().actions)
    }

    @Test
    fun `a product with no skin yet offers nothing to author`() {
        assertTrue(targetsFor(AssetSelection.Product("glass-jar")).isEmpty())
    }

    @Test
    fun `a blueprint offers belt actions under the default skin`() {
        val targets = targetsFor(AssetSelection.Blueprint("shops/one-belt.json"))

        assertEquals(1, targets.size)
        assertEquals(AssetPaths.defaultBeltSkin, targets.single().skinId)
        assertEquals(SkinActionCatalog.belt, targets.single().actions)
    }

    @Test
    fun `belts sharing a skin collapse into one grid and overrides get their own`() {
        val targets = targetsFor(AssetSelection.Blueprint("shops/three-belts.json"))

        assertEquals(
            listOf(AssetPaths.defaultBeltSkin, "belt_brass"),
            targets.map { it.skinId },
        )
        assertEquals("Animations - ${AssetPaths.defaultBeltSkin} (belt-a, belt-c)", targets.first().heading)
    }

    @Test
    fun `workers and machines keep their own action sets`() {
        assertEquals(SkinActionCatalog.worker, targetsFor(AssetSelection.Worker("inspector")).single().actions)
        assertEquals(SkinActionCatalog.machine, targetsFor(AssetSelection.Machine("bench")).single().actions)
    }

    @Test
    fun `a worker can author both halves of every authored interaction`() {
        val interactions = InteractionCatalog(
            interactions = listOf(
                InteractionDefinition(
                    id = "hand_off",
                    initiatorAction = "hand_off_give",
                    recipientAction = "hand_off_take",
                    durationSeconds = 0.6f,
                ),
            ),
        )

        val actions = AnimationTargets
            .forSelection(AssetSelection.Worker("inspector"), catalog, blueprints, interactions)
            .single()
            .actions

        assertTrue(actions.containsAll(SkinActionCatalog.worker))
        assertTrue(actions.containsAll(listOf("hand_off_give", "hand_off_take")))
    }

    @Test
    fun `interaction clips do not leak onto machines products or belts`() {
        val interactions = InteractionCatalog(
            interactions = listOf(InteractionDefinition("hand_off", "give", "take", 0.6f)),
        )

        listOf(
            AssetSelection.Machine("bench"),
            AssetSelection.Product("ceramic-mug"),
            AssetSelection.Blueprint("shops/one-belt.json"),
        ).forEach { selection ->
            val actions = AnimationTargets.forSelection(selection, catalog, blueprints, interactions)
                .single()
                .actions
            assertTrue(actions.none { it == "give" || it == "take" }, "leaked into $selection")
        }
    }

    @Test
    fun `unknown assets and levels offer nothing`() {
        assertTrue(targetsFor(AssetSelection.Product("missing")).isEmpty())
        assertTrue(targetsFor(AssetSelection.Blueprint("shops/missing.json")).isEmpty())
        assertTrue(targetsFor(AssetSelection.Level("tutorial-shop")).isEmpty())
    }

    private fun targetsFor(selection: AssetSelection) =
        AnimationTargets.forSelection(selection, catalog, blueprints)

    private fun belt(id: String, skin: String?) = ConveyorBelt(
        id = id,
        checkpoints = listOf(BeltNode(200f, 200f), BeltNode(280f, 200f)),
        skin = skin,
    )

    private fun blueprint(vararg belts: ConveyorBelt) = ShopBlueprint(
        id = "test",
        displayName = "Test",
        qualityThresholdPercent = 90f,
        shiftLengthSeconds = 60f,
        conveyorBelts = belts.toList(),
        machineSlots = emptyList(),
        workerSpawnPoints = emptyList(),
    )

    private fun product(id: String, skin: String) = ProductDefinition(id = id, saleValue = 1, skin = skin)

    private fun worker(id: String, skin: String) = WorkerProfile(
        id = id,
        level = 1,
        hireCost = 0,
        walkSpeed = 1f,
        skin = skin,
        roleProfiles = emptyList(),
    )

    private fun machine(id: String, skin: String) = MachineSpec(
        id = id,
        level = 1,
        type = MachineType.QA,
        manuality = Manuality.AUTOMATIC,
        skin = skin,
        installCost = 0,
        operationDurationSeconds = 1f,
    )
}
