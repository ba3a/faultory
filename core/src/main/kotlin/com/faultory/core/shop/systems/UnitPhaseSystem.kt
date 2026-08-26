package com.faultory.core.shop.systems

import com.faultory.core.config.GameConfig
import com.faultory.core.encounters.ProductDestroyedEvent
import com.faultory.core.encounters.ShopFloorEvents
import com.faultory.core.encounters.UnitStoodUpEvent
import com.faultory.core.shop.PlacedShopObject
import com.faultory.core.shop.PlacedShopObjectKind
import com.faultory.core.shop.UnitPhase
import kotlin.random.Random

internal class UnitPhaseSystem(
    private val state: ShopFloorState,
    private val random: Random,
    private val events: ShopFloorEvents = ShopFloorEvents()
) {
    private val mutablePlacedObjects get() = state.mutablePlacedObjects
    private val mutableActiveProducts get() = state.mutableActiveProducts

    fun update(deltaSeconds: Float) {
        for (index in mutablePlacedObjects.indices) {
            val placed = mutablePlacedObjects[index]
            if (placed.kind != PlacedShopObjectKind.WORKER) continue
            val phase = placed.unitPhase ?: continue
            advance(index, placed, phase, deltaSeconds)
        }
    }

    private fun advance(index: Int, placed: PlacedShopObject, phase: UnitPhase, deltaSeconds: Float) {
        val newTimer = placed.unitPhaseTimer + deltaSeconds
        if (newTimer < placed.unitPhaseDurationSeconds) {
            mutablePlacedObjects[index] = placed.copy(unitPhaseTimer = newTimer)
            return
        }
        when (phase) {
            UnitPhase.FALLING -> mutablePlacedObjects[index] = placed.copy(
                unitPhase = UnitPhase.LYING,
                unitPhaseTimer = 0f,
                unitPhaseDurationSeconds = jitterLyingDuration()
            )
            UnitPhase.LYING -> mutablePlacedObjects[index] = placed.copy(
                unitPhase = UnitPhase.STANDING,
                unitPhaseTimer = 0f,
                unitPhaseDurationSeconds = GameConfig.unitStandingSeconds
            )
            UnitPhase.STANDING -> {
                mutablePlacedObjects[index] = placed.copy(
                    unitPhase = null,
                    unitPhaseTimer = 0f,
                    unitPhaseDurationSeconds = 0f
                )
                events.publish { UnitStoodUpEvent(objectId = placed.id, tile = placed.position, levelId = it) }
            }
            UnitPhase.DESTROYING_PRODUCT -> completeDestroyProduct(index, placed)
        }
    }

    private fun completeDestroyProduct(index: Int, placed: PlacedShopObject) {
        val productId = placed.carriedProductId
        if (productId != null) {
            val productIndex = mutableActiveProducts.indexOfFirst { it.id == productId }
            if (productIndex >= 0) {
                val destroyed = mutableActiveProducts[productIndex]
                mutableActiveProducts.removeAt(productIndex)
                events.publish {
                    ProductDestroyedEvent(
                        objectId = placed.id,
                        productInstanceId = destroyed.id,
                        productId = destroyed.productId,
                        faultReason = destroyed.faultReason,
                        levelId = it
                    )
                }
            }
        }
        mutablePlacedObjects[index] = placed.copy(
            carriedProductId = null,
            unitPhase = null,
            unitPhaseTimer = 0f,
            unitPhaseDurationSeconds = 0f
        )
    }

    private fun jitterLyingDuration(): Float {
        val jitter = if (GameConfig.unitLyingJitterSeconds > 0f) {
            (random.nextFloat() * 2f - 1f) * GameConfig.unitLyingJitterSeconds
        } else {
            0f
        }
        return (GameConfig.unitLyingBaseSeconds + jitter).coerceAtLeast(0.05f)
    }

    companion object {
        fun startFalling(placed: PlacedShopObject): PlacedShopObject = placed.copy(
            movementPath = emptyList(),
            movementProgress = 0f,
            unitPhase = UnitPhase.FALLING,
            unitPhaseTimer = 0f,
            unitPhaseDurationSeconds = GameConfig.unitFallSeconds
        )

        fun startDestroyProduct(placed: PlacedShopObject): PlacedShopObject = placed.copy(
            movementPath = emptyList(),
            movementProgress = 0f,
            unitPhase = UnitPhase.DESTROYING_PRODUCT,
            unitPhaseTimer = 0f,
            unitPhaseDurationSeconds = GameConfig.cleanerDestroyProductSeconds
        )
    }
}
