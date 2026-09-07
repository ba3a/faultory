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
     *
     * Both candidate chains are pure functions of a small closed set — the stand-in table is static
     * and there are four orientations — and they are asked for once per entity per frame, the
     * orientation one once per action tried. So they are computed once and read from a table rather
     * than rebuilt, which is several list allocations per lookup that nothing keeps.
     */
    fun actionCandidates(action: String): List<String> =
        actionCandidatesByAction[action] ?: buildActionCandidates(action)

    /** Requested first, then the canonical south facing, then clockwise, counter-clockwise, opposite. */
    fun orientationCandidates(orientation: Orientation): List<Orientation> =
        orientationCandidatesByOrientation.getValue(orientation)

    private fun buildActionCandidates(action: String): List<String> =
        (listOf(action) + SpriteAction.standIns[action].orEmpty() + SpriteAction.IDLE.id).distinct()

    // Every action a resolver can request is a SpriteAction entry — SkinActionCatalogTest enforces
    // that — so this covers the whole hot path. buildActionCandidates stays reachable for an
    // unauthored id, which resolves to `idle` and draws nothing new.
    private val actionCandidatesByAction: Map<String, List<String>> =
        SpriteAction.entries.associate { it.id to buildActionCandidates(it.id) }

    private val orientationCandidatesByOrientation: Map<Orientation, List<Orientation>> =
        Orientation.entries.associateWith { orientation ->
            listOf(
                orientation,
                Orientation.SOUTH,
                orientation.turnClockwise(),
                orientation.turnCounterClockwise(),
                orientation.opposite()
            ).distinct()
        }
}
