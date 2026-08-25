package com.faultory.core.content

import kotlinx.serialization.Serializable

@Serializable
data class ProductDefinition(
    val id: String,
    val saleValue: Int,
    val skin: String = ""
)
