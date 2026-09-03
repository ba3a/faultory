package com.faultory.core.graphics

import com.faultory.core.shop.BeltTileShape
import com.faultory.core.shop.ProductFaultReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [SpriteAction] is the single source the per-kind catalog lists and the [SkinFrameResolver]
 * stand-in chain derive from, so this pins the table itself: unique ids, resolvable stand-ins, and
 * the exact per-kind lists the editor and `SkinActionCatalogTest` depend on.
 */
class SpriteActionTest {
    @Test
    fun `every action id is unique`() {
        val ids = SpriteAction.entries.map { it.id }
        assertEquals(ids.distinct(), ids)
    }

    @Test
    fun `every stand-in names a real action a stand-in could actually substitute for`() {
        SpriteAction.entries.forEach { action ->
            val standInId = action.standInId ?: return@forEach
            val standIn = SpriteAction.entries.firstOrNull { it.id == standInId }
            assertNotNull(standIn, "${action.id} stands in on the unknown action '$standInId'")
            assertTrue(
                (action.kinds intersect standIn.kinds).isNotEmpty(),
                "${action.id} borrows ${standIn.id}, which no shared kind can author"
            )
        }
    }

    @Test
    fun `each kind's list leads with idle and has no duplicates`() {
        SpriteKind.entries.forEach { kind ->
            val ids = SpriteAction.idsFor(kind)
            assertEquals(SpriteAction.IDLE.id, ids.first(), "$kind must lead with idle")
            assertEquals(ids.distinct(), ids, "$kind has duplicate actions")
        }
    }

    @Test
    fun `the per-kind lists are exactly what the catalog and editor expect`() {
        assertEquals(
            listOf(
                "idle", "walk", "belt_enter", "belt_ride", "belt_exit",
                "pursue", "fall", "lie", "stand_up", "destroy"
            ),
            SpriteAction.idsFor(SpriteKind.WORKER)
        )
        assertEquals(
            listOf("idle", "working", "inspect", "blocked"),
            SpriteAction.idsFor(SpriteKind.MACHINE)
        )
        assertEquals(
            listOf(
                "idle", "producing", "on_belt", "carried",
                "inspected", "destroying", "fault_defect", "fault_sabotage"
            ),
            SpriteAction.idsFor(SpriteKind.PRODUCT)
        )
        assertEquals(
            listOf("idle", "straight", "turn_cw", "turn_ccw", "start", "end"),
            SpriteAction.idsFor(SpriteKind.BELT)
        )
    }

    @Test
    fun `the derived catalog and fallback map agree with the table`() {
        assertEquals(SpriteAction.idsFor(SpriteKind.WORKER), SkinActionCatalog.worker)
        assertEquals(SpriteAction.idsFor(SpriteKind.BELT), SkinActionCatalog.belt)
        assertEquals(
            mapOf(
                "belt_enter" to listOf("belt_ride"),
                "belt_exit" to listOf("belt_ride"),
                "pursue" to listOf("walk"),
                "fall" to listOf("lie")
            ),
            SpriteAction.standIns
        )
    }

    @Test
    fun `fault overlays and belt shapes map to product and belt actions`() {
        assertEquals(SpriteAction.FAULT_DEFECT, SpriteAction.faultOverlayFor(ProductFaultReason.PRODUCTION_DEFECT))
        assertEquals(SpriteAction.FAULT_SABOTAGE, SpriteAction.faultOverlayFor(ProductFaultReason.SABOTAGE))
        assertEquals(null, SpriteAction.faultOverlayFor(null))

        BeltTileShape.entries.forEach { shape ->
            assertTrue(SpriteAction.forBeltShape(shape).id in SkinActionCatalog.belt)
        }
    }
}
