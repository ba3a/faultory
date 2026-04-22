package com.faultory.editor.ui.inspector

import com.faultory.core.content.BinaryUpgradeTree
import com.faultory.core.content.LevelDefinition
import com.faultory.core.content.MachineSpec
import com.faultory.core.content.WorkerProfile
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.serializer
import kotlin.reflect.KClass

enum class CatalogType {
    PRODUCT,
    WORKER,
    MACHINE,
}

object IdReferenceRegistry {

    private data class Key(val ownerSerialNames: List<String>, val fieldName: String)

    private val map: Map<Key, CatalogType> = buildMap {
        put(key(MachineSpec::class, "productIds"), CatalogType.PRODUCT)
        put(key(MachineSpec::class, "minimumOperatorWorkerIds"), CatalogType.WORKER)
        put(key(LevelDefinition::class, "availableWorkerIds"), CatalogType.WORKER)
        put(key(LevelDefinition::class, "availableMachineIds"), CatalogType.MACHINE)
        put(key(WorkerProfile::class, BinaryUpgradeTree::class, "leftUpgradeId"), CatalogType.WORKER)
        put(key(WorkerProfile::class, BinaryUpgradeTree::class, "rightUpgradeId"), CatalogType.WORKER)
        put(key(MachineSpec::class, BinaryUpgradeTree::class, "leftUpgradeId"), CatalogType.MACHINE)
        put(key(MachineSpec::class, BinaryUpgradeTree::class, "rightUpgradeId"), CatalogType.MACHINE)
    }

    fun lookup(ownerSerialNames: List<String>, fieldName: String): CatalogType? {
        return map[Key(ownerSerialNames, fieldName)]
    }

    fun lookupByDescriptors(ownerChain: List<SerialDescriptor>, fieldName: String): CatalogType? {
        return lookup(ownerChain.map { it.serialName.removeSuffix("?") }, fieldName)
    }

    private fun key(owner: KClass<*>, fieldName: String): Key {
        return Key(listOf(serialNameOf(owner)), fieldName)
    }

    private fun key(outer: KClass<*>, inner: KClass<*>, fieldName: String): Key {
        return Key(listOf(serialNameOf(outer), serialNameOf(inner)), fieldName)
    }

    private fun serialNameOf(kClass: KClass<*>): String {
        return serializerDescriptor(kClass).serialName
    }

    private fun serializerDescriptor(kClass: KClass<*>): SerialDescriptor {
        return when (kClass) {
            MachineSpec::class -> serializer<MachineSpec>().descriptor
            WorkerProfile::class -> serializer<WorkerProfile>().descriptor
            LevelDefinition::class -> serializer<LevelDefinition>().descriptor
            BinaryUpgradeTree::class -> serializer<BinaryUpgradeTree>().descriptor
            else -> error("No serializer registered for ${kClass.qualifiedName}")
        }
    }
}
