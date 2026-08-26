package com.faultory.core.shop.systems

import com.faultory.core.content.MachineType
import com.faultory.core.content.WorkerProfile
import com.faultory.core.content.WorkerRole
import com.faultory.core.content.WorkerRoleProfile
import com.faultory.core.encounters.PursuitAbandonedEvent
import com.faultory.core.encounters.SaboteurSpottedEvent
import com.faultory.core.encounters.SabotageStoppedEvent
import com.faultory.core.encounters.ShopFloorEvents
import com.faultory.core.shop.Orientation
import com.faultory.core.shop.PlacedShopObject
import com.faultory.core.shop.ProductFaultReason
import com.faultory.core.shop.TileCoordinate
import com.faultory.core.shop.pathfinding.MovementStrategyResolver
import kotlin.random.Random

internal class SecuritySystem(
    private val state: ShopFloorState,
    private val movementStrategyResolver: MovementStrategyResolver,
    private val random: Random,
    private val events: ShopFloorEvents = ShopFloorEvents()
) {
    private val mutablePlacedObjects get() = state.mutablePlacedObjects
    private val placedWorkers get() = state.placedWorkers
    private val placedSecurityWorkers get() = state.placedSecurityWorkers
    private val mutableMachineProductionStates get() = state.mutableMachineProductionStates
    private val machineSpecsById get() = state.machineSpecsById
    private val grid get() = state.grid

    fun update(workerProfilesById: Map<String, WorkerProfile>) {
        val securityWorkers = placedSecurityWorkers
        if (securityWorkers.isEmpty()) return

        val saboteursById = activeSaboteurs().associateBy { it.id }
        val pursuedSaboteurIds = securityWorkers
            .mapNotNull { it.pursuitTargetWorkerId }
            .filter { it in saboteursById }
            .toMutableSet()

        for (security in securityWorkers) {
            val freshIndex = mutablePlacedObjects.indexOfFirst { it.id == security.id }
            if (freshIndex < 0) continue
            val current = mutablePlacedObjects[freshIndex]
            val profile = workerProfilesById[current.catalogId] ?: continue
            val roleProfile = profile.profileFor(WorkerRole.SECURITY) ?: continue

            if (current.pursuitTargetWorkerId != null) {
                handleSecurityPursuit(freshIndex, current, saboteursById, pursuedSaboteurIds)
                continue
            }

            if (isSecurityOnDutyAtCamera(current)) {
                dispatchFromCamera(current, securityWorkers, saboteursById, pursuedSaboteurIds)
                continue
            }

            handleRoamingSecurity(freshIndex, current, profile, roleProfile, saboteursById, pursuedSaboteurIds)
        }
    }

    private fun activeSaboteurs(): List<PlacedShopObject> {
        val sabotagingMachineIds = mutableMachineProductionStates
            .asSequence()
            .filter { !it.isComplete && it.faultReason == ProductFaultReason.SABOTAGE }
            .map { it.machineId }
            .toSet()
        if (sabotagingMachineIds.isEmpty()) return emptyList()
        return placedWorkers.filter { worker ->
            worker.workerRole == WorkerRole.PRODUCER_OPERATOR &&
                worker.assignedMachineId in sabotagingMachineIds &&
                state.isWorkerAtAssignedSlot(worker)
        }
    }

    private fun isSecurityOnDutyAtCamera(security: PlacedShopObject): Boolean {
        val machineId = security.assignedMachineId ?: return false
        val machine = state.findObjectById(machineId) ?: return false
        val machineSpec = machineSpecsById[machine.catalogId] ?: return false
        if (machineSpec.type != MachineType.SECURITY_CAMERA) return false
        return state.isWorkerAtAssignedSlot(security) && security.movementPath.isEmpty()
    }

    private fun handleSecurityPursuit(
        securityIndex: Int,
        security: PlacedShopObject,
        saboteursById: Map<String, PlacedShopObject>,
        pursuedSaboteurIds: MutableSet<String>
    ) {
        val targetId = security.pursuitTargetWorkerId ?: return
        val target = saboteursById[targetId]
        if (target == null) {
            pursuedSaboteurIds.remove(targetId)
            mutablePlacedObjects[securityIndex] = security.copy(
                pursuitTargetWorkerId = null,
                movementPath = emptyList(),
                movementProgress = 0f
            )
            events.publish {
                PursuitAbandonedEvent(objectId = security.id, saboteurObjectId = targetId, levelId = it)
            }
            return
        }

        if (state.manhattanDistance(security.position, target.position) <= 1) {
            val sabotagedMachineId = target.assignedMachineId ?: return
            cancelSabotage(sabotagedMachineId)
            pursuedSaboteurIds.remove(targetId)
            val orientation = Orientation.between(security.position, target.position) ?: security.orientation
            mutablePlacedObjects[securityIndex] = security.copy(
                pursuitTargetWorkerId = null,
                movementPath = emptyList(),
                movementProgress = 0f,
                orientation = orientation
            )
            events.publish {
                SabotageStoppedEvent(
                    objectId = security.id,
                    saboteurObjectId = targetId,
                    machineId = sabotagedMachineId,
                    levelId = it
                )
            }
            return
        }

        if (security.movementPath.isEmpty() || !isPathStillValid(security)) {
            val path = planSecurityPursuit(security, target) ?: return
            if (path.isEmpty()) return
            mutablePlacedObjects[securityIndex] = security.copy(
                movementPath = path,
                movementProgress = 0f,
                orientation = Orientation.between(security.position, path.first()) ?: security.orientation
            )
        }
    }

    private fun dispatchFromCamera(
        cameraSecurity: PlacedShopObject,
        allSecurityWorkers: List<PlacedShopObject>,
        saboteursById: Map<String, PlacedShopObject>,
        pursuedSaboteurIds: MutableSet<String>
    ) {
        val unattended = saboteursById.values.filter { it.id !in pursuedSaboteurIds }
        if (unattended.isEmpty()) return

        val unattendedSorted = unattended.sortedWith(
            compareBy<PlacedShopObject> { state.manhattanDistance(it.position, cameraSecurity.position) }.thenBy { it.id }
        )

        for (saboteur in unattendedSorted) {
            if (saboteur.id in pursuedSaboteurIds) continue
            val freeOnFoot = freeOnFootSecurity(allSecurityWorkers, excludeId = cameraSecurity.id)
            val pursuer = if (freeOnFoot != null) {
                freeOnFoot
            } else {
                cameraSecurity
            }
            assignPursuer(pursuer, saboteur)
            pursuedSaboteurIds += saboteur.id
            if (pursuer.id == cameraSecurity.id) {
                return
            }
        }
    }

    private fun handleRoamingSecurity(
        securityIndex: Int,
        security: PlacedShopObject,
        profile: WorkerProfile,
        roleProfile: WorkerRoleProfile,
        saboteursById: Map<String, PlacedShopObject>,
        pursuedSaboteurIds: MutableSet<String>
    ) {
        val eyesightRadius = roleProfile.eyesightRadius
        if (eyesightRadius != null && eyesightRadius > 0f) {
            val visible = saboteursById.values
                .filter { it.id !in pursuedSaboteurIds }
                .filter { euclideanDistance(security.position, it.position) <= eyesightRadius }
                .sortedWith(
                    compareBy<PlacedShopObject> { euclideanDistance(security.position, it.position) }.thenBy { it.id }
                )
            val target = visible.firstOrNull()
            if (target != null) {
                pursuedSaboteurIds += target.id
                assignPursuer(security, target)
                return
            }
        }

        if (security.movementPath.isEmpty()) {
            val path = planSecurityRoamingPath(security)
            if (path.isNotEmpty()) {
                mutablePlacedObjects[securityIndex] = security.copy(
                    movementPath = path,
                    movementProgress = 0f,
                    orientation = Orientation.between(security.position, path.first()) ?: security.orientation
                )
            }
        }
    }

    private fun freeOnFootSecurity(
        allSecurityWorkers: List<PlacedShopObject>,
        excludeId: String
    ): PlacedShopObject? {
        return allSecurityWorkers
            .asSequence()
            .filter { it.id != excludeId }
            .map { state.findObjectById(it.id) ?: it }
            .filter { it.assignedMachineId == null }
            .filter { it.pursuitTargetWorkerId == null }
            .filter { it.carriedProductId == null }
            .firstOrNull()
    }

    /** The single place a saboteur becomes someone's target, whether spotted on foot or on camera. */
    private fun assignPursuer(pursuer: PlacedShopObject, target: PlacedShopObject) {
        val pursuerIndex = mutablePlacedObjects.indexOfFirst { it.id == pursuer.id }
        if (pursuerIndex < 0) return
        val current = mutablePlacedObjects[pursuerIndex]
        val path = planSecurityPursuit(current, target)
        mutablePlacedObjects[pursuerIndex] = current.copy(
            pursuitTargetWorkerId = target.id,
            movementPath = path ?: emptyList(),
            movementProgress = 0f,
            orientation = path?.firstOrNull()
                ?.let { Orientation.between(current.position, it) }
                ?: current.orientation
        )
        val sabotagedMachineId = target.assignedMachineId ?: return
        events.publish {
            SaboteurSpottedEvent(
                objectId = current.id,
                saboteurObjectId = target.id,
                machineId = sabotagedMachineId,
                levelId = it
            )
        }
    }

    private fun planSecurityPursuit(
        security: PlacedShopObject,
        target: PlacedShopObject
    ): List<TileCoordinate>? {
        val standTiles = grid.orthogonalNeighbors(target.position)
            .filter { tile ->
                grid.isBuildable(tile) &&
                    (tile == security.position || !state.isOccupied(tile, ignoreObjectId = security.id))
            }
            .toSet()
        if (standTiles.isEmpty()) {
            return null
        }
        if (security.position in standTiles) {
            return emptyList()
        }
        return movementStrategyResolver.strategyFor(security).pathFinder.findPath(
            grid = grid,
            start = security.position,
            goals = standTiles,
            blockedTiles = state.blockedTilesForPath(ignoreWorkerId = security.id)
        )
    }

    private fun planSecurityRoamingPath(security: PlacedShopObject): List<TileCoordinate> {
        val roamer = movementStrategyResolver.strategyFor(security).roamer ?: return emptyList()
        val blocked = state.blockedTilesForPath(ignoreWorkerId = security.id)
        return roamer.nextRoam(grid, security.position, blocked, random)
    }

    private fun isPathStillValid(worker: PlacedShopObject): Boolean {
        return worker.movementPath.none { tile ->
            state.isOccupied(tile, ignoreObjectId = worker.id, ignoreProductId = worker.carriedProductId)
        }
    }

    private fun cancelSabotage(machineId: String) {
        val index = mutableMachineProductionStates.indexOfFirst { it.machineId == machineId }
        if (index < 0) return
        val productionState = mutableMachineProductionStates[index]
        if (productionState.faultReason != ProductFaultReason.SABOTAGE || productionState.isComplete) return
        mutableMachineProductionStates[index] = productionState.copy(
            faultReason = null,
            progressSeconds = 0f
        )
    }

    private fun euclideanDistance(a: TileCoordinate, b: TileCoordinate): Float {
        val dx = (a.x - b.x).toFloat()
        val dy = (a.y - b.y).toFloat()
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }
}
