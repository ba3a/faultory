package com.faultory.core.screens.shopfloor

import com.faultory.core.graphics.InteractionDefinition
import com.faultory.core.shop.InteractionRole
import kotlin.math.PI
import kotlin.math.atan

/**
 * Two interacting entities and their payload, drawn as one unit so their fragments can interleave.
 *
 * Per-entity depth handles a body wrapping around what it holds, but it cannot put one entity
 * *inside* another: the ordinary sort gives each entity a single slot, so a worker reaching into a
 * machine can only ever be wholly in front of it or wholly behind. An interaction that declares a
 * [InteractionDefinition.layerOrder] escapes that by pooling both participants into one sort
 * position and ordering their fragments by the authored list instead.
 *
 * Fragment keys are role-qualified rather than kind-qualified — `initiator.far_arm`, not
 * `worker.far_arm` — because a worker-to-worker exchange has two workers and a kind alone could not
 * say which one. [PAYLOAD] names the carried product.
 *
 * One list is authored per interaction type, never per pair, so this stays as cheap to extend as
 * the socket system it sits on.
 */
class InteractionRenderGroup(
    private val definition: InteractionDefinition,
    private val initiatorId: String,
    private val recipientId: String,
    val payloadProductId: String?,
    /**
     * The frontmost participant's position, which the whole group sorts by.
     *
     * Treating two entities as one unit cannot be exactly right against the rest of the scene, but
     * participants in a layered interaction are adjacent or overlapping by construction, so the
     * only band that could sort wrong is the sliver between them — which they themselves occupy.
     * Anchoring forward keeps the group from being drawn over by what it is standing in front of.
     */
    val anchor: RenderPosition
) {
    fun roleKeyFor(objectId: String): String? = when (objectId) {
        initiatorId -> INITIATOR
        recipientId -> RECIPIENT
        else -> null
    }

    fun partKey(roleKey: String, partName: String): String = "$roleKey.$partName"

    /**
     * The slot [key] occupies in the authored order.
     *
     * Anything unlisted trails every listed fragment, keeping its own per-entity depth as the
     * tiebreak, so a partially authored list still renders everything in a sensible order.
     *
     * A group with no order authored at all leaves every depth untouched.
     */
    fun depthFor(key: String, fallbackDepth: Float): Float {
        if (definition.layerOrder.isEmpty()) {
            return fallbackDepth
        }
        val index = definition.layerOrder.indexOf(key)
        if (index >= 0) {
            return index.toFloat()
        }
        // Squashed into a unit band past the last listed slot rather than added raw: a part
        // authored at a negative depth - a far arm, say - would otherwise be pulled in front of
        // fragments the layer order explicitly placed behind it.
        return definition.layerOrder.size + squashed(fallbackDepth)
    }

    /** Order-preserving map from any depth onto (0, 1). */
    private fun squashed(depth: Float): Float = 0.5f + atan(depth) / PI.toFloat()

    companion object {
        const val INITIATOR = "initiator"
        const val RECIPIENT = "recipient"
        const val PAYLOAD = "payload"

        fun roleKeyOf(role: InteractionRole): String = when (role) {
            InteractionRole.INITIATOR -> INITIATOR
            InteractionRole.RECIPIENT -> RECIPIENT
        }
    }
}
