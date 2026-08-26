package com.faultory.core.graphics

/**
 * The actions each kind of entity can actually request at runtime, in authoring order.
 *
 * This is what the editor offers as animation rows. It lives beside the action constants rather
 * than in the editor so the two cannot drift: an action a resolver can ask for but nobody can
 * author is an animation that never plays.
 *
 * Two worker states are deliberately absent because the architecture covers them elsewhere.
 * Carrying is not an action - the payload rides the `hands` socket over the ordinary pose - and
 * handing something over is an interaction, whose clip names are authored per interaction and
 * merged in by [workerActions].
 */
object SkinActionCatalog {
    private val workerBase: List<String> = listOf(
        SkinActions.IDLE,
        SkinActions.WALK,
        SkinActions.BELT_ENTER,
        SkinActions.BELT_RIDE,
        SkinActions.BELT_EXIT,
        SkinActions.PURSUE,
        SkinActions.FALL,
        SkinActions.LIE,
        SkinActions.STAND_UP,
        SkinActions.DESTROY
    )

    val worker: List<String> get() = workerBase

    val machine: List<String> = listOf(
        SkinActions.IDLE,
        SkinActions.WORKING,
        SkinActions.INSPECT,
        SkinActions.BLOCKED
    )

    val product: List<String> = listOf(
        ProductActions.IDLE,
        ProductActions.PRODUCING,
        ProductActions.ON_BELT,
        ProductActions.CARRIED,
        ProductActions.INSPECTED,
        ProductActions.DESTROYING,
        ProductActions.FAULT_DEFECT,
        ProductActions.FAULT_SABOTAGE
    )

    /**
     * Idle leads because a belt skin that authors nothing else still renders every tile through
     * the [SkinFrameResolver] fallback.
     */
    val belt: List<String> = listOf(
        SkinActions.IDLE,
        BeltActions.STRAIGHT,
        BeltActions.TURN_CW,
        BeltActions.TURN_CCW,
        BeltActions.START,
        BeltActions.END
    )

    /**
     * Worker actions including both halves of every authored interaction, since a worker can be
     * asked to play either side and each side comes off its own skin.
     */
    fun workerActions(interactions: InteractionCatalog?): List<String> =
        (workerBase + interactionActions(interactions)).distinct()

    fun interactionActions(interactions: InteractionCatalog?): List<String> =
        interactions?.interactions
            ?.flatMap { listOf(it.initiatorAction, it.recipientAction) }
            ?.filter(String::isNotBlank)
            ?.distinct()
            .orEmpty()
}
