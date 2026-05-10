package com.faultory.core.screens.shopfloor

import com.faultory.core.config.GameConfig
import com.faultory.core.shop.PlacedShopObjectKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UpgradeModalLayoutTest {
    @Test
    fun `build with single option centers modal horizontally`() {
        val options = listOf(upgradeOption("worker-b"))

        val modal = UpgradeModalLayout.build("worker-1", PlacedShopObjectKind.WORKER, options)

        val expectedWidth = GameConfig.upgradeModalCardWidth + GameConfig.upgradeModalHorizontalPadding * 2f
        val expectedX = (GameConfig.virtualWidth - expectedWidth) / 2f
        assertEquals(expectedX, modal.bounds.x, absoluteTolerance = 0.01f)
    }

    @Test
    fun `build with two options produces modal wide enough to hold both cards`() {
        val options = listOf(upgradeOption("worker-b"), upgradeOption("worker-c"))

        val modal = UpgradeModalLayout.build("worker-1", PlacedShopObjectKind.WORKER, options)

        val expectedWidth = 2 * GameConfig.upgradeModalCardWidth + GameConfig.upgradeModalCardGap + GameConfig.upgradeModalHorizontalPadding * 2f
        assertEquals(expectedWidth, modal.bounds.width, absoluteTolerance = 0.01f)
        assertEquals(2, modal.options.size)
    }

    @Test
    fun `option bounds are inside modal bounds`() {
        val options = listOf(upgradeOption("m-b"), upgradeOption("m-c"))

        val modal = UpgradeModalLayout.build("m-1", PlacedShopObjectKind.MACHINE, options)

        for (opt in modal.options) {
            assertTrue(opt.bounds.x >= modal.bounds.x, "option left must be inside modal")
            assertTrue(opt.bounds.y >= modal.bounds.y, "option bottom must be inside modal")
            assertTrue(opt.bounds.x + opt.bounds.width <= modal.bounds.x + modal.bounds.width, "option right must be inside modal")
            assertTrue(opt.bounds.y + opt.bounds.height <= modal.bounds.y + modal.bounds.height, "option top must be inside modal")
        }
    }

    @Test
    fun `option bounds have the configured card dimensions`() {
        val options = listOf(upgradeOption("worker-b"))

        val modal = UpgradeModalLayout.build("worker-1", PlacedShopObjectKind.WORKER, options)

        val opt = modal.options.first()
        assertEquals(GameConfig.upgradeModalCardWidth, opt.bounds.width, absoluteTolerance = 0.01f)
        assertEquals(GameConfig.upgradeModalCardHeight, opt.bounds.height, absoluteTolerance = 0.01f)
    }

    @Test
    fun `two options are placed side by side with the configured gap`() {
        val options = listOf(upgradeOption("worker-b"), upgradeOption("worker-c"))

        val modal = UpgradeModalLayout.build("worker-1", PlacedShopObjectKind.WORKER, options)

        val gap = modal.options[1].bounds.x - (modal.options[0].bounds.x + modal.options[0].bounds.width)
        assertEquals(GameConfig.upgradeModalCardGap, gap, absoluteTolerance = 0.01f)
    }

    private fun upgradeOption(targetId: String): UpgradeOption {
        return UpgradeOption(targetCatalogId = targetId, kind = PlacedShopObjectKind.WORKER, cost = 100)
    }
}

private fun assertEquals(expected: Float, actual: Float, absoluteTolerance: Float, message: String? = null) {
    val diff = kotlin.math.abs(expected - actual)
    if (diff > absoluteTolerance) {
        val msg = if (message != null) "$message: " else ""
        throw AssertionError("${msg}Expected $expected but was $actual (tolerance $absoluteTolerance)")
    }
}
