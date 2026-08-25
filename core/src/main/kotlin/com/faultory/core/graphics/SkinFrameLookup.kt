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
    fun region(
        definition: SkinDefinition,
        animationId: String,
        action: String,
        orientation: Orientation,
        delta: Float
    ): TextureRegion? = regionFor(
        definition = definition,
        resolution = SkinFrameResolver.resolve(definition, action, orientation),
        animationId = animationId,
        action = action,
        orientation = orientation,
        delta = delta
    )

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
    ): TextureRegion? = regionFor(
        definition = definition,
        resolution = SkinFrameResolver.resolveExactAction(definition, action, orientation),
        animationId = animationId,
        action = action,
        orientation = orientation,
        delta = delta
    )

    fun endFrame() = animationPlayer.endFrame()

    private fun regionFor(
        definition: SkinDefinition,
        resolution: SkinFrameResolver.Resolution?,
        animationId: String,
        action: String,
        orientation: Orientation,
        delta: Float
    ): TextureRegion? {
        if (resolution == null) {
            return null
        }
        val atlas = atlasProvider(definition.atlas) ?: return null
        val state = animationPlayer.advance(animationId, action, orientation, delta)
        val regionName = animationPlayer.regionName(resolution, state) ?: return null
        return atlas.findRegion(regionName)
    }
}
