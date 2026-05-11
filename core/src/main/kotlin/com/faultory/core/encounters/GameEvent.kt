package com.faultory.core.encounters

import com.faultory.core.shop.PlacedShopObjectKind

sealed interface GameEvent

data class ProductShippedEvent(
    val productId: String,
    val quality: ProductQuality,
    val levelId: String
) : GameEvent

data class LevelCompletedEvent(
    val levelId: String,
    val starsEarned: Int,
    val passed: Boolean
) : GameEvent

data class ObjectPlacedEvent(
    val kind: PlacedShopObjectKind,
    val catalogId: String
) : GameEvent

data class ShiftStartedEvent(val levelId: String) : GameEvent
