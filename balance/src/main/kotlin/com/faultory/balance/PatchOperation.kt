package com.faultory.balance

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

enum class PatchOp { SET, ADD, MUL, UNSET }

@Serializable
data class PatchOperation(
    val op: PatchOp,
    val path: String,
    val value: JsonElement = JsonNull
)
