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
 * Runs in-flight two-actor interactions: ticks both sides, moves the payload at the authored
 * transfer point, and clears the pairing when the clip ends.
 *
 * Interactions used to be instantaneous — a handoff swapped [PlacedShopObject.carriedProductId]
 * between two workers within a single frame, leaving nothing for the renderer to show. Giving them
 * a duration is what makes a give/take animation possible at all, and centralising the bookkeeping
 * here is what stops each new interaction adding another field and another resolver branch.
 *
 * A missing catalog is not fatal: interactions then complete on their fallback duration and the
 * payload still changes hands, so gameplay never depends on the presentation asset loading.
 */
internal class InteractionSystem(
    private val state: ShopFloorState,
    private val catalogProvider: () -> InteractionCatalog?,
    private val events: ShopFloorEvents = ShopFloorEvents()
) : SimulationSystem {
    private val mutablePlacedObjects get() = state.mutablePlacedObjects
    private val mutableActiveProducts get() = state.mutableActiveProducts

    override val phase = SimulationPhase.ANIMATION

    override fun step(context: SystemContext) = update(context.deltaSeconds)

    fun update(deltaSeconds: Float) {
        if (mutablePlacedObjects.none { it.interaction != null }) {
            return
        }

        for (index in mutablePlacedObjects.indices) {
            val placed = mutablePlacedObjects[index]
            val interaction = placed.interaction ?: continue

            // The partner drives release: if it vanished mid-shift - removed between shifts, or
            // destroyed - the survivor would otherwise hold a payload nobody can take.
            if (state.findObjectById(interaction.partnerObjectId) == null) {
                mutablePlacedObjects[index] = placed.copy(interaction = null)
                events.publish {
                    InteractionAbandonedEvent(
                        definitionId = interaction.definitionId,
                        objectId = placed.id,
                        partnerObjectId = interaction.partnerObjectId,
                        levelId = it
                    )
                }
                continue
            }

            advance(index, placed, interaction, deltaSeconds)
        }
    }

    private fun advance(
        index: Int,
        placed: PlacedShopObject,
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

        mutablePlacedObjects[index] = if (ticked.isComplete) {
            mutablePlacedObjects[index].copy(interaction = null)
        } else {
            mutablePlacedObjects[index].copy(interaction = ticked.copy(payloadTransferred = transferred))
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

    private fun transferPayload(giver: PlacedShopObject, interaction: ActiveInteraction) {
        val productId = interaction.payloadProductId ?: return
        val takerIndex = mutablePlacedObjects.indexOfFirst { it.id == interaction.partnerObjectId }
        if (takerIndex < 0) {
            return
        }
        val giverIndex = mutablePlacedObjects.indexOfFirst { it.id == giver.id }
        if (giverIndex < 0) {
            return
        }

        val productIndex = mutableActiveProducts.indexOfFirst { it.id == productId }
        if (productIndex >= 0) {
            mutableActiveProducts[productIndex] = mutableActiveProducts[productIndex].copy(
                state = ShopProductState.CARRIED,
                tile = null,
                carrierWorkerId = interaction.partnerObjectId,
                holderObjectId = interaction.partnerObjectId
            )
        }

        val taker = mutablePlacedObjects[takerIndex]
        mutablePlacedObjects[takerIndex] = taker.copy(carriedProductId = productId)
        mutablePlacedObjects[giverIndex] = mutablePlacedObjects[giverIndex].copy(carriedProductId = null)

        // Reported here rather than where the interaction was requested: this is the frame the
        // product actually changes hands, partway through the clip.
        if (productIndex >= 0) {
            val handed = mutableActiveProducts[productIndex]
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

    /**
     * Pairs two objects into an interaction, facing each other and standing still for its duration.
     *
     * Returns false when either side is already busy, so a caller can fall back rather than stack
     * two interactions onto one worker.
     */
    fun begin(
        definitionId: String,
        initiatorId: String,
        recipientId: String,
        payloadProductId: String?
    ): Boolean {
        val initiatorIndex = mutablePlacedObjects.indexOfFirst { it.id == initiatorId }
        val recipientIndex = mutablePlacedObjects.indexOfFirst { it.id == recipientId }
        if (initiatorIndex < 0 || recipientIndex < 0 || initiatorIndex == recipientIndex) {
            return false
        }
        val initiator = mutablePlacedObjects[initiatorIndex]
        val recipient = mutablePlacedObjects[recipientIndex]
        if (initiator.interaction != null || recipient.interaction != null) {
            return false
        }

        val definition = catalogProvider()?.find(definitionId)
        val duration = definition?.durationSeconds ?: FALLBACK_DURATION_SECONDS
        val transfer = definition?.transferSeconds ?: (duration * DEFAULT_TRANSFER_FRACTION)
        val facing = Orientation.between(initiator.position, recipient.position)

        mutablePlacedObjects[initiatorIndex] = initiator.copy(
            movementPath = emptyList(),
            movementProgress = 0f,
            orientation = facing ?: initiator.orientation,
            interaction = ActiveInteraction(
                definitionId = definitionId,
                partnerObjectId = recipientId,
                role = InteractionRole.INITIATOR,
                payloadProductId = payloadProductId,
                durationSeconds = duration,
                transferSeconds = transfer
            )
        )
        mutablePlacedObjects[recipientIndex] = recipient.copy(
            movementPath = emptyList(),
            movementProgress = 0f,
            orientation = facing?.opposite() ?: recipient.orientation,
            interaction = ActiveInteraction(
                definitionId = definitionId,
                partnerObjectId = initiatorId,
                role = InteractionRole.RECIPIENT,
                payloadProductId = payloadProductId,
                durationSeconds = duration,
                transferSeconds = transfer
            )
        )
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
