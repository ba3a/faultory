package com.faultory.balance

import com.faultory.core.headless.HeadlessLevelSession
import com.faultory.core.shop.Orientation
import com.faultory.core.shop.PlacedShopObjectKind
import com.faultory.core.shop.PurchaseResult
import com.faultory.core.shop.TileCoordinate
import com.faultory.core.shop.WorkerAssignmentResult

/**
 * A [ShiftPlayer] that executes a declarative [Plan]: time-stamped steps fire at the first decision
 * point with `elapsed >= at`, in list order for ties.
 *
 * Failures are data: each one is recorded in [failures] and handling proceeds per [Plan.onFailure].
 * A step is skipped by the SKIP_DEPENDENTS mode when it uses a label from a step that failed.
 */
class PlanPlayer(private val plan: Plan) : ShiftPlayer {

    data class Failure(val seed: Long, val elapsedSeconds: Float, val step: PlanStep, val reason: String)

    private val labels = mutableMapOf<String, String>()
    private val failedLabels = mutableSetOf<String>()
    private var aborted = false
    private var nextStepIndex = 0

    val failures = mutableListOf<Failure>()

    override fun act(session: HeadlessLevelSession) {
        if (aborted) return
        val elapsed = session.elapsedSeconds
        val steps = plan.steps

        while (nextStepIndex < steps.size && steps[nextStepIndex].at <= elapsed) {
            val step = steps[nextStepIndex]
            nextStepIndex++

            if (shouldSkip(step)) continue

            val failureReason = executeStep(step, session)
            if (failureReason != null) {
                failures += Failure(0L, elapsed, step, failureReason)
                step.`as`?.let { failedLabels += it }
                when (plan.onFailure) {
                    OnFailure.CONTINUE -> continue
                    OnFailure.SKIP_DEPENDENTS -> continue
                    OnFailure.ABORT -> { aborted = true; return }
                }
            }
        }
    }

    private fun shouldSkip(step: PlanStep): Boolean {
        if (plan.onFailure != OnFailure.SKIP_DEPENDENTS) return false
        return step.usedLabels().any { it in failedLabels }
    }

    private fun PlanStep.usedLabels(): Set<String> {
        val used = mutableSetOf<String>()
        `as`?.let { /* binding, not use */ }
        `object`?.let { used += it }
        worker?.let { used += it }
        machine?.let { used += it }
        return used
    }

    private fun executeStep(step: PlanStep, session: HeadlessLevelSession): String? {
        return when (step.`do`) {
            PlanCommand.BUY -> executeBuy(step, session)
            PlanCommand.ROTATE -> executeRotate(step, session)
            PlanCommand.UPGRADE -> executeUpgrade(step, session)
            PlanCommand.ASSIGN -> executeAssign(step, session)
            PlanCommand.ASSIGN_QA -> executeAssignQa(step, session)
        }
    }

    private fun executeBuy(step: PlanStep, session: HeadlessLevelSession): String? {
        val kindStr = step.objectKind ?: return "buy: objectKind is required"
        val catalogId = step.catalogId ?: return "buy: catalogId is required"
        val tile = step.tile?.let {
            if (it.size < 2) return "buy: tile must have [x, y]"
            TileCoordinate(it[0], it[1])
        } ?: return "buy: tile is required"

        val kind = runCatching { PlacedShopObjectKind.valueOf(kindStr) }.getOrElse {
            return "buy: unknown objectKind '$kindStr'"
        }

        return when (val result = session.buyAndPlace(kind, catalogId, tile)) {
            is PurchaseResult.Success -> {
                step.`as`?.let { labels[it] = result.placedObject.id }
                null
            }
            is PurchaseResult.Failure -> "buy $catalogId: ${result.reason.name}"
        }
    }

    private fun executeRotate(step: PlanStep, session: HeadlessLevelSession): String? {
        val objectRef = step.`object` ?: return "rotate: object is required"
        val orientationStr = step.orientation ?: return "rotate: orientation is required"
        val id = resolve(objectRef)
        val orientation = runCatching { Orientation.valueOf(orientationStr) }.getOrElse {
            return "rotate: unknown orientation '$orientationStr'"
        }
        return if (session.rotate(id, orientation)) null else "rotate $id: failed (not a machine or invalid orientation)"
    }

    private fun executeUpgrade(step: PlanStep, session: HeadlessLevelSession): String? {
        val objectRef = step.`object` ?: return "upgrade: object is required"
        val target = step.target ?: return "upgrade: target is required"
        val id = resolve(objectRef)
        return if (session.upgrade(id, target)) null else "upgrade $id to $target: not available"
    }

    private fun executeAssign(step: PlanStep, session: HeadlessLevelSession): String? {
        val workerRef = step.worker ?: return "assign: worker is required"
        val machineRef = step.machine ?: return "assign: machine is required"
        val workerId = resolve(workerRef)
        val machineId = resolve(machineRef)
        return when (val result = session.assignToMachine(workerId, machineId)) {
            is WorkerAssignmentResult.Success -> null
            is WorkerAssignmentResult.Failure -> "assign $workerRef to $machineRef: ${result.reason.name}"
        }
    }

    private fun executeAssignQa(step: PlanStep, session: HeadlessLevelSession): String? {
        val workerRef = step.worker ?: return "assignQa: worker is required"
        val workerId = resolve(workerRef)
        return when (val result = session.assignToQa(workerId)) {
            is WorkerAssignmentResult.Success -> null
            is WorkerAssignmentResult.Failure -> "assignQa $workerRef: ${result.reason.name}"
        }
    }

    /** Resolves a label or returns the value as-is if it's not a known label. */
    private fun resolve(labelOrId: String): String = labels[labelOrId] ?: labelOrId
}
