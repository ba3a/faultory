package com.faultory.core.graphics

import com.faultory.core.shop.Orientation
import kotlin.math.floor

data class AnimationState(
    val action: String,
    val orientation: Orientation,
    val elapsed: Float
)

class AnimationPlayer {
    private val statesById = mutableMapOf<String, AnimationState>()

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
        return nextState
    }

    fun regionName(clip: ActionClip, state: AnimationState): String? {
        val frames = clip.frames[state.orientation].orEmpty()
        if (frames.isEmpty()) {
            return null
        }
        if (clip.fps <= 0f) {
            return frames.first()
        }

        val elapsed = state.elapsed.coerceAtLeast(0f)
        val frameIndex = floor(elapsed * clip.fps).toInt().coerceAtLeast(0)
        val resolvedIndex = if (clip.loop) {
            frameIndex % frames.size
        } else {
            frameIndex.coerceAtMost(frames.lastIndex)
        }
        return frames[resolvedIndex]
    }
}
