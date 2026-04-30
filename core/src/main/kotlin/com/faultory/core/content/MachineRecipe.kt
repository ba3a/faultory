package com.faultory.core.content

import kotlinx.serialization.Serializable

@Serializable
data class MachineRecipe(
    val inputs: List<RecipeInput>,
    val outputProductId: String,
    val durationSeconds: Float
)

@Serializable
data class RecipeInput(
    val productId: String,
    val quantity: Int = 1
)
