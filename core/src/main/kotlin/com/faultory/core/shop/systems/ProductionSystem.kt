package com.faultory.core.shop.systems

import com.faultory.core.config.GameConfig
import com.faultory.core.content.MachineRecipe
import com.faultory.core.content.MachineSlotType
import com.faultory.core.content.MachineSpec
import com.faultory.core.content.MachineType
import com.faultory.core.content.Manuality
import com.faultory.core.content.WorkerProfile
import com.faultory.core.content.WorkerRole
import com.faultory.core.encounters.MachineInputLoadedEvent
import com.faultory.core.encounters.MachineInputSource
import com.faultory.core.encounters.ProductHandedOverEvent
import com.faultory.core.encounters.ProductPlacedOnBeltEvent
import com.faultory.core.encounters.ProductPlacedOnFloorEvent
import com.faultory.core.encounters.ProductionCompletedEvent
import com.faultory.core.encounters.ProductionStartedEvent
import com.faultory.core.encounters.SabotageCommittedEvent
import com.faultory.core.encounters.ShopFloorEvents
import com.faultory.core.shop.MachineProductionState
import com.faultory.core.shop.MachineRecipeState
import com.faultory.core.shop.PlacedShopObject
import com.faultory.core.shop.ProductFaultReason
import com.faultory.core.shop.QueuedMachineOutput
import com.faultory.core.shop.ShopProduct
import com.faultory.core.shop.ShopProductState
import com.faultory.core.shop.TileCoordinate
import com.faultory.core.shop.manhattanDistanceTo
import kotlin.math.abs
import kotlin.math.max
import kotlin.random.Random

