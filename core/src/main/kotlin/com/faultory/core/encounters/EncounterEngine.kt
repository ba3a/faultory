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
    private var unsavedChanges = false

    init {
        eventBus.subscribe { onEvent(it) }
    }

    private fun onEvent(event: GameEvent) {
        val before = progress
        progress = accumulate(event, progress)
        val ctx = buildCtx()
        evaluateEncounters(ctx)
        if (progress != before) {
            unsavedChanges = true
        }
        // Now that the whole floor publishes, saving per event would put a file write and an
        // atomic rename on the simulation's hot path. Progress is flushed where play pauses
        // instead: shift boundaries here, and screen changes via [flush].
        if (event is ShiftStartedEvent || event is LevelCompletedEvent) {
            flush()
        }
    }

    /** Writes accumulated progress if anything changed since the last write. */
    fun flush() {
        if (!unsavedChanges) return
        progressRepository.save(progress)
        unsavedChanges = false
    }

    /**
     * Bumps whatever counters the event names.
     *
     * The engine deliberately knows nothing about individual event types: an event that carries its
     * own keys starts accumulating statistics the moment it is published, with no change here.
     */
    private fun accumulate(event: GameEvent, current: EncounterProgress): EncounterProgress {
        val scope = event.levelId ?: currentLevelId ?: GameEvent.UNKNOWN_SCOPE
        var result = current
        for (key in event.counterKeys(scope).distinct()) {
            result = result.withCounter(key, 1L)
        }
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
