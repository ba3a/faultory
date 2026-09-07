package com.faultory.core.screens.shopfloor

import com.badlogic.gdx.graphics.g2d.SpriteBatch
import java.util.Arrays

/**
 * A layer's planned sprites for one frame, in a buffer that outlives the frame so the sprites
 * themselves do not have to.
 *
 * Two things this exists for. The instances are reused: a layer plans the same handful of fragments
 * every frame and used to allocate a fresh [PlannedSprite] and [SpritePlacement] for each one, sixty
 * times a second. And the sort runs in place over the backing array — `MutableList.sortWith`
 * delegates to `Collections.sort`, which copies the list to an array, sorts, and copies back, every
 * frame, whether or not anything moved.
 *
 * [obtain] hands back a sprite to fill in; it is live until the next [clear]. Nothing may hold one
 * past the layer's draw pass.
 */
class PlannedSpriteBuffer {
    private var sprites: Array<PlannedSprite?> = arrayOfNulls(INITIAL_CAPACITY)

    var size: Int = 0
        private set

    fun clear() {
        size = 0
    }

    /** The next sprite to fill in, reused from a previous frame when there is one to reuse. */
    fun obtain(): PlannedSprite {
        if (size == sprites.size) {
            sprites = sprites.copyOf(sprites.size * 2)
        }
        val existing = sprites[size]
        val sprite = existing ?: PlannedSprite().also { sprites[size] = it }
        size += 1
        return sprite
    }

    /** Drops the sprite [obtain] just handed out, for a caller that could not resolve it after all. */
    fun discardLast() {
        if (size > 0) size -= 1
    }

    fun sortBackToFront() {
        Arrays.sort(sprites, 0, size, backToFront)
    }

    /** The sprite at [index] in the live range. Exists for `PlannedSpriteBufferTest`. */
    internal fun spriteAt(index: Int): PlannedSprite = requireNotNull(sprites[index]) {
        "no sprite at $index; the live range is 0 until $size"
    }

    fun drawAll(batch: SpriteBatch) {
        for (index in 0 until size) {
            sprites[index]?.draw(batch)
        }
    }

    private companion object {
        const val INITIAL_CAPACITY = 64

        // The array's slots are nullable past [size], but the sorted range never is — obtain fills
        // every slot it hands out, and clear only rewinds the count.
        val backToFront: Comparator<PlannedSprite?> = Comparator { first, second ->
            if (first == null || second == null) 0 else PlannedSprite.backToFront.compare(first, second)
        }
    }
}
