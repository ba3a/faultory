package com.faultory.core.shop.systems

import com.faultory.core.content.MachineRecipe
import com.faultory.core.shop.MachineRecipeState
import com.faultory.core.shop.ProductFaultReason
import com.faultory.core.shop.QueuedMachineOutput

/**
 * The one owner of a machine's recipe input buffer and output queue.
 *
 * Two systems load ingredients into a machine: [ProductionBeltIntakeSystem] pulls them off a belt
 * and [WorkerObjectiveSystem] hands them over from a worker. Both used to carry their own copy of
 * the same read-modify-write helpers, which is exactly the drift a shared collaborator exists to
 * prevent — the same rule that put belt-side QA geometry in [QaPostLocator] and the puddle map in
 * [WetFloor], rather than letting one system call into another.
 *
 * Every method addresses a machine by id and creates the row on demand, so a caller never has to
 * know whether the machine has one yet.
 */
internal class MachineRecipeBuffers(private val access: RecipeStateWrites) {

    fun stateFor(machineId: String): MachineRecipeState? = access.mutableMachineRecipeStates.byId(machineId)

    fun ensureFor(machineId: String): MachineRecipeState {
        access.mutableMachineRecipeStates.byId(machineId)?.let { return it }
        val newState = MachineRecipeState(machineId = machineId)
        access.mutableMachineRecipeStates += newState
        return newState
    }

    /** True when the buffer holds at least the quantity [recipe] asks for of every input. */
    fun hasInputsFor(machineId: String, recipe: MachineRecipe): Boolean {
        val buffer = stateFor(machineId)?.inputBuffer ?: return recipe.inputs.isEmpty()
        return recipe.inputs.all { input -> (buffer[input.productId] ?: 0) >= input.quantity }
    }

    /** Counts one more [productId] into the buffer and folds [faultReason] into the running fault. */
    fun addInput(machineId: String, productId: String, faultReason: ProductFaultReason?) {
        val current = ensureFor(machineId)
        val count = (current.inputBuffer[productId] ?: 0) + 1
        replace(
            current.copy(
                inputBuffer = current.inputBuffer + (productId to count),
                accumulatedInputFault = worstFault(current.accumulatedInputFault, faultReason)
            )
        )
    }

    /**
     * Takes [recipe]'s inputs out of the buffer and clears the running fault, returning the fault
     * the consumed ingredients carried so the caller can stamp it on what the machine produces.
     */
    fun consumeInputs(machineId: String, recipe: MachineRecipe): ProductFaultReason? {
        val current = ensureFor(machineId)
        val remaining = current.inputBuffer.toMutableMap()
        for (input in recipe.inputs) {
            val left = (remaining[input.productId] ?: 0) - input.quantity
            if (left <= 0) remaining.remove(input.productId) else remaining[input.productId] = left
        }
        replace(current.copy(inputBuffer = remaining, accumulatedInputFault = null))
        return current.accumulatedInputFault
    }

    fun queueOutput(machineId: String, output: QueuedMachineOutput) {
        val current = ensureFor(machineId)
        replace(current.copy(outputQueue = current.outputQueue + output))
    }

    /** Drops the head of the output queue, once the caller has placed it somewhere. */
    fun dropQueuedOutputHead(machineId: String) {
        val current = stateFor(machineId) ?: return
        if (current.outputQueue.isEmpty()) return
        replace(current.copy(outputQueue = current.outputQueue.drop(1)))
    }

    fun pruneEmpty() {
        access.mutableMachineRecipeStates.removeAll { it.isEmpty }
    }

    private fun replace(recipeState: MachineRecipeState) {
        val index = access.mutableMachineRecipeStates.indexOfId(recipeState.machineId)
        if (index >= 0) {
            access.mutableMachineRecipeStates[index] = recipeState
        } else {
            access.mutableMachineRecipeStates += recipeState
        }
    }

    companion object {
        /** Sabotage outranks a production defect, which outranks no fault at all. */
        fun worstFault(a: ProductFaultReason?, b: ProductFaultReason?): ProductFaultReason? = when {
            a == ProductFaultReason.SABOTAGE || b == ProductFaultReason.SABOTAGE ->
                ProductFaultReason.SABOTAGE
            a == ProductFaultReason.PRODUCTION_DEFECT || b == ProductFaultReason.PRODUCTION_DEFECT ->
                ProductFaultReason.PRODUCTION_DEFECT
            else -> null
        }
    }
}
