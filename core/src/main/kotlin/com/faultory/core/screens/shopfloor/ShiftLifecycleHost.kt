package com.faultory.core.screens.shopfloor

import com.faultory.core.content.LevelDefinition
import com.faultory.core.save.SaveRepository

interface ShiftLifecycleHost {
    val saveRepository: SaveRepository
    fun openLevel(level: LevelDefinition)
    fun openLevelSelection()
}
