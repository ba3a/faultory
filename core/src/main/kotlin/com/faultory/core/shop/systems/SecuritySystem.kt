package com.faultory.core.shop.systems

import com.faultory.core.config.GameConfig
import com.faultory.core.content.MachineType
import com.faultory.core.content.WorkerProfile
import com.faultory.core.content.WorkerRole
import com.faultory.core.content.WorkerRoleProfile
import com.faultory.core.shop.Orientation
import com.faultory.core.shop.PlacedShopObject
import com.faultory.core.shop.plus
import com.faultory.core.shop.PlacedShopObjectKind
import com.faultory.core.shop.ProductFaultReason
import com.faultory.core.shop.TileCoordinate
import kotlin.random.Random

internal class SecuritySystem(
    private val state: ShopFloorState,
    private val random: Random
) {
    private val mutablePlacedObjects get() = state.mutablePlacedObjects
    private val mutableMachineProductionStates get() = state.mutableMachineProductionStates
    private val machineSpecsById get() = state.machineSpecsById
    private val grid get() = state.grid

    fun update(workerProfilesById: Map<String, WorkerProfile>) {
        val securityWorkers = mutablePlacedObjects
            .filter { it.kind == PlacedShopObjectKind.WORKER && it.workerRole == WorkerRole.SECURITY }
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
        return mutablePlacedObjects.filter { worker ->
            worker.kind == PlacedShopObjectKind.WORKER &&
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
            return
        }

        if (state.manhattanDistance(security.position, target.position) <= 1) {
            cancelSabotage(target.assignedMachineId ?: return)
            pursuedSaboteurIds.remove(targetId)
            val orientation = Orientation.between(security.position, target.position) ?: security.orientation
            mutablePlacedObjects[securityIndex] = security.copy(
                pursuitTargetWorkerId = null,
                movementPath = emptyList(),
                movementProgress = 0f,
                orientation = orientation
            )
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
            .filter { it.workerRole == WorkerRole.SECURITY }
            .filter { it.assignedMachineId == null }
            .filter { it.pursuitTargetWorkerId == null }
            .filter { it.carriedProductId == null }
            .firstOrNull()
    }

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
        return grid.findPath(
            start = security.position,
            goals = standTiles,
            blockedTiles = state.blockedTilesForPath(ignoreWorkerId = security.id)
        )
    }

    private fun planSecurityRoamingPath(security: PlacedShopObject): List<TileCoordinate> {
        val blocked = state.blockedTilesForPath(ignoreWorkerId = security.id)

        if (random.nextFloat() < GameConfig.securityRoamBeltTripChance) {
            val beltTrip = pickBeltRoamingPath(security, blocked)
            if (beltTrip.isNotEmpty()) {
                return beltTrip
            }
        }

        val orientations = Orientation.entries.toList().shuffled(random)
        for (orientation in orientations) {
            val path = straightLinePath(security.position, orientation, blocked)
            if (path.isNotEmpty()) {
                return path
            }
        }
        return emptyList()
    }

    private fun pickBeltRoamingPath(
        security: PlacedShopObject,
        blocked: Set<TileCoordinate>
    ): List<TileCoordinate> {
        val candidates = grid.beltTiles
            .filter { tile -> tile != security.position && tile !in blocked }
            .filter { tile -> state.manhattanDistance(security.position, tile) >= GameConfig.securityRoamMinSteps }
        if (candidates.isEmpty()) return emptyList()
        val target = candidates.random(random)
        return grid.findPath(security.position, setOf(target), blocked) ?: emptyList()
    }

    private fun straightLinePath(
        start: TileCoordinate,
        orientation: Orientation,
        blocked: Set<TileCoordinate>
    ): List<TileCoordinate> {
        val step = orientation.step()
        val targetLength = random.nextInt(
            GameConfig.securityRoamMinSteps,
            GameConfig.securityRoamMaxSteps + 1
        )
        val path = mutableListOf<TileCoordinate>()
        var current = start
        while (path.size < targetLength) {
            val next = current + step
            if (!grid.isBuildable(next) || next in blocked) break
            path += next
            current = next
        }
        return path
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
