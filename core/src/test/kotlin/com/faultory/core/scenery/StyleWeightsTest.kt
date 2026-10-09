package com.faultory.core.scenery

import com.faultory.core.content.SceneryStyleCatalog
import com.faultory.core.content.TagWeight
import com.faultory.core.shop.ScenerySpec
import kotlin.test.Test
import kotlin.test.assertEquals

class StyleWeightsTest {
    private val styles = SceneryStyleCatalog(
        listOf(
            SceneryFixtures.style("workshop", "wood" to 2f, "epoxy" to 0f),
            SceneryFixtures.style("modern", "wood" to 0f)
        )
    )

    @Test
    fun `an untagged piece keeps its own weight whatever the style`() {
        val weights = StyleWeights.forSpec(ScenerySpec(styleId = "workshop"), styles)

        assertEquals(3f, weights.weightOf(3f, emptyList()))
        assertEquals(3f, weights.weightOf(3f, listOf("brick")))
    }

    @Test
    fun `a style's multipliers apply, and the shop's own multiply on top`() {
        val spec = ScenerySpec(styleId = "workshop", tagWeights = listOf(TagWeight("wood", 1.5f)))
        val weights = StyleWeights.forSpec(spec, styles)

        assertEquals(3f, weights.weightOf(1f, listOf("wood")))
        assertEquals(6f, weights.weightOf(2f, listOf("wood", "brick")))
    }

    @Test
    fun `a zero multiplier excludes a piece, and nothing can bring it back`() {
        val spec = ScenerySpec(styleId = "workshop", tagWeights = listOf(TagWeight("epoxy", 100f)))
        val weights = StyleWeights.forSpec(spec, styles)

        assertEquals(0f, weights.weightOf(5f, listOf("epoxy")))
        assertEquals(0f, weights.weightOf(5f, listOf("wood", "epoxy")))
    }

    @Test
    fun `an unknown style is no style`() {
        val weights = StyleWeights.forSpec(ScenerySpec(styleId = "baroque"), styles)

        assertEquals(1f, weights.weightOf(1f, listOf("wood")))
    }

    @Test
    fun `a negative result is clamped to excluded`() {
        val weights = StyleWeights.forSpec(ScenerySpec(tagWeights = listOf(TagWeight("odd", -2f))), styles)

        assertEquals(0f, weights.weightOf(1f, listOf("odd")))
    }
}
