package com.faultory.core.graphics

/**
 * Finds where a named socket sits on the frame that is actually being drawn.
 *
 * Every lookup takes the [SkinFrameResolver.Resolution] the frame resolver settled on rather than
 * the action and orientation that were *requested*. That distinction matters: a request that fell
 * back from `carried` to `idle`, or from north to south, must read the socket belonging to the pose
 * on screen, or the held item attaches to a body position that is not being rendered.
 *
 * The chain degrades the same way [SkinFrameResolver] does: the frame's own point, then the
 * orientation's point walking [SkinFrameResolver.orientationCandidates], then the skin-wide default.
 * An unresolved lookup means the caller should centre the attachment on the tile.
 */
object SkinSocketResolver {
    fun resolve(
        definition: SkinDefinition,
        resolution: SkinFrameResolver.Resolution,
        socketName: String,
        frameIndex: Int
    ): SocketPoint? {
        val socketClip = resolution.clip.sockets[socketName]
        if (socketClip != null) {
            // Per-frame points are index-aligned with *this* orientation's frames, so unlike
            // per-orientation points they are never borrowed from a neighbouring facing.
            socketClip.byFrame[resolution.orientation]
                ?.getOrNull(frameIndex)
                ?.let { return it }

            for (candidate in SkinFrameResolver.orientationCandidates(resolution.orientation)) {
                socketClip.byOrientation[candidate]?.let { return it }
            }
        }

        return definition.sockets[socketName]
    }
}