internal class ProductionSystem(
    private val access: ProductionAccess,
    private val random: Random,
    private val events: ShopFloorEvents = ShopFloorEvents(),
    private val chance: ChanceOracle = RandomChanceOracle(random)
) : SimulationSystem {
    private val grid get() = access.grid
    private val mutablePlacedObjects get() = access.mutablePlacedObjects
    private val placedMachines get() = access.placedMachines
    private val mutableActiveProducts get() = access.mutableActiveProducts
    private val mutableMachineProductionStates get() = access.mutableMachineProductionStates
    private val mutableMachineRecipeStates get() = access.mutableMachineRecipeStates
    private val machineSpecsById get() = access.machineSpecsById

    /**
     * The recipe tick. Belt intake ([ProductionBeltIntakeSystem]) runs before it and output drain
     * ([ProductionOutputSystem]) after it, both in [SimulationPhase.PRODUCTION]; recipe-state
     * cleanup ([RecipeStateCleanupSystem]) runs in [SimulationPhase.CLEANUP].
     */
    override val phase = SimulationPhase.PRODUCTION

    override fun step(context: SystemContext) = update(context.deltaSeconds, context.workerProfilesById)

    fun update(
        deltaSeconds: Float,
        workerProfilesById: Map<String, WorkerProfile>
    ) {
        for (machine in placedMachines) {
            val machineSpec = machineSpecsById[machine.catalogId] ?: continue
            if (machineSpec.type != MachineType.PRODUCER) continue
            val recipe = machineSpec.recipe ?: continue
            tickRecipeMachine(machine, machineSpec, recipe, deltaSeconds, workerProfilesById)
        }
    }

    private fun tickRecipeMachine(
        machine: PlacedShopObject.Machine,
        machineSpec: MachineSpec,
        recipe: MachineRecipe,
        deltaSeconds: Float,
        workerProfilesById: Map<String, WorkerProfile>
    ) {
        val recipeState = ensureRecipeState(machine.id)
        val productionIndex = mutableMachineProductionStates.indexOfFirst { it.machineId == machine.id }

        if (productionIndex < 0) {
            if (recipeState.outputQueue.size >= GameConfig.machineOutputQueueCap) {
                return
            }
            if (!hasInputsFor(recipeState.inputBuffer, recipe)) {
                return
            }
            if (!canStartRecipeProduction(machine, machineSpec, workerProfilesById)) {
                return
            }
            val inputFault = recipeState.accumulatedInputFault
            replaceRecipeState(
                recipeState.copy(
                    inputBuffer = subtractInputs(recipeState.inputBuffer, recipe),
                    accumulatedInputFault = null
                )
            )
            val rolledFault = rollFaultReason(machine, machineSpec, recipe, workerProfilesById)
            val startedState = MachineProductionState(
                machineId = machine.id,
                productInstanceId = access.createProductId(),
                productId = recipe.outputProductId,
                faultReason = worstFault(inputFault, rolledFault),
                progressSeconds = 0f,
                isComplete = false
            )
            mutableMachineProductionStates += startedState
            events.publish {
                ProductionStartedEvent(
                    machineId = machine.id,
                    productInstanceId = startedState.productInstanceId,
                    productId = startedState.productId,
                    faultReason = startedState.faultReason,
                    levelId = it
                )
            }
            // Only a fault rolled here is an act of sabotage; one inherited from a spoiled input
            // was already reported when it happened upstream.
            if (rolledFault == ProductFaultReason.SABOTAGE) {
                val saboteurId = access.operatorWorkerForMachine(machine.id)?.id
                if (saboteurId != null) {
                    events.publish {
                        SabotageCommittedEvent(
                            machineId = machine.id,
                            objectId = saboteurId,
                            productInstanceId = startedState.productInstanceId,
                            productId = startedState.productId,
                            levelId = it
                        )
                    }
                }
            }
            return
        }

        val productionState = mutableMachineProductionStates[productionIndex]
        if (productionState.isComplete) {
            val queued = QueuedMachineOutput(
                productInstanceId = productionState.productInstanceId,
                productId = productionState.productId,
                faultReason = productionState.faultReason
            )
            replaceRecipeState(recipeState.copy(outputQueue = recipeState.outputQueue + queued))
            mutableMachineProductionStates.removeAt(productionIndex)
            return
        }

        val updatedProgress = (productionState.progressSeconds + deltaSeconds).coerceAtMost(recipe.durationSeconds)
        val isComplete = updatedProgress >= recipe.durationSeconds
        if (isComplete) {
            val completedState = productionState.copy(
                progressSeconds = updatedProgress,
                isComplete = true
            )
            val queued = QueuedMachineOutput(
                productInstanceId = completedState.productInstanceId,
                productId = completedState.productId,
                faultReason = completedState.faultReason
            )
            replaceRecipeState(recipeState.copy(outputQueue = recipeState.outputQueue + queued))
            mutableMachineProductionStates.removeAt(productionIndex)
            events.publish {
                ProductionCompletedEvent(
                    machineId = machine.id,
                    productInstanceId = completedState.productInstanceId,
                    productId = completedState.productId,
                    faultReason = completedState.faultReason,
                    levelId = it
                )
            }
            return
        }

        mutableMachineProductionStates[productionIndex] = productionState.copy(
            progressSeconds = updatedProgress,
            isComplete = false
        )
    }

    private fun hasInputsFor(buffer: Map<String, Int>, recipe: MachineRecipe): Boolean {
        return recipe.inputs.all { input ->
            (buffer[input.productId] ?: 0) >= input.quantity
        }
    }

    private fun subtractInputs(buffer: Map<String, Int>, recipe: MachineRecipe): Map<String, Int> {
        val result = buffer.toMutableMap()
        for (input in recipe.inputs) {
            val remaining = (result[input.productId] ?: 0) - input.quantity
            if (remaining <= 0) {
                result.remove(input.productId)
            } else {
                result[input.productId] = remaining
            }
        }
        return result
    }

    private fun canStartRecipeProduction(
        machine: PlacedShopObject.Machine,
        machineSpec: MachineSpec,
        workerProfilesById: Map<String, WorkerProfile>
    ): Boolean {
        val recipe = machineSpec.recipe ?: return false
        if (recipe.faultyProductCapacity > 0 && machine.faultyInventoryCount >= recipe.faultyProductCapacity) {
            return false
        }
        if (machineSpec.manuality == Manuality.AUTOMATIC) {
            return true
        }
        val operator = access.operatorWorkerForMachine(machine.id) ?: return false
        if (!access.isWorkerAtAssignedSlot(operator)) return false
        if (operator.carriedProductId != null || operator.movementPath.isNotEmpty()) return false
        val workerProfile = workerProfilesById[operator.catalogId] ?: return false
        return machineSpec.canAcceptOperator(workerProfile, workerProfilesById)
    }

    private fun ensureRecipeState(machineId: String): MachineRecipeState {
        val idx = mutableMachineRecipeStates.indexOfFirst { it.machineId == machineId }
        if (idx >= 0) return mutableMachineRecipeStates[idx]
        val newState = MachineRecipeState(machineId = machineId)
        mutableMachineRecipeStates += newState
        return newState
    }

    private fun replaceRecipeState(recipeState: MachineRecipeState) {
        val idx = mutableMachineRecipeStates.indexOfFirst { it.machineId == recipeState.machineId }
        if (idx >= 0) {
            mutableMachineRecipeStates[idx] = recipeState
        } else {
            mutableMachineRecipeStates += recipeState
        }
    }

    fun pruneEmptyRecipeStates() {
        mutableMachineRecipeStates.removeAll { it.isEmpty }
    }

    fun acceptBeltInputs() {
        for (machine in placedMachines) {
            val machineSpec = machineSpecsById[machine.catalogId] ?: continue
            if (machineSpec.type != MachineType.PRODUCER) continue
            val recipe = machineSpec.recipe ?: continue
            val inputSlots = access.slotPositionsFor(machine, MachineSlotType.BELT_INPUT)
            if (inputSlots.isEmpty()) continue

            for (slot in inputSlots) {
                val accessTile = slot.accessTile
                if (accessTile !in grid.beltTiles) continue
                if (grid.nextBeltTile(accessTile) != null) continue
                val product = access.productAtBeltTile(accessTile) ?: continue
                if (product.holderObjectId != null) continue
                val recipeInput = recipe.inputs.firstOrNull { it.productId == product.productId } ?: continue
                val recipeState = ensureRecipeState(machine.id)
                val currentCount = recipeState.inputBuffer[product.productId] ?: 0
                val cap = max(recipeInput.quantity, GameConfig.machineInputBufferCap)
                if (currentCount >= cap) continue

                replaceRecipeState(
                    recipeState.copy(
                        inputBuffer = recipeState.inputBuffer + (product.productId to currentCount + 1),
                        accumulatedInputFault = worstFault(
                            recipeState.accumulatedInputFault,
                            product.faultReason
                        )
                    )
                )
                val productIndex = mutableActiveProducts.indexOfFirst { it.id == product.id }
                if (productIndex >= 0) {
                    mutableActiveProducts.removeAt(productIndex)
                }
                events.publish {
                    MachineInputLoadedEvent(
                        machineId = machine.id,
                        productInstanceId = product.id,
                        productId = product.productId,
                        source = MachineInputSource.BELT,
                        levelId = it
                    )
                }
            }
        }
    }

    fun drainRecipeOutputs(workerProfilesById: Map<String, WorkerProfile>) {
        val states = mutableMachineRecipeStates.toList()
        for (recipeState in states) {
            if (recipeState.outputQueue.isEmpty()) continue
            val machine = access.findObjectById(recipeState.machineId) as? PlacedShopObject.Machine ?: continue
            val machineSpec = machineSpecsById[machine.catalogId] ?: continue
            val outputAccess = access.slotPositionsFor(machine, MachineSlotType.BELT_OUTPUT).firstOrNull()?.accessTile

            val head = recipeState.outputQueue.first()
            val placed = when {
                outputAccess != null -> tryPlaceQueuedOnBelt(machine, head, outputAccess)
                machineSpec.manuality == Manuality.HUMAN_OPERATED ->
                    tryHandQueuedToWorker(machine, head)
                else -> tryDispenseQueuedToFloor(machine, head)
            }

            if (placed) {
                val current = mutableMachineRecipeStates.firstOrNull { it.machineId == machine.id } ?: continue
                replaceRecipeState(current.copy(outputQueue = current.outputQueue.drop(1)))
            }
        }
    }

    private fun tryPlaceQueuedOnBelt(
        machine: PlacedShopObject.Machine,
        head: QueuedMachineOutput,
        accessTile: TileCoordinate
    ): Boolean {
        if (accessTile !in grid.beltTiles) return false
        if (access.isOccupied(accessTile)) return false

        mutableActiveProducts += ShopProduct(
            id = head.productInstanceId,
            productId = head.productId,
            sourceMachineId = machine.id,
            faultReason = head.faultReason,
            state = ShopProductState.ON_BELT,
            tile = accessTile
        )
        events.publish {
            ProductPlacedOnBeltEvent(
                productInstanceId = head.productInstanceId,
                productId = head.productId,
                tile = accessTile,
                byObjectId = machine.id,
                levelId = it
            )
        }
        return true
    }

    private fun tryHandQueuedToWorker(
        machine: PlacedShopObject.Machine,
        head: QueuedMachineOutput
    ): Boolean {
        val worker = access.operatorWorkerForMachine(machine.id) ?: return false
        if (!access.isWorkerAtAssignedSlot(worker) || worker.carriedProductId != null) {
            return false
        }
        val workerIndex = mutablePlacedObjects.indexOfFirst { it.id == worker.id }
        if (workerIndex < 0) return false

        mutableActiveProducts += ShopProduct(
            id = head.productInstanceId,
            productId = head.productId,
            sourceMachineId = machine.id,
            faultReason = head.faultReason,
            state = ShopProductState.CARRIED,
            carrierWorkerId = worker.id,
            holderObjectId = worker.id
        )
        mutablePlacedObjects[workerIndex] = worker.copy(
            carriedProductId = head.productInstanceId,
            movementPath = emptyList(),
            movementProgress = 0f
        )
        events.publish {
            ProductHandedOverEvent(
                objectId = machine.id,
                giverRole = null,
                recipientObjectId = worker.id,
                recipientRole = worker.workerRole,
                productInstanceId = head.productInstanceId,
                productId = head.productId,
                levelId = it
            )
        }
        return true
    }

    private fun tryDispenseQueuedToFloor(
        machine: PlacedShopObject.Machine,
        head: QueuedMachineOutput
    ): Boolean {
        val outputTile = preferredAutomaticOutputTile(machine) ?: return false
        if (access.isOccupied(outputTile)) return false

        val ontoBelt = outputTile in grid.beltTiles
        mutableActiveProducts += ShopProduct(
            id = head.productInstanceId,
            productId = head.productId,
            sourceMachineId = machine.id,
            faultReason = head.faultReason,
            state = if (ontoBelt) ShopProductState.ON_BELT else ShopProductState.ON_FLOOR,
            tile = outputTile
        )
        events.publish {
            if (ontoBelt) {
                ProductPlacedOnBeltEvent(
                    productInstanceId = head.productInstanceId,
                    productId = head.productId,
                    tile = outputTile,
                    byObjectId = machine.id,
                    levelId = it
                )
            } else {
                ProductPlacedOnFloorEvent(
                    productInstanceId = head.productInstanceId,
                    productId = head.productId,
                    tile = outputTile,
                    byObjectId = machine.id,
                    levelId = it
                )
            }
        }
        return true
    }

    private fun preferredAutomaticOutputTile(machine: PlacedShopObject.Machine): TileCoordinate? {
        val machineTiles = access.occupiedTilesFor(machine)
        return machineTiles
            .flatMap(grid::orthogonalNeighbors)
            .distinct()
            .filter { candidate -> candidate !in machineTiles && grid.isBuildable(candidate) }
            .minWithOrNull(
                compareBy<TileCoordinate> { distanceToNearestBeltTile(it) }
                    .thenByDescending { it.x }
                    .thenBy { abs(it.y - machine.position.y) }
            )
    }

    private fun distanceToNearestBeltTile(tile: TileCoordinate): Int {
        return grid.beltTiles.minOfOrNull { beltTile -> tile.manhattanDistanceTo(beltTile) } ?: Int.MAX_VALUE
    }

    private fun rollFaultReason(
        machine: PlacedShopObject.Machine,
        machineSpec: MachineSpec,
        recipe: MachineRecipe,
        workerProfilesById: Map<String, WorkerProfile>
    ): ProductFaultReason? {
        if (machineSpec.manuality == Manuality.AUTOMATIC) {
            return if (chance.roll(ChanceKind.PRODUCTION_DEFECT, recipe.defectChance)) {
                ProductFaultReason.PRODUCTION_DEFECT
            } else {
                null
            }
        }

        val operatorWorker = access.operatorWorkerForMachine(machine.id) ?: return null
        val workerProfile = workerProfilesById[operatorWorker.catalogId] ?: return null
        val workerRoleProfile = workerProfile.profileFor(WorkerRole.PRODUCER_OPERATOR) ?: return null
        val workerDefectChance = workerRoleProfile.defectChance

        return when {
            chance.roll(ChanceKind.SABOTAGE, workerRoleProfile.sabotageChance) -> ProductFaultReason.SABOTAGE
            workerDefectChance != null &&
                chance.roll(ChanceKind.PRODUCTION_DEFECT, recipe.defectChance * workerDefectChance) ->
                ProductFaultReason.PRODUCTION_DEFECT
            else -> null
        }
    }

    private fun worstFault(
        a: ProductFaultReason?,
        b: ProductFaultReason?
    ): ProductFaultReason? {
        return when {
            a == ProductFaultReason.SABOTAGE || b == ProductFaultReason.SABOTAGE -> ProductFaultReason.SABOTAGE
            a == ProductFaultReason.PRODUCTION_DEFECT || b == ProductFaultReason.PRODUCTION_DEFECT -> ProductFaultReason.PRODUCTION_DEFECT
            else -> null
        }
    }
}

