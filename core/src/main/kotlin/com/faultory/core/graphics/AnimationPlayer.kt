package com.faultory.core.graphics

import com.faultory.core.config.GameConfig
import com.faultory.core.shop.Orientation
import kotlin.math.floor

/**
 * One entity's animation clock, owned by [AnimationPlayer.statesById] and advanced in place.
 * Mutable because [AnimationPlayer.advance] runs once per drawn entity per frame and used to
 * allocate a fresh state on every one of them; nothing holds a state past the frame it read it in.
 */
data class AnimationState(
    var action: String,
    var orientation: Orientation,
    var elapsed: Float
)

class AnimationPlayer {
    private val statesById = mutableMapOf<String, AnimationState>()
    private val touchedIds = mutableSetOf<String>()

    /**
     * [action] is the *requested* action, not the one [SkinFrameResolver] settled on, so the
     * clock keeps running when a fallback flips between resolutions of the same semantic state.
     */
    fun advance(id: String, action: String, orientation: Orientation, delta: Float): AnimationState {
        val previous = statesById[id]
        val nextState = if (previous == null) {
            AnimationState(action = action, orientation = orientation, elapsed = 0f)
                .also { statesById[id] = it }
        } else {
            previous.elapsed = if (previous.action != action) 0f else previous.elapsed + delta.coerceAtLeast(0f)
            previous.action = action
            previous.orientation = orientation
            previous
        }

        touchedIds += id
        return nextState
    }

    /**
     * Drops the clocks of everything that was not advanced since the previous call, so entities
     * that despawn do not leak. Products spawn and despawn constantly, so this matters.
     */
    fun endFrame() {
        statesById.keys.retainAll(touchedIds)
        touchedIds.clear()
    }

    fun regionName(clip: ActionClip, state: AnimationState): String? =
        regionName(clip, state.orientation, state.elapsed)

    fun regionName(resolution: SkinFrameResolver.Resolution, state: AnimationState): String? =
        regionName(resolution.clip, resolution.orientation, state.elapsed)

    fun regionName(clip: ActionClip, orientation: Orientation, elapsed: Float): String? {
        val frames = clip.frames[orientation].orEmpty()
        val frameIndex = frameIndexFor(clip, orientation, elapsed) ?: return null
        return frames[frameIndex]
    }

    /**
     * The index into [clip]'s frames for [orientation] at [elapsed], or null when none are authored.
     *
     * Sockets and parts resolve against this same index, so an attachment can never drift onto a
     * frame other than the one being drawn.
     */
    fun frameIndexFor(clip: ActionClip, orientation: Orientation, elapsed: Float): Int? {
        val frameCount = clip.frames[orientation].orEmpty().size
        if (frameCount == 0) {
            return null
        }

        val frameDuration = clip.frameDurationSeconds
            ?.takeIf { it > 0f }
            ?: GameConfig.defaultFrameDurationSeconds
        val frameIndex = floor(elapsed.coerceAtLeast(0f) / frameDuration).toInt().coerceAtLeast(0)
        return if (clip.loop) {
            frameIndex % frameCount
        } else {
            frameIndex.coerceAtMost(frameCount - 1)
        }
    }
}
