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
            (ctx.saveRepository.load(levelId)?.lastCompletedRun?.starsEarned ?: 0) >= minStars
    }

    @Serializable @SerialName("level.notCompleted")
    data class LevelNotCompleted(val levelId: String) : Condition {
        override fun evaluate(ctx: EvaluationContext): Boolean =
            (ctx.saveRepository.load(levelId)?.lastCompletedRun?.starsEarned ?: 0) < 1
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

        internal fun counterKey(levelId: String?): String {
            val q = if (quality == ProductQuality.ANY) "any" else quality.name.lowercase()
            val s = if (scope == CountScope.CURRENT_LEVEL) (levelId ?: "__unknown__") else "__all__"
            return if (productId != null) "shipped.$q.$s.$productId" else "shipped.$q.$s"
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
}
