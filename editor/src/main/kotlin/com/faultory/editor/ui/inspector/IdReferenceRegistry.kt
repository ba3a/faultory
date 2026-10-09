package com.faultory.editor.ui.inspector

import com.faultory.core.content.BinaryUpgradeTree
import com.faultory.core.content.LevelDefinition
import com.faultory.core.content.MachineSpec
import com.faultory.core.content.TileCorners
import com.faultory.core.content.TileDefinition
import com.faultory.core.content.WorkerProfile
import com.faultory.core.content.WorkerRoleProfile
import com.faultory.core.shop.ScenerySpec
import com.faultory.core.shop.ShopBlueprint
import com.faultory.core.shop.TerrainPatch
import com.faultory.core.shop.TerrainRegion
import com.faultory.core.shop.TilePin
import com.faultory.core.shop.WallPin
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.serializer
import kotlin.reflect.KClass

enum class CatalogType {
    PRODUCT,
    WORKER,
    MACHINE,
    LEVEL,
    TERRAIN,
    TILE,
    WALL,
    STYLE,
}

object IdReferenceRegistry {

    private data class Key(val ownerSerialNames: List<String>, val fieldName: String)

    private val map: Map<Key, CatalogType> = buildMap {
        put(key(MachineSpec::class, field = "productIds"), CatalogType.PRODUCT)
        put(key(MachineSpec::class, field = "minimumOperatorWorkerIds"), CatalogType.WORKER)
        put(key(LevelDefinition::class, field = "availableWorkerIds"), CatalogType.WORKER)
        put(key(LevelDefinition::class, field = "availableMachineIds"), CatalogType.MACHINE)
        put(key(WorkerProfile::class, BinaryUpgradeTree::class, field = "leftUpgradeId"), CatalogType.WORKER)
        put(key(WorkerProfile::class, BinaryUpgradeTree::class, field = "rightUpgradeId"), CatalogType.WORKER)
        put(key(MachineSpec::class, BinaryUpgradeTree::class, field = "leftUpgradeId"), CatalogType.MACHINE)
        put(key(MachineSpec::class, BinaryUpgradeTree::class, field = "rightUpgradeId"), CatalogType.MACHINE)
        put(key(WorkerProfile::class, WorkerRoleProfile::class, field = "acceptedProductIds"), CatalogType.PRODUCT)
        for (corner in listOf("northWest", "northEast", "southEast", "southWest")) {
            put(key(TileDefinition::class, TileCorners::class, field = corner), CatalogType.TERRAIN)
        }
        put(key(ShopBlueprint::class, ScenerySpec::class, field = "floorTerrain"), CatalogType.TERRAIN)
        put(key(ShopBlueprint::class, ScenerySpec::class, field = "outsideTerrain"), CatalogType.TERRAIN)
        put(key(ShopBlueprint::class, ScenerySpec::class, field = "styleId"), CatalogType.STYLE)
        put(key(ShopBlueprint::class, ScenerySpec::class, TerrainRegion::class, field = "terrain"), CatalogType.TERRAIN)
        put(key(ShopBlueprint::class, ScenerySpec::class, TerrainPatch::class, field = "terrain"), CatalogType.TERRAIN)
        put(key(ShopBlueprint::class, ScenerySpec::class, TilePin::class, field = "tileId"), CatalogType.TILE)
        put(key(ShopBlueprint::class, ScenerySpec::class, WallPin::class, field = "wallId"), CatalogType.WALL)
    }

    fun lookup(ownerSerialNames: List<String>, fieldName: String): CatalogType? {
        return map[Key(ownerSerialNames, fieldName)]
    }

    fun lookupByDescriptors(ownerChain: List<SerialDescriptor>, fieldName: String): CatalogType? {
        return lookup(ownerChain.map { it.serialName.removeSuffix("?") }, fieldName)
    }

    /** The field [field] of the class at the end of the owner chain [owners], outermost first. */
    private fun key(vararg owners: KClass<*>, field: String): Key =
        Key(owners.map { serializer(it.java).descriptor.serialName }, field)
}
