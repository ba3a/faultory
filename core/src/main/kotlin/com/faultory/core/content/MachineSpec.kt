package com.faultory.core.content

import com.faultory.core.shop.Orientation
import com.faultory.core.shop.TileCoordinate
import com.faultory.core.shop.plus
import kotlinx.serialization.Serializable

@Serializable
data class MachineSpec(
    val id: String,
    val displayName: String,
    val level: Int,
    val type: MachineType,
    val manuality: Manuality,
    val skin: String,
    val productIds: List<String>,
    val shape: List<MachineShapeTile> = listOf(MachineShapeTile(0, 0)),
    val slots: List<MachineSlotSpec> = emptyList(),
    val minimumOperatorWorkerIds: List<String> = emptyList(),
    val installCost: Int,
    val operationDurationSeconds: Float,
    val upgradeTree: BinaryUpgradeTree? = null,
    val producerProfile: ProducerMachineProfile? = null,
    val qaProfile: QaMachineProfile? = null
) {
    fun requiresOperator(): Boolean {
        return manuality == Manuality.HUMAN_OPERATED
    }

    fun requiredOperatorRole(): WorkerRole? {
        if (!requiresOperator()) {
            return null
        }

        return when (type) {
            MachineType.PRODUCER -> WorkerRole.PRODUCER_OPERATOR
            MachineType.QA -> WorkerRole.QA
        }
    }

    fun canBeOperatedBy(
        worker: WorkerProfile,
        workersById: Map<String, WorkerProfile>
    ): Boolean {
        if (!requiresOperator()) {
            return false
        }

        val requiredRole = requiredOperatorRole() ?: return false
        if (worker.profileFor(requiredRole) == null) {
            return false
        }

        return minimumOperatorWorkerIds.any { rootWorkerId ->
            worker.isSameOrHigherOnUpgradeBranch(rootWorkerId, workersById)
        }
    }

    fun canAcceptOperator(
        worker: WorkerProfile,
        workersById: Map<String, WorkerProfile>
    ): Boolean {
        return canBeOperatedBy(worker, workersById)
    }

    fun occupiedTiles(
        anchorTile: TileCoordinate,
        orientation: Orientation
    ): Set<TileCoordinate> {
        return shape
            .map { anchorTile + orientation.rotate(it.asTileCoordinate()) }
            .toSet()
    }

    fun slotPositions(
        anchorTile: TileCoordinate,
        orientation: Orientation,
        type: MachineSlotType? = null
    ): List<MachineSlotPosition> {
        return slots
            .asSequence()
            .filter { slot -> type == null || slot.type == type }
            .mapIndexed { slotIndex, slot ->
                val rotatedLocalTile = orientation.rotate(slot.asTileCoordinate())
                val machineTile = anchorTile + rotatedLocalTile
                val worldSide = orientation.rotate(slot.side)
                MachineSlotPosition(
                    slotIndex = slotIndex,
                    type = slot.type,
                    machineTile = machineTile,
                    accessTile = machineTile + worldSide.step(),
                    side = worldSide
                )
            }
            .toList()
    }
}

@Serializable
enum class MachineType {
    PRODUCER,
    QA
}

@Serializable
enum class Manuality {
    HUMAN_OPERATED,
    AUTOMATIC
}

@Serializable
data class ProducerMachineProfile(
    val defectChance: Float
)

@Serializable
data class QaMachineProfile(
    val detectionAccuracy: Float
)
