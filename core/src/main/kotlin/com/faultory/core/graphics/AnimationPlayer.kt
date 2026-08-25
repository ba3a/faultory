package com.faultory.core.graphics

import com.faultory.core.config.GameConfig
import com.faultory.core.shop.Orientation
import kotlin.math.floor

data class AnimationState(
    val action: String,
    val orientation: Orientation,
    val elapsed: Float
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
        val nextState = if (previous == null || previous.action != action) {
            AnimationState(action = action, orientation = orientation, elapsed = 0f)
        } else {
            AnimationState(
                action = action,
                orientation = orientation,
                elapsed = previous.elapsed + delta.coerceAtLeast(0f)
            )
        }

        statesById[id] = nextState
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
        if (frames.isEmpty()) {
            return null
        }

        val frameDuration = clip.frameDurationSeconds
            ?.takeIf { it > 0f }
            ?: GameConfig.defaultFrameDurationSeconds
        val frameIndex = floor(elapsed.coerceAtLeast(0f) / frameDuration).toInt().coerceAtLeast(0)
        val resolvedIndex = if (clip.loop) {
            frameIndex % frames.size
        } else {
            frameIndex.coerceAtMost(frames.lastIndex)
        }
        return frames[resolvedIndex]
    }
}
