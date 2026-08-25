package com.faultory.core.graphics

import kotlinx.serialization.Serializable

/**
 * A two-actor interaction, authored once and played by both participants from their own skins.
 *
 * This is what keeps paired animation out of combinatorial territory. Rather than authoring
 * "cleaner hands crate to inspector" for every pairing, each side plays its own half — the giver
 * plays [initiatorAction], the taker plays [recipientAction] — and the payload rides each side's
 * socket in turn. Adding a worker type costs two clips on that skin, not a clip per partner.
 *
 * Every field degrades: a skin with no clip authored for its half falls back through
 * [SkinFrameResolver] to idle and simply stands still for the duration, which reads as plain rather
 * than broken and never blocks the gameplay change on art.
 */
@Serializable
data class InteractionDefinition(
    val id: String,
    val initiatorAction: String,
    val recipientAction: String,
    val durationSeconds: Float,
    /** Normalized point in the clip at which the payload changes hands. */
    val payloadTransferAt: Float = 0.5f,
    val initiatorSocket: String = SocketNames.HANDS,
    val recipientSocket: String = SocketNames.HANDS,
    /**
     * Qualified fragment keys ordered back to front, for interactions whose participants overlap
     * heavily enough that per-entity depth cannot separate them — a worker reaching into a machine
     * needs the machine's interior between their two arms.
     *
     * Empty for the common case, where the ordinary tile sort already keeps participants apart.
     */
    val layerOrder: List<String> = emptyList()
) {
    /** Where in the interaction the payload sits, clamped so bad authoring cannot invert the clip. */
    val transferSeconds: Float
        get() = durationSeconds * payloadTransferAt.coerceIn(0f, 1f)
}

@Serializable
data class InteractionCatalog(
    val interactions: List<InteractionDefinition> = emptyList()
) {
    private val byId: Map<String, InteractionDefinition> by lazy { interactions.associateBy { it.id } }

    fun find(id: String): InteractionDefinition? = byId[id]
}

object InteractionIds {
    /** A worker passing a carried product to an adjacent worker. */
    const val HAND_OFF = "hand_off"
}
