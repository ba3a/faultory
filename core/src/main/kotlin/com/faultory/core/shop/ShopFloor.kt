package com.faultory.core.shop

import com.badlogic.gdx.utils.Disposable
import com.faultory.core.shop.physics.ShopPhysics

class ShopFloor(
    val blueprint: ShopBlueprint,
    initialPlacements: List<PlacedShopObject> = emptyList(),
    private val physics: ShopPhysics = ShopPhysics()
) : Disposable {
    val grid = ShopGrid(blueprint)

    var elapsedSeconds: Float = 0f
        private set

    private val mutablePlacedObjects = initialPlacements.toMutableList()

    val placedObjects: List<PlacedShopObject>
        get() = mutablePlacedObjects

    fun update(deltaSeconds: Float) {
        elapsedSeconds += deltaSeconds
        physics.step(deltaSeconds)
    }

    fun isOccupied(tile: TileCoordinate): Boolean {
        return mutablePlacedObjects.any { it.position == tile }
    }

    fun placeObject(placedObject: PlacedShopObject): Boolean {
        if (!grid.isBuildable(placedObject.position) || isOccupied(placedObject.position)) {
            return false
        }

        mutablePlacedObjects += placedObject
        return true
    }

    override fun dispose() {
    }
}
