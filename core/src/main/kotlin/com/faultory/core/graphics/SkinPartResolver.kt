package com.faultory.core.graphics

/**
 * Picks the cutout layers to composite around a clip's base frame, ordered back to front.
 *
 * Unlike [SkinFrameResolver] and [SkinSocketResolver] this deliberately does **not** degrade across
 * orientations. A far arm borrowed from the south pose and composited onto an east body reads as a
 * glitch, which is worse than the arm simply being absent, so a part with nothing authored for the
 * resolved orientation is skipped.
 *
 * Frame indices *are* clamped, because a static part accompanying an animated body - one far arm
 * against a five-frame walk - is ordinary authoring rather than a mistake.
 */
object SkinPartResolver {
    data class ResolvedPart(
        val name: String,
        val depth: Float,
        val regionName: String
    )

    fun resolve(resolution: SkinFrameResolver.Resolution, frameIndex: Int): List<ResolvedPart> {
        val parts = resolution.clip.parts
        if (parts.isEmpty()) {
            return emptyList()
        }

        return parts.mapNotNull { (name, part) ->
            val frames = part.frames[resolution.orientation].orEmpty()
            if (frames.isEmpty()) {
                return@mapNotNull null
            }
            ResolvedPart(
                name = name,
                depth = part.depth,
                regionName = frames[frameIndex.coerceIn(0, frames.lastIndex)]
            )
        // Stable, so parts authored at the same depth keep their authoring order.
        }.sortedBy { it.depth }
    }
}
