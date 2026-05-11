package com.faultory.core.encounters

import com.faultory.core.save.EncounterProgress
import com.faultory.core.save.GameSave
import com.faultory.core.save.SaveRepository
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ConditionRefTest {

    @Test
    fun `Ref resolves and evaluates body`() {
        val library = ConditionLibrary(
            namedConditions = listOf(NamedCondition("always-cond", Condition.Always))
        )
        assertTrue(Condition.Ref("always-cond").evaluate(ctx(library)))
    }

    @Test
    fun `Ref evaluates referenced Never condition`() {
        val library = ConditionLibrary(
            namedConditions = listOf(NamedCondition("never-cond", Condition.Never))
        )
        assertFalse(Condition.Ref("never-cond").evaluate(ctx(library)))
    }

    @Test
    fun `Ref throws on unknown refId`() {
        val library = ConditionLibrary()
        assertFailsWith<IllegalStateException> {
            Condition.Ref("missing").evaluate(ctx(library))
        }
    }

    @Test
    fun `validate passes on acyclic chain`() {
        val library = ConditionLibrary(
            namedConditions = listOf(
                NamedCondition("a", Condition.Ref("b")),
                NamedCondition("b", Condition.Always)
            )
        )
        library.validate()
    }

    @Test
    fun `validate throws on self-referential cycle`() {
        val library = ConditionLibrary(
            namedConditions = listOf(NamedCondition("a", Condition.Ref("a")))
        )
        assertFailsWith<IllegalStateException> {
            library.validate()
        }
    }

    @Test
    fun `validate throws on mutual cycle`() {
        val library = ConditionLibrary(
            namedConditions = listOf(
                NamedCondition("a", Condition.Ref("b")),
                NamedCondition("b", Condition.Ref("a"))
            )
        )
        assertFailsWith<IllegalStateException> {
            library.validate()
        }
    }

    @Test
    fun `validate throws on longer cycle A to B to C to A`() {
        val library = ConditionLibrary(
            namedConditions = listOf(
                NamedCondition("a", Condition.Ref("b")),
                NamedCondition("b", Condition.Ref("c")),
                NamedCondition("c", Condition.Ref("a"))
            )
        )
        assertFailsWith<IllegalStateException> {
            library.validate()
        }
    }

    private fun ctx(library: ConditionLibrary) = EvaluationContext(
        saveRepository = object : SaveRepository {
            override fun hasSlot(slotId: String) = false
            override fun load(slotId: String): GameSave? = null
            override fun save(save: GameSave) {}
        },
        encounterProgress = EncounterProgress(),
        conditionLibrary = library
    )
}
