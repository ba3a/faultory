package com.faultory.core.encounters

import com.faultory.core.save.EncounterProgress
import com.faultory.core.save.GameSave
import com.faultory.core.save.SaveRepository
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConditionRandomTest {

    @Test
    fun `random condition evaluates true when seeded roll is below probability`() {
        val ctx = context(random = Random(0L))
        assertTrue(Condition.Random(1f).evaluate(ctx))
    }

    @Test
    fun `random condition evaluates false when probability is zero`() {
        val ctx = context(random = Random(0L))
        assertFalse(Condition.Random(0f).evaluate(ctx))
    }

    @Test
    fun `random condition over many rolls converges to probability`() {
        val random = Random(123L)
        val ctx = EvaluationContext(
            saveRepository = emptySaveRepository(),
            encounterProgress = EncounterProgress(),
            conditionLibrary = ConditionLibrary(),
            random = random
        )
        val trials = 5000
        val hits = (0 until trials).count { Condition.Random(0.3f).evaluate(ctx) }
        val rate = hits.toDouble() / trials
        assertTrue(rate in 0.27..0.33, "expected ~0.30, got $rate")
    }

    private fun context(random: Random): EvaluationContext = EvaluationContext(
        saveRepository = emptySaveRepository(),
        encounterProgress = EncounterProgress(),
        conditionLibrary = ConditionLibrary(),
        random = random
    )

    private fun emptySaveRepository(): SaveRepository = object : SaveRepository {
        override fun hasSlot(slotId: String): Boolean = false
        override fun load(slotId: String): GameSave? = null
        override fun save(save: GameSave) {}
    }
}
