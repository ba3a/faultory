package com.faultory.core.scenery

import com.faultory.core.content.SceneryStyleCatalog
import com.faultory.core.shop.ScenerySpec

/**
 * A level's say over which pieces it uses: one multiplier per tag, from the shop's named style
 * with the shop's own tag weights applied on top. The single place a piece's per-level weight is
 * worked out - the autotiler, the wall ring and the editor's preview all ask this, so a level can
 * never filter pieces two different ways.
 */
class StyleWeights(private val multipliersByTag: Map<String, Float>) {
    /** [baseWeight] times the multiplier of each of [tags]; at or below 0 the piece is excluded. */
    fun weightOf(baseWeight: Float, tags: List<String>): Float {
        var weight = baseWeight
        for (tag in tags) {
            weight *= multipliersByTag[tag] ?: 1f
        }
        return weight.coerceAtLeast(0f)
    }

    companion object {
        val NEUTRAL = StyleWeights(emptyMap())

        /** An unknown [ScenerySpec.styleId] counts as no style; the editor reports it. */
        fun forSpec(spec: ScenerySpec, styles: SceneryStyleCatalog): StyleWeights {
            val multipliers = HashMap<String, Float>()
            val style = spec.styleId?.let { id -> styles.styles.firstOrNull { it.id == id } }
            for (tagWeight in style?.tagWeights.orEmpty() + spec.tagWeights) {
                multipliers[tagWeight.tag] = (multipliers[tagWeight.tag] ?: 1f) * tagWeight.multiplier
            }
            return StyleWeights(multipliers)
        }
    }
}
