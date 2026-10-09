package com.faultory.core.shop

import com.faultory.core.content.MachineType
import kotlinx.serialization.Serializable

@Serializable
data class ShopBlueprint(
    val id: String,
    val displayName: String,
    val qualityThresholdPercent: Float,
    val shiftLengthSeconds: Float,
    /**
     * The buildable floor, in absolute world tiles. The single source of the floor's extent:
     * [ShopGrid] and everything that asks it which tiles exist read this, never the screen layout
     * in [com.faultory.core.config.GameConfig].
     */
    val floor: TileRect = defaultFloor,
    /** Where the shop-floor camera rests and how far it may overscroll; see [CameraFraming]. */
    val camera: CameraFraming = defaultCamera,
    val conveyorBelts: List<ConveyorBelt>,
    val machineSlots: List<MachineSlot>,
    val workerSpawnPoints: List<WorkerSpawnPoint>,
    val floorTuning: FloorTuning = FloorTuning.default()
) {
    companion object {
        /** The 40x16 floor every shop had while its extent was derived from the screen layout:
         * the band between the bank and the HUD at the virtual resolution. */
        val defaultFloor = TileRect(x = 0, y = 4, width = 40, height = 16)

        /**
         * Rests on [defaultFloor], so at zoom 1 the window is locked to the framing the game always
         * had, and leaves a few tiles of overscroll beyond what the half-zoom window shows around it.
         */
        val defaultCamera = CameraFraming(
            rest = defaultFloor,
            limit = TileRect(x = -24, y = -8, width = 88, height = 40)
        )
    }
}

@Serializable
data class FloorTuning(
    val conveyorSpeedTilesPerSecond: Float = 2.5f,
    val machineInputBufferCap: Int = 5,
    val machineOutputQueueCap: Int = 3,
    val beltEnterDurationSeconds: Float = 0.3f,
    val beltRideDurationSeconds: Float = 0.4f,
    val beltExitDurationSeconds: Float = 0.3f,
    val wetTileBaseSeconds: Float = 6f,
    val wetTileJitterSeconds: Float = 2f,
    val wetTileSlipBaseChance: Float = 0.25f,
    val wetTileSlipJitterChance: Float = 0.1f,
    val unitFallSeconds: Float = 0.4f,
    val unitLyingBaseSeconds: Float = 1.4f,
    val unitLyingJitterSeconds: Float = 0.6f,
    val unitStandingSeconds: Float = 0.4f,
    val destroyProductSeconds: Float = 1.0f
) {
    companion object {
        /** The baseline tuning every shop had before this became authorable - cheap to construct
         * for a shop JSON that omits the block, an editor "new shop" scaffold, or a future
         * in-game settings reset. */
        fun default(): FloorTuning = FloorTuning()
    }
}

@Serializable
data class ConveyorBelt(
    val id: String,
    val checkpoints: List<BeltNode>,
    val skin: String? = null
)

@Serializable
data class BeltNode(
    val x: Float,
    val y: Float
)

@Serializable
data class MachineSlot(
    val id: String,
    val type: MachineType,
    val installedMachineId: String? = null,
    val x: Float,
    val y: Float,
    val interactionRadius: Float = 28f
)

@Serializable
data class WorkerSpawnPoint(
    val id: String,
    val x: Float,
    val y: Float
)
