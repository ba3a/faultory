package com.faultory.core.headless

import com.faultory.core.TestAssets
import com.faultory.core.content.FileSystemContentSource
import com.faultory.core.save.InMemorySaveRepository
import com.faultory.core.shop.PlacedShopObjectKind
import com.faultory.core.shop.TileCoordinate
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * The one invariant a headless run must keep, the same one [com.faultory.core.capture] keeps for
 * capture mode: it must never reach the player's real save slots. Unlike capture mode there is no
 * `SavePathResolver` call anywhere in `com.faultory.core.headless` to test against — isolation here
 * comes entirely from [HeadlessGame]'s default repositories being in-memory
 * ([InMemorySaveRepository] and [com.faultory.core.save.InMemoryEncounterProgressRepository]),
 * which this test locks.
 */
class HeadlessIsolationTest {
    @Test
    fun `a headless game defaults to in-memory save persistence`() {
        val content = FileSystemContentSource(TestAssets.root).load()

        assertIs<InMemorySaveRepository>(HeadlessGame(content).saveRepository)
    }

    @Test
    fun `a full run's autosaves land in the in-memory repository`() {
        val content = FileSystemContentSource(TestAssets.root).load()
        val saveRepository = InMemorySaveRepository()
        val game = HeadlessGame(content = content, saveRepository = saveRepository)
        val session = game.openLevel("tutorial-shop")

        session.buyAndPlace(PlacedShopObjectKind.MACHINE, "bench-assembler", TileCoordinate(10, 5))
        session.runToShiftEnd()

        assertNotNull(saveRepository.load("tutorial-shop"), "the shift's progress must reach the in-memory repository")
    }
}
