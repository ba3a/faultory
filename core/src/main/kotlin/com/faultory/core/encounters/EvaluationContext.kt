package com.faultory.core.encounters

import com.faultory.core.save.EncounterProgress
import com.faultory.core.save.SaveRepository
import com.faultory.core.shop.PlacedShopObject

class EvaluationContext(
    val saveRepository: SaveRepository,
    val encounterProgress: EncounterProgress,
    val conditionLibrary: ConditionLibrary,
    val currentLevelId: String? = null,
    val placedObjects: List<PlacedShopObject>? = null
)
