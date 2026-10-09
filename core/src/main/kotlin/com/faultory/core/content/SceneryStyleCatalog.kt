package com.faultory.core.content

import kotlinx.serialization.Serializable

/** Every named scenery style (`content/scenery-styles.json`). */
@Serializable
data class SceneryStyleCatalog(
    val styles: List<SceneryStyle> = emptyList()
)

/**
 * A look a shop can opt into by name, shared by every level that should look alike. Each
 * [TagWeight] multiplies the weight of every tile and wall piece carrying its tag - 0 excludes those
 * pieces, above 1 favours them - and pieces with no listed tag keep their own weight, so a style
 * only has to mention what it changes.
 */
@Serializable
data class SceneryStyle(
    val id: String,
    val tagWeights: List<TagWeight> = emptyList()
)

@Serializable
data class TagWeight(
    val tag: String,
    val multiplier: Float
)
