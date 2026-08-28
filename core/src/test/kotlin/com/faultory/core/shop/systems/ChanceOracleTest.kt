package com.faultory.core.shop.systems

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChanceOracleTest {
    @Test
    fun `a probability of 1 always rolls true`() {
        val oracle = RandomChanceOracle(Random(0L))
        assertTrue(oracle.roll(ChanceKind.SABOTAGE, 1f))
    }

    @Test
    fun `a probability of 0 always rolls false`() {
        val oracle = RandomChanceOracle(Random(0L))
        assertFalse(oracle.roll(ChanceKind.SABOTAGE, 0f))
    }

    @Test
    fun `the same seed rolls the same sequence regardless of kind`() {
        val first = RandomChanceOracle(Random(42L))
        val second = RandomChanceOracle(Random(42L))
        val probability = 0.5f
        repeat(20) {
            val firstRoll = first.roll(ChanceKind.QA_DETECTION, probability)
            val secondRoll = second.roll(ChanceKind.QA_DETECTION, probability)
            assertEquals(firstRoll, secondRoll)
        }
    }
}
