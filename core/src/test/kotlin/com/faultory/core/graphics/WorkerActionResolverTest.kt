package com.faultory.core.graphics

import com.faultory.core.shop.Orientation
import com.faultory.core.shop.PlacedShopObject
import com.faultory.core.shop.PlacedShopObjectKind
import com.faultory.core.shop.TileCoordinate
import kotlin.test.Test
import kotlin.test.assertEquals

class WorkerActionResolverTest {
    @Test
    fun `action is walk while worker is moving along a path`() {
        val resolver = WorkerActionResolver()

        assertEquals(
            SkinActions.WALK,
            resolver.actionFor(
                worker(
                    movementPath = listOf(TileCoordinate(6, 5)),
                    movementProgress = 0.4f
                )
            )
        )
    }

    @Test
    fun `action is idle when worker has no remaining movement`() {
        val resolver = WorkerActionResolver()

        assertEquals(
            SkinActions.IDLE,
            resolver.actionFor(
                worker(
                    movementPath = listOf(TileCoordinate(6, 5)),
                    movementProgress = 1f
                )
            )
        )
    }

    @Test
    fun `orientation follows the vector toward the next path tile`() {
        val resolver = WorkerActionResolver()

        assertEquals(
            Orientation.EAST,
            resolver.orientationFor(
                worker(
                    orientation = Orientation.SOUTH,
                    movementPath = listOf(TileCoordinate(6, 5))
                )
            )
        )
    }

    @Test
    fun `orientation falls back to placed orientation when path is empty`() {
        val resolver = WorkerActionResolver()

        assertEquals(
            Orientation.WEST,
            resolver.orientationFor(
                worker(
                    orientation = Orientation.WEST,
                    movementPath = emptyList()
                )
            )
        )
    }

    private fun worker(
        orientation: Orientation = Orientation.SOUTH,
        movementPath: List<TileCoordinate>,
        movementProgress: Float = 0f
    ): PlacedShopObject {
        return PlacedShopObject(
            id = "worker-1",
            catalogId = "line-inspector",
            kind = PlacedShopObjectKind.WORKER,
            position = TileCoordinate(5, 5),
            orientation = orientation,
            movementPath = movementPath,
            movementProgress = movementProgress
        )
    }
}
