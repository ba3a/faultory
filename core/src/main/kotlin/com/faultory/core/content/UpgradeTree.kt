package com.faultory.core.content

import kotlinx.serialization.Serializable

@Serializable
data class BinaryUpgradeTree(
    val leftUpgradeId: String? = null,
    val rightUpgradeId: String? = null
) {
    fun upgradeIds(): List<String> {
        return listOfNotNull(leftUpgradeId, rightUpgradeId)
    }
}
