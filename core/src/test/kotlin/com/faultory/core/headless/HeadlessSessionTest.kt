package com.faultory.core.headless

import com.faultory.core.TestAssets
import com.faultory.core.content.FileSystemContentSource
import com.faultory.core.shop.PlacedShopObjectKind
import com.faultory.core.shop.PurchaseResult
import com.faultory.core.shop.TileCoordinate
import com.faultory.core.shop.WorkerAssignmentResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * End-to-end: real `assets/` content, no screen, the same purchase/assign/tick path a player's
 * clicks reach. This is the scenario a balance-tuning driver runs.
 */
class HeadlessSessionTest {
    @Test
    fun `buys, places, assigns and runs a full shift against the real tutorial-shop content`() {
        val content = FileSystemContentSource(TestAssets.root).load()
        val game = HeadlessGame(content = content, seed = 42)
        val session = game.openLevel("tutorial-shop")

        assertTrue(session.offers().isNotEmpty(), "tutorial-shop should offer purchasable workers and machines")

        // Placed off the belt path (which runs through tile column 10 only for y in [7,13]) so the
        // machine's footprint and its operator access tile are both clear ground.
        val machinePlacement =
            session.buyAndPlace(PlacedShopObjectKind.MACHINE, "bench-assembler", TileCoordinate(10, 5))
        val machine = assertIs<PurchaseResult.Success>(machinePlacement).placedObject

        val workerPlacement = session.buyAndPlace(PlacedShopObjectKind.WORKER, "anywork-1", TileCoordinate(20, 5))
        val worker = assertIs<PurchaseResult.Success>(workerPlacement).placedObject

        assertEquals(320 - 75 - 85, session.cash, "starting cash minus the machine's and worker's catalog cost")

        val assignment = session.assignToMachine(worker.id, machine.id)
        assertIs<WorkerAssignmentResult.Success>(assignment)

        session.runToShiftEnd()

        assertTrue(session.isShiftEnded)
        assertEquals(180f, session.elapsedSeconds)

        val report = session.report()
        assertEquals("tutorial-shop", report.levelId)
        assertEquals(42L, report.seed)
        assertEquals(
            report.goodProductsDelivered + report.faultyProductsDelivered,
            report.productDeliveryStats.sumOf { it.totalCount }
        )
        assertTrue(
            report.goodProductsDelivered + report.faultyProductsDelivered > 0,
            "a full 180s shift with an assigned producer should deliver at least one product"
        )
        assertEquals(report.starsEarned > 0, report.passed)
    }
}
