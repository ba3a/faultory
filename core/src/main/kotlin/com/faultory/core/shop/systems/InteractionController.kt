package com.faultory.core.shop.systems

import com.faultory.core.encounters.InteractionAbandonedEvent
import com.faultory.core.encounters.InteractionCompletedEvent
import com.faultory.core.encounters.InteractionStartedEvent
import com.faultory.core.encounters.ProductHandedOverEvent
import com.faultory.core.encounters.ShopFloorEvents
import com.faultory.core.graphics.InteractionCatalog
import com.faultory.core.shop.ActiveInteraction
import com.faultory.core.shop.InteractionRole
import com.faultory.core.shop.Orientation
import com.faultory.core.shop.PlacedShopObject
import com.faultory.core.shop.ShopProductState

/**
 * Lets another system start a two-actor interaction without depending on [InteractionSystem].
 * [CleanerSystem] asks for this, not the whole system.
 */
internal interface InteractionStarter {
    /**
     * Pairs two objects into an interaction, facing each other and standing still for its duration.
     * Returns false when either side is already busy, so the caller can fall back rather than stack
     * two interactions onto one worker.
     */
    fun begin(
        definitionId: String,
        initiatorId: String,
        recipientId: String,
        payloadProductId: String?
    ): Boolean
}

/**
 * Runs in-flight two-actor interactions: ticks both sides, moves the payload at the authored
 * transfer point, and clears the pairing when the clip ends — plus [begin], the entry point other
 * systems use to start one.
 *
 * Interactions used to be instantaneous — a handoff swapped [PlacedShopObject.Worker.carriedProductId]
 * between two workers within a single frame, leaving nothing for the renderer to show. Giving them a
 * duration is what makes a give/take animation possible at all, and centralising the bookkeeping
 * here is what stops each new interaction adding another field and another resolver branch.
 *
 * A missing catalog is not fatal: interactions then complete on their fallback duration and the
 * payload still changes hands, so gameplay never depends on the presentation asset loading.
 *
 * [InteractionSystem] is the thin [SimulationSystem] that calls [advanceAll] once per frame.
 */
