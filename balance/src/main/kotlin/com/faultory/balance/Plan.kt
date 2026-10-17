package com.faultory.balance

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/**
 * A declarative, time-stamped list of commands that drives a shift. Each [PlanStep] fires at the
 * first decision point with `elapsedSeconds >= at`, in list order for equal `at` values.
 *
 * Plans are level-specific: tile coordinates are absolute and only meaningful for the level the plan
 * was authored against. [forLevel] records which level that is.
 */
@Serializable
data class Plan(
    val kind: String = "plan",
    val forLevel: String,
    val decisionIntervalSeconds: Float = 1.0f,
    val onFailure: OnFailure = OnFailure.CONTINUE,
    val steps: List<PlanStep> = emptyList()
)

enum class OnFailure { CONTINUE, SKIP_DEPENDENTS, ABORT }

/**
 * One command in a plan. [at] is simulated-seconds from shift start. [label] is the `as` field —
 * binds the placed object's runtime id to a symbolic name later steps use.
 */
@Serializable
data class PlanStep(
    val at: Float,
    val `do`: PlanCommand,
    /** Bind the placed object to this label so later steps can refer to it by name. */
    val `as`: String? = null,
    /** For `buy`: MACHINE or WORKER. */
    val objectKind: String? = null,
    /** For `buy`/`upgrade`: catalog id of the thing to buy or upgrade to. */
    val catalogId: String? = null,
    /** For `buy`: placement tile [x, y]. */
    val tile: List<Int>? = null,
    /** For `rotate`: target orientation name. */
    val orientation: String? = null,
    /** For `upgrade`: label or runtime id of the object to upgrade. */
    val `object`: String? = null,
    /** For `upgrade`: catalog id to upgrade to. */
    val target: String? = null,
    /** For `assign`: label or id of the worker. */
    val worker: String? = null,
    /** For `assign`: label or id of the machine. */
    val machine: String? = null,
)

enum class PlanCommand { BUY, ROTATE, UPGRADE, ASSIGN, ASSIGN_QA }

/** A stored plan: a name, the level it was authored for, and the [Plan] itself. */
@Serializable
data class StoredPlan(
    val name: String,
    val plan: Plan
)
