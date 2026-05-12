package com.faultory.editor.ui.inspector

import com.faultory.core.content.WorkerProfile
import com.faultory.core.content.WorkerRole
import com.faultory.core.content.WorkerRoleProfile
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.serializer

class WorkerRoleProfilesEditor(
    override val fieldName: String = "roleProfiles",
    initialProfiles: JsonArray,
) : PropertyEditor {

    data class RoleSlot(
        val role: WorkerRole,
        var enabled: Boolean,
        var original: JsonObject,
        var editors: List<PropertyEditor>,
    )

    private val descriptor: SerialDescriptor = serializer<WorkerRoleProfile>().descriptor
    private val ownerChain: List<SerialDescriptor> = listOf(
        serializer<WorkerProfile>().descriptor,
        descriptor,
    )

    val slots: List<RoleSlot> = WorkerRole.values().map { role ->
        val existing = initialProfiles
            .mapNotNull { it as? JsonObject }
            .firstOrNull { (it["role"] as? JsonPrimitive)?.content == role.name }
        if (existing != null) {
            RoleSlot(
                role = role,
                enabled = true,
                original = existing,
                editors = ReflectionForm.editorsFrom(ownerChain, existing),
            )
        } else {
            val template = templateFor(role)
            RoleSlot(
                role = role,
                enabled = false,
                original = template,
                editors = ReflectionForm.editorsFrom(ownerChain, template),
            )
        }
    }

    fun setEnabled(role: WorkerRole, enabled: Boolean) {
        val slot = slots.first { it.role == role }
        if (slot.enabled == enabled) return
        slot.enabled = enabled
    }

    fun resetToTemplate(role: WorkerRole) {
        val slot = slots.first { it.role == role }
        val template = templateFor(role)
        slot.original = template
        slot.editors = ReflectionForm.editorsFrom(ownerChain, template)
    }

    fun toJsonArray(): JsonArray {
        val active = slots.filter { it.enabled }.map { slot ->
            EditorCommitter.commit(slot.editors, slot.original)
        }
        return JsonArray(active)
    }

    companion object {
        fun templateFor(role: WorkerRole): JsonObject {
            val map = linkedMapOf<String, JsonElement>()
            map["role"] = JsonPrimitive(role.name)
            map["taskDurationSeconds"] = JsonPrimitive(taskDurationDefault(role))
            map["defectChance"] = if (role == WorkerRole.PRODUCER_OPERATOR) JsonPrimitive(0.1) else JsonNull
            map["sabotageChance"] = JsonPrimitive(0.0)
            map["inspectionDurationSeconds"] = if (role == WorkerRole.QA) JsonPrimitive(1.5) else JsonNull
            map["detectionAccuracy"] = if (role == WorkerRole.QA) JsonPrimitive(0.85) else JsonNull
            map["falsePositiveChance"] = JsonPrimitive(if (role == WorkerRole.QA) 0.05 else 0.0)
            map["faultyProductStrategy"] = if (role == WorkerRole.QA) JsonPrimitive("DESTROY") else JsonNull
            map["acceptedProductIds"] = JsonArray(emptyList())
            map["eyesightRadius"] = if (role == WorkerRole.SECURITY) JsonPrimitive(5.0) else JsonNull
            return JsonObject(map)
        }

        private fun taskDurationDefault(role: WorkerRole): Double = when (role) {
            WorkerRole.PRODUCER_OPERATOR -> 1.5
            WorkerRole.QA -> 1.5
            WorkerRole.SECURITY -> 0.0
            WorkerRole.CLEANER -> 0.0
        }
    }
}
