package com.faultory.core.shop.systems

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
    private val catalogProvider: () -> InteractionCatalog?
) {
    private val mutablePlacedObjects get() = state.mutablePlacedObjects
    private val mutableActiveProducts get() = state.mutableActiveProducts

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

        mutablePlacedObjects[takerIndex] = mutablePlacedObjects[takerIndex].copy(carriedProductId = productId)
        mutablePlacedObjects[giverIndex] = mutablePlacedObjects[giverIndex].copy(carriedProductId = null)
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
        return true
    }

    companion object {
        /** Used when the catalog has not loaded, so gameplay never waits on a presentation asset. */
        const val FALLBACK_DURATION_SECONDS = 0.6f
        private const val DEFAULT_TRANSFER_FRACTION = 0.5f
    }
}
