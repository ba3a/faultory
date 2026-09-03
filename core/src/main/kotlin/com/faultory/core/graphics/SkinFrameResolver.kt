package com.faultory.core.graphics

import com.faultory.core.shop.Orientation

/**
 * Picks the clip a sprite should actually draw, degrading gracefully when the requested
 * action or orientation was never authored.
 *
 * The chain is, in order: the requested action facing the requested way, then facing
 * [Orientation.SOUTH], then facing the nearest authored orientation by turning order;
 * then the same three steps again for any stand-in the action declares, and finally for
 * [SpriteAction.IDLE]. An unresolved lookup means the caller should fall back to shape rendering.
 */
object SkinFrameResolver {
    data class Resolution(
        val clip: ActionClip,
        val action: String,
        val orientation: Orientation
    )

    fun resolve(definition: SkinDefinition, action: String, orientation: Orientation): Resolution? {
        for (candidateAction in actionCandidates(action)) {
            resolveExactAction(definition, candidateAction, orientation)?.let { return it }
        }
        return null
    }

    /**
     * Resolves within a single action only, still walking the orientation candidates.
     * Used for overlays, where falling back to [SpriteAction.IDLE] would draw the base sprite twice.
     */
    fun resolveExactAction(definition: SkinDefinition, action: String, orientation: Orientation): Resolution? {
        val clip = definition.actions[action] ?: return null
        for (candidateOrientation in orientationCandidates(orientation)) {
            if (clip.frames[candidateOrientation].isNullOrEmpty()) {
                continue
            }
            return Resolution(clip = clip, action = action, orientation = candidateOrientation)
        }
        return null
    }

    /**
     * The requested action, then its stand-in ([SpriteAction.standIns], tried before idle because
     * idle is the wrong substitute for anything that plays while the entity is moving or off its
     * feet — an unauthored `pursue` must not freeze a guard mid-stride), then `idle`.
     */
    fun actionCandidates(action: String): List<String> =
        (listOf(action) + SpriteAction.standIns[action].orEmpty() + SpriteAction.IDLE.id).distinct()

    /** Requested first, then the canonical south facing, then clockwise, counter-clockwise, opposite. */
    fun orientationCandidates(orientation: Orientation): List<Orientation> =
        listOf(
            orientation,
            Orientation.SOUTH,
            orientation.turnClockwise(),
            orientation.turnCounterClockwise(),
            orientation.opposite()
        ).distinct()
}
