package com.faultory.core.content

import com.faultory.core.save.SaveRepository

object LevelUnlockResolver {
    fun isUnlocked(level: LevelDefinition, saveRepository: SaveRepository): Boolean {
        return missingPrerequisites(level, saveRepository).isEmpty()
    }

    fun missingPrerequisites(level: LevelDefinition, saveRepository: SaveRepository): List<String> {
        return level.requiredLevelIds.filter { prereqId ->
            (saveRepository.load(prereqId)?.lastCompletedRun?.starsEarned ?: 0) < 1
        }
    }
}
