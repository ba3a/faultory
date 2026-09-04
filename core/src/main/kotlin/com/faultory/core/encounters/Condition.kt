package com.faultory.core.encounters

import com.faultory.core.shop.PlacedShopObjectKind
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ProductQuality { GOOD, FAULTY, SABOTAGED, ANY }

@Serializable
enum class CountScope { CURRENT_LEVEL, ALL_LEVELS }

@Serializable
sealed interface Condition {
    fun evaluate(ctx: EvaluationContext): Boolean
    fun missingHints(ctx: EvaluationContext): List<String> = emptyList()

    @Serializable @SerialName("always")
    data object Always : Condition {
        override fun evaluate(ctx: EvaluationContext) = true
    }

    @Serializable @SerialName("never")
    data object Never : Condition {
        override fun evaluate(ctx: EvaluationContext) = false
    }

    @Serializable @SerialName("level.completed")
    data class LevelCompleted(val levelId: String, val minStars: Int = 1) : Condition {
        override fun evaluate(ctx: EvaluationContext): Boolean =
            (ctx.levelSave(levelId)?.lastCompletedRun?.starsEarned ?: 0) >= minStars
    }

    @Serializable @SerialName("level.notCompleted")
    data class LevelNotCompleted(val levelId: String) : Condition {
        override fun evaluate(ctx: EvaluationContext): Boolean =
            (ctx.levelSave(levelId)?.lastCompletedRun?.starsEarned ?: 0) < 1
    }

    @Serializable @SerialName("shipped")
    data class ProductsShipped(
        val quality: ProductQuality,
        val scope: CountScope,
        val productId: String? = null,
        val atLeast: Int
    ) : Condition {
        override fun evaluate(ctx: EvaluationContext): Boolean =
            (ctx.encounterProgress.counters[counterKey(ctx.currentLevelId)] ?: 0L) >= atLeast

        internal fun counterKey(levelId: String?): String = CounterKeys.key(
            "shipped",
            scope.scopeSegment(levelId),
            buildList {
                if (quality != ProductQuality.ANY) add("quality" to quality.name.lowercase())
                if (productId != null) add("product" to productId)
            },
        )
    }

    /**
     * Reads any counter an event accumulates, so an achievement over a new event needs no new
     * condition type — only the [GameEvent.counterName] and, for a breakdown key, its
     * `dimension.value` suffix (e.g. `"outcome.caught"`).
     */
    @Serializable @SerialName("counter")
    data class CounterAtLeast(
        val counterName: String,
        val scope: CountScope,
        val suffix: String? = null,
        val atLeast: Int
    ) : Condition {
        override fun evaluate(ctx: EvaluationContext): Boolean =
            (ctx.encounterProgress.counters[counterKey(ctx.currentLevelId)] ?: 0L) >= atLeast

        internal fun counterKey(levelId: String?): String {
            val base = CounterKeys.key(counterName, scope.scopeSegment(levelId))
            return if (suffix != null) "$base.$suffix" else base
        }
    }

    @Serializable @SerialName("placed")
    data class ObjectPlaced(val kind: PlacedShopObjectKind, val catalogId: String) : Condition {
        override fun evaluate(ctx: EvaluationContext): Boolean =
            ctx.placedObjects?.any { it.kind == kind && it.catalogId == catalogId } ?: false
    }

    @Serializable @SerialName("encounter.triggered")
    data class EncounterTriggered(val encounterId: String) : Condition {
        override fun evaluate(ctx: EvaluationContext): Boolean =
            encounterId in ctx.encounterProgress.triggeredEncounterIds
    }

    @Serializable @SerialName("ref")
    data class Ref(val refId: String) : Condition {
        override fun evaluate(ctx: EvaluationContext): Boolean =
            ctx.conditionLibrary.resolve(refId)?.evaluate(ctx)
                ?: error("Unknown condition ref: $refId")
    }

    @Serializable @SerialName("and")
    data class And(val operands: List<Condition>) : Condition {
        override fun evaluate(ctx: EvaluationContext) = operands.all { it.evaluate(ctx) }
    }

    @Serializable @SerialName("or")
    data class Or(val operands: List<Condition>) : Condition {
        override fun evaluate(ctx: EvaluationContext) = operands.any { it.evaluate(ctx) }
    }

    @Serializable @SerialName("xor")
    data class Xor(val operands: List<Condition>) : Condition {
        override fun evaluate(ctx: EvaluationContext) = operands.count { it.evaluate(ctx) } % 2 == 1
    }

    @Serializable @SerialName("not")
    data class Not(val operand: Condition) : Condition {
        override fun evaluate(ctx: EvaluationContext) = !operand.evaluate(ctx)
    }

    @Serializable @SerialName("random")
    data class Random(val probability: Float) : Condition {
        override fun evaluate(ctx: EvaluationContext): Boolean =
            ctx.random.nextFloat() < probability
    }
}
