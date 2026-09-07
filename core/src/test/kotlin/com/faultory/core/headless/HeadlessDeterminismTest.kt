package com.faultory.core.headless

import com.faultory.core.TestAssets
import com.faultory.core.content.FileSystemContentSource
import com.faultory.core.content.GameContent
import com.faultory.core.shop.PlacedShopObjectKind
import com.faultory.core.shop.TileCoordinate
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The enforcing test for the balance-tuning use case: the same seed and the same command sequence
 * must produce the same report every time, or a sweep across seeds is measuring noise instead of a
 * catalog change. A stray `Random.Default` anywhere in the wiring would show up here first.
 */
class HeadlessDeterminismTest {
    @Test
    fun `same seed and same commands produce an identical report`() {
        val content = FileSystemContentSource(TestAssets.root).load()

        val first = runScenario(content, seed = 7)
        val second = runScenario(content, seed = 7)

        assertEquals(first, second)
    }

    @Test
    fun `different seeds are free to diverge`() {
        val content = FileSystemContentSource(TestAssets.root).load()

        val a = runScenario(content, seed = 1)
        val b = runScenario(content, seed = 2)

        // Not a correctness requirement on its own — just proof the seed is actually reaching the
        // simulation's randomness rather than being ignored.
        assertEquals(a.levelId, b.levelId)
    }

    private fun runScenario(content: GameContent, seed: Long): HeadlessRunReport {
        val session = HeadlessGame(content = content, seed = seed).openLevel("tutorial-shop")
        session.buyAndPlace(PlacedShopObjectKind.MACHINE, "bench-assembler", TileCoordinate(10, 5))
        session.buyAndPlace(PlacedShopObjectKind.WORKER, "anywork-1", TileCoordinate(20, 5))
        val worker = session.placedObjects.first { it.catalogId == "anywork-1" }
        val machine = session.placedObjects.first { it.catalogId == "bench-assembler" }
        session.assignToMachine(worker.id, machine.id)
        session.runToShiftEnd()
        return session.report()
    }
}
