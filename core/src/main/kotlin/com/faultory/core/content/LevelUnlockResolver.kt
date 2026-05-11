package com.faultory.core.content

import com.faultory.core.encounters.EvaluationContext

object LevelUnlockResolver {
    fun isUnlocked(level: LevelDefinition, ctx: EvaluationContext): Boolean =
        level.unlockCondition.evaluate(ctx)
}
