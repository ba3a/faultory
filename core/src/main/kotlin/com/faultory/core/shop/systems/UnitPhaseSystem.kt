package com.faultory.core.shop.systems

import com.faultory.core.config.GameConfig
import com.faultory.core.encounters.ProductDestroyedEvent
import com.faultory.core.encounters.ShopFloorEvents
import com.faultory.core.encounters.UnitStoodUpEvent
import com.faultory.core.shop.PlacedShopObject
import com.faultory.core.shop.UnitPhase
import com.faultory.core.shop.WorkerAnimationState
import kotlin.random.Random

internal class UnitPhaseSystem(
    private val access: UnitPhaseAccess,
    private val random: Random,
    private val events: ShopFloorEvents = ShopFloorEvents()
) : SimulationSystem {
    private val mutablePlacedObjects get() = access.mutablePlacedObjects
    private val mutableActiveProducts get() = access.mutableActiveProducts

    override val phase = SimulationPhase.ANIMATION

    override fun step(context: SystemContext) = update(context.deltaSeconds)

    fun update(deltaSeconds: Float) {
        for (index in mutablePlacedObjects.indices) {
            val placed = mutablePlacedObjects[index] as? PlacedShopObject.Worker ?: continue
            val animation = access.animationStateFor(placed.id)
            val phase = animation.unitPhase ?: continue
            advance(index, placed, animation, phase, deltaSeconds)
        }
    }

    private fun advance(
        index: Int,
        placed: PlacedShopObject.Worker,
        animation: WorkerAnimationState,
        phase: UnitPhase,
        deltaSeconds: Float
    ) {
        val newTimer = animation.unitPhaseTimer + deltaSeconds
        if (newTimer < animation.unitPhaseDurationSeconds) {
            access.updateAnimation(placed.id) { it.copy(unitPhaseTimer = newTimer) }
            return
        }
        when (phase) {
            UnitPhase.FALLING -> access.updateAnimation(placed.id) {
                it.copy(
                    unitPhase = UnitPhase.LYING,
                    unitPhaseTimer = 0f,
                    unitPhaseDurationSeconds = jitterLyingDuration()
                )
            }
            UnitPhase.LYING -> access.updateAnimation(placed.id) {
                it.copy(
                    unitPhase = UnitPhase.STANDING,
                    unitPhaseTimer = 0f,
                    unitPhaseDurationSeconds = GameConfig.unitStandingSeconds
                )
            }
            UnitPhase.STANDING -> {
                access.updateAnimation(placed.id) {
                    it.copy(unitPhase = null, unitPhaseTimer = 0f, unitPhaseDurationSeconds = 0f)
                }
                events.publish { UnitStoodUpEvent(objectId = placed.id, tile = placed.position, levelId = it) }
            }
            UnitPhase.DESTROYING_PRODUCT -> completeDestroyProduct(index, placed)
        }
    }

    private fun completeDestroyProduct(index: Int, placed: PlacedShopObject.Worker) {
        val productId = placed.carriedProductId
        if (productId != null) {
            val destroyed = mutableActiveProducts.removeById(productId)
            if (destroyed != null) {
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
        mutablePlacedObjects[index] = placed.copy(carriedProductId = null)
        access.updateAnimation(placed.id) {
            it.copy(unitPhase = null, unitPhaseTimer = 0f, unitPhaseDurationSeconds = 0f)
        }
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
        fun startFalling(workerId: String, objects: PlacedObjectWrites, animations: WorkerAnimationWrites) {
            val worker = objects.mutablePlacedObjects.byId(workerId) as? PlacedShopObject.Worker ?: return
            objects.mutablePlacedObjects.replaceById(workerId) {
                worker.copy(movementPath = emptyList(), movementProgress = 0f)
            }
            animations.updateAnimation(workerId) {
                it.copy(
                    unitPhase = UnitPhase.FALLING,
                    unitPhaseTimer = 0f,
                    unitPhaseDurationSeconds = GameConfig.unitFallSeconds
                )
            }
        }

        fun startDestroyProduct(workerId: String, objects: PlacedObjectWrites, animations: WorkerAnimationWrites) {
            val worker = objects.mutablePlacedObjects.byId(workerId) as? PlacedShopObject.Worker ?: return
            objects.mutablePlacedObjects.replaceById(workerId) {
                worker.copy(movementPath = emptyList(), movementProgress = 0f)
            }
            animations.updateAnimation(workerId) {
                it.copy(
                    unitPhase = UnitPhase.DESTROYING_PRODUCT,
                    unitPhaseTimer = 0f,
                    unitPhaseDurationSeconds = GameConfig.cleanerDestroyProductSeconds
                )
            }
        }
    }
}
