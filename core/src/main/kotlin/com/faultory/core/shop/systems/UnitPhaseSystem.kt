package com.faultory.core.shop.systems

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

    override val phase = SimulationPhase.ANIMATION

    override fun step(context: SystemContext) = update(context.deltaSeconds)

    fun update(deltaSeconds: Float) {
        for (index in access.placedObjects.indices) {
            val placed = access.placedObjects[index] as? PlacedShopObject.Worker ?: continue
            val animation = access.animationStateFor(placed.id)
            val phase = animation.unitPhase ?: continue
            advance(placed, animation, phase, deltaSeconds)
        }
    }

    private fun advance(
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
                    unitPhaseDurationSeconds = access.floorTuning.unitStandingSeconds
                )
            }
            UnitPhase.STANDING -> {
                access.updateAnimation(placed.id) {
                    it.copy(unitPhase = null, unitPhaseTimer = 0f, unitPhaseDurationSeconds = 0f)
                }
                events.publish { UnitStoodUpEvent(objectId = placed.id, tile = placed.position, levelId = it) }
            }
            UnitPhase.DESTROYING_PRODUCT -> completeDestroyProduct(placed)
        }
    }

    private fun completeDestroyProduct(placed: PlacedShopObject.Worker) {
        val productId = placed.carriedProductId
        if (productId != null) {
            val destroyed = access.removeProduct(productId)
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
        access.updateWorker(placed.id) { it.carriedProductId = null }
        access.updateAnimation(placed.id) {
            it.copy(unitPhase = null, unitPhaseTimer = 0f, unitPhaseDurationSeconds = 0f)
        }
    }

    private fun jitterLyingDuration(): Float {
        val jitter = if (access.floorTuning.unitLyingJitterSeconds > 0f) {
            (random.nextFloat() * 2f - 1f) * access.floorTuning.unitLyingJitterSeconds
        } else {
            0f
        }
        return (access.floorTuning.unitLyingBaseSeconds + jitter).coerceAtLeast(0.05f)
    }

    companion object {
        fun startFalling(
            workerId: String,
            objects: PlacedObjectWrites,
            animations: WorkerAnimationWrites,
            fallSeconds: Float
        ) {
            objects.updateWorker(workerId) {
                it.movementPath = emptyList()
                it.movementProgress = 0f
            } ?: return
            animations.updateAnimation(workerId) {
                it.copy(
                    unitPhase = UnitPhase.FALLING,
                    unitPhaseTimer = 0f,
                    unitPhaseDurationSeconds = fallSeconds
                )
            }
        }

        fun startDestroyProduct(
            workerId: String,
            objects: PlacedObjectWrites,
            animations: WorkerAnimationWrites,
            destroySeconds: Float
        ) {
            objects.updateWorker(workerId) {
                it.movementPath = emptyList()
                it.movementProgress = 0f
            } ?: return
            animations.updateAnimation(workerId) {
                it.copy(
                    unitPhase = UnitPhase.DESTROYING_PRODUCT,
                    unitPhaseTimer = 0f,
                    unitPhaseDurationSeconds = destroySeconds
                )
            }
        }
    }
}