internal class InteractionController(
    private val objects: PlacedObjectWrites,
    private val products: ProductWrites,
    private val animations: WorkerAnimationWrites,
    private val catalogProvider: () -> InteractionCatalog?,
    private val events: ShopFloorEvents = ShopFloorEvents()
) : InteractionStarter {
    private val mutablePlacedObjects get() = objects.mutablePlacedObjects

    fun advanceAll(deltaSeconds: Float) {
        val noneInteracting = mutablePlacedObjects.none {
            it is PlacedShopObject.Worker && animations.animationStateFor(it.id).interaction != null
        }
        if (noneInteracting) {
            return
        }

        for (index in mutablePlacedObjects.indices) {
            val placed = mutablePlacedObjects[index] as? PlacedShopObject.Worker ?: continue
            val interaction = animations.animationStateFor(placed.id).interaction ?: continue
            // The partner drives release: if it vanished mid-shift - removed between shifts, or
            // destroyed - the survivor would otherwise hold a payload nobody can take.
            if (objects.findObjectById(interaction.partnerObjectId) == null) {
                releaseAbandoned(placed, interaction)
            } else {
                advance(placed, interaction, deltaSeconds)
            }
        }
    }

    private fun releaseAbandoned(placed: PlacedShopObject.Worker, interaction: ActiveInteraction) {
        animations.updateAnimation(placed.id) { it.copy(interaction = null) }
        events.publish {
            InteractionAbandonedEvent(
                definitionId = interaction.definitionId,
                objectId = placed.id,
                partnerObjectId = interaction.partnerObjectId,
                levelId = it
            )
        }
    }

    private fun advance(
        placed: PlacedShopObject.Worker,
        interaction: ActiveInteraction,
        deltaSeconds: Float
    ) {
        val ticked = interaction.copy(elapsedSeconds = interaction.elapsedSeconds + deltaSeconds)

        // Only the giver moves the payload, so the transfer runs exactly once even though both
        // sides tick their own clock.
        val transferred = if (ticked.role == InteractionRole.INITIATOR && ticked.isDueToTransfer) {
            transferPayload(placed, ticked)
            true
        } else {
            ticked.payloadTransferred
        }

        if (ticked.isComplete) {
            animations.updateAnimation(placed.id) { it.copy(interaction = null) }
        } else {
            animations.updateAnimation(placed.id) {
                it.copy(interaction = ticked.copy(payloadTransferred = transferred))
            }
        }

        // Both sides tick their own clock, so - as with the transfer above - only the giver
        // reports, and the interaction is one event rather than two.
        if (ticked.isComplete && ticked.role == InteractionRole.INITIATOR) {
            events.publish {
                InteractionCompletedEvent(
                    definitionId = ticked.definitionId,
                    objectId = placed.id,
                    partnerObjectId = ticked.partnerObjectId,
                    levelId = it
                )
            }
        }
    }

    private fun transferPayload(giver: PlacedShopObject.Worker, interaction: ActiveInteraction) {
        val productId = interaction.payloadProductId ?: return
        val takerIndex = mutablePlacedObjects.indexOfId(interaction.partnerObjectId)
        if (takerIndex < 0) {
            return
        }
        val giverIndex = mutablePlacedObjects.indexOfId(giver.id)
        if (giverIndex < 0) {
            return
        }

        val handed = products.updateProduct(productId) {
            it.state = ShopProductState.CARRIED
            it.tile = null
            it.carrierWorkerId = interaction.partnerObjectId
            it.holderObjectId = interaction.partnerObjectId
        }

        val taker = mutablePlacedObjects[takerIndex] as? PlacedShopObject.Worker ?: return
        mutablePlacedObjects[takerIndex] = taker.copy(carriedProductId = productId)
        mutablePlacedObjects[giverIndex] = (mutablePlacedObjects[giverIndex] as PlacedShopObject.Worker)
            .copy(carriedProductId = null)

        // Reported here rather than where the interaction was requested: this is the frame the
        // product actually changes hands, partway through the clip.
        if (handed != null) {
            events.publish {
                ProductHandedOverEvent(
                    objectId = giver.id,
                    giverRole = giver.workerRole,
                    recipientObjectId = taker.id,
                    recipientRole = taker.workerRole,
                    productInstanceId = handed.id,
                    productId = handed.productId,
                    levelId = it
                )
            }
        }
    }

    override fun begin(
        definitionId: String,
        initiatorId: String,
        recipientId: String,
        payloadProductId: String?
    ): Boolean {
        val initiatorIndex = mutablePlacedObjects.indexOfId(initiatorId)
        val recipientIndex = mutablePlacedObjects.indexOfId(recipientId)
        if (initiatorIndex < 0 || recipientIndex < 0 || initiatorIndex == recipientIndex) {
            return false
        }
        val initiator = mutablePlacedObjects[initiatorIndex] as? PlacedShopObject.Worker ?: return false
        val recipient = mutablePlacedObjects[recipientIndex] as? PlacedShopObject.Worker ?: return false
        if (animations.animationStateFor(initiatorId).interaction != null ||
            animations.animationStateFor(recipientId).interaction != null
        ) {
            return false
        }

        val definition = catalogProvider()?.find(definitionId)
        val duration = definition?.durationSeconds ?: FALLBACK_DURATION_SECONDS
        val transfer = definition?.transferSeconds ?: (duration * DEFAULT_TRANSFER_FRACTION)
        val facing = Orientation.between(initiator.position, recipient.position)

        mutablePlacedObjects[initiatorIndex] = initiator.copy(
            movementPath = emptyList(),
            movementProgress = 0f,
            orientation = facing ?: initiator.orientation
        )
        mutablePlacedObjects[recipientIndex] = recipient.copy(
            movementPath = emptyList(),
            movementProgress = 0f,
            orientation = facing?.opposite() ?: recipient.orientation
        )
        val initiatorSide = ActiveInteraction(
            definitionId = definitionId,
            partnerObjectId = recipientId,
            role = InteractionRole.INITIATOR,
            payloadProductId = payloadProductId,
            durationSeconds = duration,
            transferSeconds = transfer
        )
        animations.updateAnimation(initiatorId) { it.copy(interaction = initiatorSide) }
        animations.updateAnimation(recipientId) {
            it.copy(interaction = initiatorSide.copy(partnerObjectId = initiatorId, role = InteractionRole.RECIPIENT))
        }
        events.publish {
            InteractionStartedEvent(
                definitionId = definitionId,
                objectId = initiatorId,
                partnerObjectId = recipientId,
                payloadProductId = payloadProductId,
                levelId = it
            )
        }
        return true
    }

    companion object {
        /** Used when the catalog has not loaded, so gameplay never waits on a presentation asset. */
        const val FALLBACK_DURATION_SECONDS = 0.6f
        private const val DEFAULT_TRANSFER_FRACTION = 0.5f
    }
}
