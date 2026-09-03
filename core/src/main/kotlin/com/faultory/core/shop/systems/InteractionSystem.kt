package com.faultory.core.shop.systems

/**
 * The scheduled half of interactions: advances every in-flight two-actor interaction once per
 * frame. All the logic — ticking, the payload transfer, [InteractionController.begin] — lives on
 * [InteractionController]; this class exists only to place that work in [SimulationPhase.ANIMATION].
 */
internal class InteractionSystem(
    private val controller: InteractionController
) : SimulationSystem {
    override val phase = SimulationPhase.ANIMATION

    override fun step(context: SystemContext) = controller.advanceAll(context.deltaSeconds)
}
