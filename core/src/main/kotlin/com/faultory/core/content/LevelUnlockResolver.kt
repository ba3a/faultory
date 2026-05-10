package com.faultory.core.content

object LevelUnlockResolver {
    fun isUnlocked(level: LevelDefinition, starsEarnedFor: (String) -> Int): Boolean {
        return missingPrerequisites(level, starsEarnedFor).isEmpty()
    }

    fun missingPrerequisites(level: LevelDefinition, starsEarnedFor: (String) -> Int): List<String> {
        return level.requiredLevelIds.filter { prereqId -> starsEarnedFor(prereqId) < 1 }
    }
}
