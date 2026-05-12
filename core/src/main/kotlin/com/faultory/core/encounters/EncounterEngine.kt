package com.faultory.core.encounters

import com.faultory.core.save.EncounterProgress
import com.faultory.core.save.EncounterProgressRepository
import com.faultory.core.save.SaveRepository
import com.faultory.core.shop.PlacedShopObject
import kotlin.random.Random

class EncounterEngine(
    private val encounterCatalog: EncounterCatalog,
    private val conditionLibrary: ConditionLibrary,
    private val progressRepository: EncounterProgressRepository,
    private val saveRepository: SaveRepository,
    eventBus: EventBus,
    private val customHandlers: Map<String, () -> Unit> = emptyMap(),
    private val random: Random = Random.Default
) {
    var progress: EncounterProgress = progressRepository.load()
        private set

    var currentLevelId: String? = null
    var currentPlacedObjects: List<PlacedShopObject>? = null

    private var fireDepth = 0
    private val maxFireDepth = 5

    init {
        eventBus.subscribe { onEvent(it) }
    }

    private fun onEvent(event: GameEvent) {
        when (event) {
            is ProductShippedEvent -> progress = updateCounters(event, progress)
            is CleanerSpawnedEvent -> progress = bumpScopedCounter("cleaner.spawned", event.levelId)
            is CleanerHandedProductEvent -> progress = bumpScopedCounter("cleaner.products.handed", event.levelId)
            is UnitFellEvent -> progress = bumpScopedCounter("units.fallen", event.levelId)
            else -> Unit
        }
        val ctx = buildCtx()
        evaluateEncounters(ctx)
        progressRepository.save(progress)
    }

    private fun bumpScopedCounter(prefix: String, levelId: String): EncounterProgress {
        var result = progress
        result = result.withCounter("$prefix.__all__", 1L)
        result = result.withCounter("$prefix.$levelId", 1L)
        return result
    }

    private fun buildCtx() = EvaluationContext(
        saveRepository = saveRepository,
        encounterProgress = progress,
        conditionLibrary = conditionLibrary,
        currentLevelId = currentLevelId,
        placedObjects = currentPlacedObjects,
        random = random
    )

    private fun updateCounters(event: ProductShippedEvent, p: EncounterProgress): EncounterProgress {
        var result = p
        val qualityKeys = if (event.quality == ProductQuality.ANY) {
            listOf("any")
        } else {
            listOf(event.quality.name.lowercase(), "any")
        }
        val scopeKeys = listOf(event.levelId, "__all__")
        for (q in qualityKeys) {
            for (s in scopeKeys) {
                result = result.withCounter("shipped.$q.$s", 1L)
                result = result.withCounter("shipped.$q.$s.${event.productId}", 1L)
            }
        }
        return result
    }

    private fun evaluateEncounters(ctx: EvaluationContext) {
        for (encounter in encounterCatalog.encounters) {
            if (encounter.oneShot && encounter.id in progress.triggeredEncounterIds) continue
            if (encounter.condition.evaluate(ctx)) {
                applyEffect(encounter, ctx)
            }
        }
    }

    private fun applyEffect(encounter: EncounterDefinition, ctx: EvaluationContext) {
        if (encounter.oneShot) {
            progress = progress.withTriggered(encounter.id)
        }
        when (val fx = encounter.effect) {
            is EncounterEffect.MarkTriggered -> Unit
            is EncounterEffect.IncrementCounter -> {
                progress = progress.withCounter(fx.counterKey, fx.amount.toLong())
            }
            is EncounterEffect.FireEvent -> {
                if (fireDepth < maxFireDepth) {
                    fireDepth++
                    val target = encounterCatalog.encounters.firstOrNull { it.id == fx.eventId }
                    if (target != null && !target.id.let { target.oneShot && it in progress.triggeredEncounterIds }
                        && target.condition.evaluate(ctx)) {
                        applyEffect(target, ctx)
                    }
                    fireDepth--
                }
            }
            is EncounterEffect.Custom -> {
                customHandlers[fx.handlerId]?.invoke()
            }
        }
    }
}
