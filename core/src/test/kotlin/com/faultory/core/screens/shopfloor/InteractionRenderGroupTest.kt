package com.faultory.core.screens.shopfloor

import com.faultory.core.graphics.InteractionDefinition
import com.faultory.core.shop.InteractionRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InteractionRenderGroupTest {
    @Test
    fun `an authored order interleaves the two participants and the payload`() {
        // The case per-entity depth cannot express: a product inside a machine with one of the
        // worker's arms in front of it and the other behind.
        val group = group(
            InteractionRenderGroup.RECIPIENT + ".chassis_back",
            InteractionRenderGroup.INITIATOR + ".far_arm",
            InteractionRenderGroup.RECIPIENT + ".interior",
            InteractionRenderGroup.PAYLOAD,
            InteractionRenderGroup.INITIATOR + ".near_arm",
            InteractionRenderGroup.RECIPIENT + ".chassis_front"
        )

        val depths = listOf(
            group.partKey(InteractionRenderGroup.RECIPIENT, "chassis_back"),
            group.partKey(InteractionRenderGroup.INITIATOR, "far_arm"),
            group.partKey(InteractionRenderGroup.RECIPIENT, "interior"),
            InteractionRenderGroup.PAYLOAD,
            group.partKey(InteractionRenderGroup.INITIATOR, "near_arm"),
            group.partKey(InteractionRenderGroup.RECIPIENT, "chassis_front")
        ).map { group.depthFor(it, fallbackDepth = 0f) }

        assertEquals(listOf(0f, 1f, 2f, 3f, 4f, 5f), depths)
    }

    @Test
    fun `unlisted fragments trail every listed one and keep their own order`() {
        val group = group(InteractionRenderGroup.PAYLOAD)

        val payload = group.depthFor(InteractionRenderGroup.PAYLOAD, fallbackDepth = 99f)
        val unlistedBehind = group.depthFor("initiator.cape", fallbackDepth = -5f)
        val unlistedFront = group.depthFor("initiator.hat", fallbackDepth = 3f)

        assertEquals(0f, payload)
        assertTrue(payload < unlistedBehind)
        assertTrue(unlistedBehind < unlistedFront)
    }

    @Test
    fun `a fragment falls back to its own depth when the group authored no order`() {
        val group = group()

        assertEquals(2.5f, group.depthFor("initiator.near_arm", fallbackDepth = 2.5f))
    }

    @Test
    fun `role keys distinguish two participants of the same kind`() {
        // A worker-to-worker exchange has two workers, so a kind-qualified key could not say which.
        val group = group()

        assertEquals(InteractionRenderGroup.INITIATOR, group.roleKeyFor(GIVER))
        assertEquals(InteractionRenderGroup.RECIPIENT, group.roleKeyFor(TAKER))
        assertNull(group.roleKeyFor("someone-else"))
    }

    @Test
    fun `role keys map from the interaction role`() {
        assertEquals(InteractionRenderGroup.INITIATOR, InteractionRenderGroup.roleKeyOf(InteractionRole.INITIATOR))
        assertEquals(InteractionRenderGroup.RECIPIENT, InteractionRenderGroup.roleKeyOf(InteractionRole.RECIPIENT))
    }

    private fun group(vararg layerOrder: String): InteractionRenderGroup = InteractionRenderGroup(
        definition = InteractionDefinition(
            id = "operate_machine",
            initiatorAction = "operate",
            recipientAction = "working",
            durationSeconds = 1f,
            layerOrder = layerOrder.toList()
        ),
        initiatorId = GIVER,
        recipientId = TAKER,
        payloadProductId = "product-1",
        anchor = RenderPosition(40f, 80f)
    )

    private companion object {
        const val GIVER = "worker-1"
        const val TAKER = "machine-1"
    }
}
