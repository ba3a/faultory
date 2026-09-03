package com.faultory.core.graphics

/**
 * The actions each kind of entity can actually request at runtime, as `List<String>` in authoring
 * order — this is what the editor turns into animation grid rows.
 *
 * The lists are derived from [SpriteAction], the single declarative table of every action, so they
 * cannot drift from what the resolvers ask for: an action a resolver requests but the table does not
 * list is an animation that never plays, and `SkinActionCatalogTest` proves it does not happen.
 *
 * The only addition on top of [SpriteAction] is interactions: a worker can be asked to play either
 * half of any authored interaction, and those clip names come from `interactions.json` at runtime
 * rather than the table, so [workerActions] merges them in.
 */
object SkinActionCatalog {
    val worker: List<String> = SpriteAction.idsFor(SpriteKind.WORKER)

    val machine: List<String> = SpriteAction.idsFor(SpriteKind.MACHINE)

    val product: List<String> = SpriteAction.idsFor(SpriteKind.PRODUCT)

    val belt: List<String> = SpriteAction.idsFor(SpriteKind.BELT)

    /**
     * Worker actions including both halves of every authored interaction, since a worker can be
     * asked to play either side and each side comes off its own skin.
     */
    fun workerActions(interactions: InteractionCatalog?): List<String> =
        (worker + interactionActions(interactions)).distinct()

    fun interactionActions(interactions: InteractionCatalog?): List<String> =
        interactions?.interactions
            ?.flatMap { listOf(it.initiatorAction, it.recipientAction) }
            ?.filter(String::isNotBlank)
            ?.distinct()
            .orEmpty()
}
