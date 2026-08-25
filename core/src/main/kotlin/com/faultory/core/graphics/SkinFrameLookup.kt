package com.faultory.core.graphics

import com.badlogic.gdx.graphics.g2d.TextureAtlas
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.faultory.core.shop.Orientation

/**
 * Single entry point every sprite layer uses: takes a skin plus the action and orientation an
 * entity *wants*, and hands back the atlas region to draw, advancing that entity's clock.
 *
 * A null result means nothing could be resolved and the caller should render shapes instead.
 */
class SkinFrameLookup(
    private val atlasProvider: (String) -> TextureAtlas?,
    private val animationPlayer: AnimationPlayer = AnimationPlayer()
) {
    /**
     * Everything a layer needs to compose one entity: the base region, plus the resolution and frame
     * index that [socket] and [parts] hang off, so attachments always match the pose on screen.
     */
    inner class ResolvedFrame internal constructor(
        val region: TextureRegion,
        val definition: SkinDefinition,
        val resolution: SkinFrameResolver.Resolution,
        val frameIndex: Int
    ) {
        fun socket(name: String): SocketPoint? =
            SkinSocketResolver.resolve(definition, resolution, name, frameIndex)

        /** Cutout layers ordered back to front; empty for the single-image poses that are the norm. */
        fun parts(): List<SkinPartResolver.ResolvedPart> =
            SkinPartResolver.resolve(resolution, frameIndex)

        fun partRegion(part: SkinPartResolver.ResolvedPart): TextureRegion? =
            atlasProvider(definition.atlas)?.findRegion(part.regionName)
    }

    fun resolveFrame(
        definition: SkinDefinition,
        animationId: String,
        action: String,
        orientation: Orientation,
        delta: Float
    ): ResolvedFrame? = resolvedFrameFor(
        definition = definition,
        resolution = SkinFrameResolver.resolve(definition, action, orientation),
        animationId = animationId,
        action = action,
        orientation = orientation,
        delta = delta
    )

    fun region(
        definition: SkinDefinition,
        animationId: String,
        action: String,
        orientation: Orientation,
        delta: Float
    ): TextureRegion? = resolveFrame(definition, animationId, action, orientation, delta)?.region

    /**
     * Resolves without the idle fallback, for overlay masks — an overlay that fell back to idle
     * would draw the base sprite a second time.
     *
     * Pass an [animationId] distinct from the base sprite's, or the two clocks fight each other.
     */
    fun overlayRegion(
        definition: SkinDefinition,
        animationId: String,
        action: String,
        orientation: Orientation,
        delta: Float
    ): TextureRegion? = resolvedFrameFor(
        definition = definition,
        resolution = SkinFrameResolver.resolveExactAction(definition, action, orientation),
        animationId = animationId,
        action = action,
        orientation = orientation,
        delta = delta
    )?.region

    fun endFrame() = animationPlayer.endFrame()

    private fun resolvedFrameFor(
        definition: SkinDefinition,
        resolution: SkinFrameResolver.Resolution?,
        animationId: String,
        action: String,
        orientation: Orientation,
        delta: Float
    ): ResolvedFrame? {
        if (resolution == null) {
            return null
        }
        val atlas = atlasProvider(definition.atlas) ?: return null
        val state = animationPlayer.advance(animationId, action, orientation, delta)
        val frameIndex = animationPlayer.frameIndexFor(resolution.clip, resolution.orientation, state.elapsed)
            ?: return null
        val regionName = resolution.clip.frames[resolution.orientation]?.getOrNull(frameIndex) ?: return null
        val region = atlas.findRegion(regionName) ?: return null
        return ResolvedFrame(
            region = region,
            definition = definition,
            resolution = resolution,
            frameIndex = frameIndex
        )
    }
}
