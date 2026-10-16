package com.faultory.balance

import com.faultory.core.content.FileSystemContentSource
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OverlayApplierTest {
    private val base by lazy { FileSystemContentSource(TestAssets.root).load() }

    // --- SET op ---

    @Test
    fun `SET replaces a catalog worker field`() {
        val result = OverlayApplier.apply(base, listOf(
            PatchOperation(PatchOp.SET, "catalog/workers/target-intern/hireCost", JsonPrimitive(999))
        ))
        val worker = result.content.shopCatalog.workers.first { it.id == "target-intern" }
        assertEquals(999, worker.hireCost)
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun `SET replaces a nested roleProfile field`() {
        val result = OverlayApplier.apply(base, listOf(
            PatchOperation(
                PatchOp.SET,
                "catalog/workers/target-intern/roleProfiles/QA/detectionAccuracy",
                JsonPrimitive(0.9f)
            )
        ))
        val worker = result.content.shopCatalog.workers.first { it.id == "target-intern" }
        val qa = worker.roleProfiles.first { it.role.name == "QA" }
        assertEquals(0.9f, qa.detectionAccuracy!!, 0.001f)
    }

    @Test
    fun `SET replaces a machine recipe field`() {
        val result = OverlayApplier.apply(base, listOf(
            PatchOperation(
                PatchOp.SET,
                "catalog/machines/bench-assembler/recipe/defectChance",
                JsonPrimitive(0.05f)
            )
        ))
        val machine = result.content.shopCatalog.machines.first { it.id == "bench-assembler" }
        assertEquals(0.05f, machine.recipe!!.defectChance, 0.001f)
    }

    @Test
    fun `SET replaces a levels field`() {
        val result = OverlayApplier.apply(base, listOf(
            PatchOperation(PatchOp.SET, "levels/tutorial-shop/startingCash", JsonPrimitive(500))
        ))
        val level = result.content.levelCatalog.levels.first { it.id == "tutorial-shop" }
        assertEquals(500, level.startingCash)
    }

    @Test
    fun `SET replaces a blueprint field`() {
        val result = OverlayApplier.apply(base, listOf(
            PatchOperation(PatchOp.SET, "blueprint/tutorial-shop/shiftLengthSeconds", JsonPrimitive(240f))
        ))
        val level = result.content.levelCatalog.levels.first { it.id == "tutorial-shop" }
        val blueprint = result.content.blueprintFor(level)
        assertEquals(240f, blueprint.shiftLengthSeconds, 0.001f)
    }

    @Test
    fun `SET replaces a blueprint floorTuning field`() {
        val result = OverlayApplier.apply(base, listOf(
            PatchOperation(
                PatchOp.SET,
                "blueprint/tutorial-shop/floorTuning/conveyorSpeedTilesPerSecond",
                JsonPrimitive(5.0f)
            )
        ))
        val level = result.content.levelCatalog.levels.first { it.id == "tutorial-shop" }
        val blueprint = result.content.blueprintFor(level)
        assertEquals(5.0f, blueprint.floorTuning.conveyorSpeedTilesPerSecond, 0.001f)
    }

    // --- ADD op ---

    @Test
    fun `ADD increments a numeric field`() {
        val original = base.shopCatalog.workers.first { it.id == "target-intern" }.hireCost
        val result = OverlayApplier.apply(base, listOf(
            PatchOperation(PatchOp.ADD, "catalog/workers/target-intern/hireCost", JsonPrimitive(50))
        ))
        val worker = result.content.shopCatalog.workers.first { it.id == "target-intern" }
        assertEquals(original + 50, worker.hireCost)
    }

    // --- MUL op ---

    @Test
    fun `MUL scales a numeric field`() {
        val original = base.shopCatalog.workers.first { it.id == "target-intern" }.hireCost.toDouble()
        val result = OverlayApplier.apply(base, listOf(
            PatchOperation(PatchOp.MUL, "catalog/workers/target-intern/hireCost", JsonPrimitive(2.0))
        ))
        val worker = result.content.shopCatalog.workers.first { it.id == "target-intern" }
        assertEquals((original * 2).toInt(), worker.hireCost)
    }

    // --- UNSET op ---

    @Test
    fun `UNSET nulls a nullable field`() {
        val result = OverlayApplier.apply(base, listOf(
            PatchOperation(PatchOp.UNSET, "catalog/workers/target-intern/upgradeTree")
        ))
        val worker = result.content.shopCatalog.workers.first { it.id == "target-intern" }
        assertEquals(null, worker.upgradeTree)
    }

    // --- Multiple patches ---

    @Test
    fun `multiple patches on the same document apply in order`() {
        val result = OverlayApplier.apply(base, listOf(
            PatchOperation(PatchOp.SET, "catalog/workers/target-intern/hireCost", JsonPrimitive(200)),
            PatchOperation(PatchOp.ADD, "catalog/workers/target-intern/hireCost", JsonPrimitive(50))
        ))
        val worker = result.content.shopCatalog.workers.first { it.id == "target-intern" }
        assertEquals(250, worker.hireCost)
    }

    // --- Error paths ---

    @Test
    fun `unknown document throws OverlayException`() {
        assertFailsWith<OverlayException> {
            OverlayApplier.apply(base, listOf(PatchOperation(PatchOp.SET, "unknown/x/y", JsonPrimitive(1))))
        }.also { assertEquals("UNKNOWN_DOCUMENT", it.code) }
    }

    @Test
    fun `unknown worker id throws OverlayException`() {
        assertFailsWith<OverlayException> {
            OverlayApplier.apply(base, listOf(
                PatchOperation(PatchOp.SET, "catalog/workers/nonexistent/hireCost", JsonPrimitive(1))
            ))
        }.also { assertEquals("UNKNOWN_ELEMENT", it.code) }
    }

    @Test
    fun `unknown field key throws OverlayException`() {
        assertFailsWith<OverlayException> {
            OverlayApplier.apply(base, listOf(
                PatchOperation(PatchOp.SET, "catalog/workers/target-intern/nonExistentField", JsonPrimitive(1))
            ))
        }.also { assertEquals("UNKNOWN_KEY", it.code) }
    }

    @Test
    fun `blueprint geometry path throws READ_ONLY_PATH`() {
        assertFailsWith<OverlayException> {
            OverlayApplier.apply(base, listOf(
                PatchOperation(PatchOp.SET, "blueprint/tutorial-shop/floor/x", JsonPrimitive(1))
            ))
        }.also { assertEquals("READ_ONLY_PATH", it.code) }
    }

    @Test
    fun `ADD on non-numeric field throws NOT_NUMERIC`() {
        assertFailsWith<OverlayException> {
            OverlayApplier.apply(base, listOf(
                PatchOperation(PatchOp.ADD, "catalog/workers/target-intern/skin", JsonPrimitive(1))
            ))
        }.also { assertEquals("NOT_NUMERIC", it.code) }
    }

    // --- Range checks ---

    @Test
    fun `range warning when detectionAccuracy exceeds 1`() {
        val result = OverlayApplier.apply(base, listOf(
            PatchOperation(
                PatchOp.SET,
                "catalog/workers/target-intern/roleProfiles/QA/detectionAccuracy",
                JsonPrimitive(1.5f)
            )
        ))
        assertTrue(
            result.warnings.any { "detectionAccuracy" in it },
            "Expected detectionAccuracy warning; got: ${result.warnings}"
        )
    }

    @Test
    fun `range warning when starThresholds are decreasing`() {
        val result = OverlayApplier.apply(base, listOf(
            PatchOperation(PatchOp.SET, "levels/tutorial-shop/starThresholds/twoStar", JsonPrimitive(1))
        ))
        assertTrue(
            result.warnings.any { "starThresholds" in it },
            "Expected starThresholds warning; got: ${result.warnings}"
        )
    }
}