/**
 * Pulls belt items sitting on a machine's belt-input slot into its recipe input buffer. Scheduled
 * in [SimulationPhase.PRODUCTION] before [ProductionSystem.step] so an input that lands this frame
 * can start production this frame.
 */
internal class ProductionBeltIntakeSystem(private val production: ProductionSystem) : SimulationSystem {
    override val phase = SimulationPhase.PRODUCTION

    override fun step(context: SystemContext) = production.acceptBeltInputs()
}

/**
 * Places finished recipe outputs onto the belt / floor / operator. Scheduled in
 * [SimulationPhase.PRODUCTION] after [ProductionSystem.step] so an output completed this frame
 * leaves the machine this frame.
 */
internal class ProductionOutputSystem(private val production: ProductionSystem) : SimulationSystem {
    override val phase = SimulationPhase.PRODUCTION

    override fun step(context: SystemContext) = production.drainRecipeOutputs(context.workerProfilesById)
}

/**
 * Drops emptied [com.faultory.core.shop.MachineRecipeState] rows. Scheduled dead last in
 * [SimulationPhase.CLEANUP], once every system that read recipe state this frame has run.
 */
internal class RecipeStateCleanupSystem(private val production: ProductionSystem) : SimulationSystem {
    override val phase = SimulationPhase.CLEANUP

    override fun step(context: SystemContext) = production.pruneEmptyRecipeStates()
}
