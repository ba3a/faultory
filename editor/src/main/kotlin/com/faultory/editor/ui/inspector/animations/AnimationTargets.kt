package com.faultory.editor.ui.inspector.animations

import com.faultory.core.assets.AssetPaths
import com.faultory.core.content.ShopCatalog
import com.faultory.core.graphics.InteractionCatalog
import com.faultory.core.graphics.SkinActionCatalog
import com.faultory.core.shop.ShopBlueprint
import com.faultory.editor.ui.tree.AssetSelection

/** One animations grid: which skin it edits, and which action rows it shows. */
data class AnimationTarget(
    val skinId: String,
    val actions: List<String>,
    val label: String? = null,
) {
    val heading: String get() = label?.let { "Animations - $it" } ?: "Animations"
}

/**
 * Maps a selected asset to the animation grids the inspector should show.
 *
 * Kept free of scene2d so the mapping itself is testable: which actions an author can reach for a
 * given asset is the whole point, and getting it wrong shows up as an animation that never plays.
 */
object AnimationTargets {
    fun forSelection(
        selection: AssetSelection,
        catalog: ShopCatalog,
        blueprints: Map<String, ShopBlueprint>,
        interactions: InteractionCatalog = InteractionCatalog(),
    ): List<AnimationTarget> = when (selection) {
        // Interaction halves are authored per interaction rather than as fixed constants, so the
        // rows a worker offers depend on what the interaction catalog currently defines.
        is AssetSelection.Worker -> catalog.workers
            .firstOrNull { it.id == selection.id }
            ?.skin
            .toTargets(SkinActionCatalog.workerActions(interactions))

        is AssetSelection.Machine -> catalog.machines
            .firstOrNull { it.id == selection.id }
            ?.skin
            .toTargets(SkinActionCatalog.machine)

        is AssetSelection.Product -> catalog.products
            .firstOrNull { it.id == selection.id }
            ?.skin
            .toTargets(SkinActionCatalog.product)

        is AssetSelection.Blueprint -> beltTargets(blueprints[selection.shopAssetPath])

        is AssetSelection.Level -> emptyList()
    }

    /**
     * Belts fall back to the shared default skin rather than dropping out of the inspector the way
     * a skinless worker does, because a belt always renders and so always needs somewhere to author
     * it. One grid per distinct skin, since a blueprint can carry several belts.
     */
    private fun beltTargets(blueprint: ShopBlueprint?): List<AnimationTarget> =
        blueprint?.conveyorBelts
            ?.map { belt -> (belt.skin?.takeIf(String::isNotBlank) ?: AssetPaths.defaultBeltSkin) to belt.id }
            ?.groupBy({ it.first }, { it.second })
            ?.map { (skinId, beltIds) ->
                AnimationTarget(
                    skinId = skinId,
                    actions = SkinActionCatalog.belt,
                    label = "$skinId (${beltIds.joinToString()})",
                )
            }
            .orEmpty()

    private fun String?.toTargets(actions: List<String>): List<AnimationTarget> =
        this?.takeIf(String::isNotBlank)
            ?.let { listOf(AnimationTarget(skinId = it, actions = actions)) }
            .orEmpty()
}
