package com.faultory.core.screens.shopfloor

import com.badlogic.gdx.graphics.Color
import com.faultory.core.config.DebugFlags
import com.faultory.core.config.GameConfig
import com.faultory.core.graphics.MachineActionResolver
import com.faultory.core.graphics.ProductActionResolver
import com.faultory.core.graphics.ProductActions
import com.faultory.core.graphics.SkinDefinition
import com.faultory.core.graphics.SkinFrameLookup
import com.faultory.core.graphics.SkinRegistry
import com.faultory.core.graphics.SocketNames
import com.faultory.core.graphics.SocketPoint
import com.faultory.core.graphics.WorkerActionResolver
import com.faultory.core.shop.InteractionRole
import com.faultory.core.shop.Orientation
import com.faultory.core.shop.PlacedShopObject
import com.faultory.core.shop.PlacedShopObjectKind
import com.faultory.core.shop.ShopFloor
import com.faultory.core.shop.ShopProduct
import com.faultory.core.shop.ShopProductState
import kotlin.math.abs

/**
 * Draws every sprite-backed entity — workers, machines and products alike — in one globally sorted
 * pass.
 *
 * Sorting them together is what lets a held product sit *inside* its holder. While workers and
 * products were separate layers every product necessarily drew above every worker, so a crate could
 * only ever be pasted on top of a body, and a product lying south of a worker drew over their head.
 *
 * Each entity contributes a small group of fragments sharing one sort position: cutout parts below
 * and above the base sprite, and any attachment sitting at its socket's depth between them.
 */
class EntitySpriteLayer(
    private val shopFloor: ShopFloor,
    private val catalogLookup: CatalogLookup,
    private val geometry: ShopFloorGeometry,
    private val drawnIds: MutableSet<String> = mutableSetOf(),
    private val drawnProductIds: MutableSet<String> = mutableSetOf()
) : ShopFloorLayer {
    private val planned = mutableListOf<PlannedSprite>()
    private val framesByObjectId = mutableMapOf<String, SkinFrameLookup.ResolvedFrame>()
    private val groupsByObjectId = mutableMapOf<String, InteractionRenderGroup>()

    fun drawnIdsView(): Set<String> = drawnIds

    fun drawnProductIdsView(): Set<String> = drawnProductIds

    override fun prepare(ctx: ShopFloorRenderContext) {
        planned.clear()
        drawnIds.clear()
        drawnProductIds.clear()
        framesByObjectId.clear()
        groupsByObjectId.clear()
        if (DebugFlags.forceShapeRendering) {
            return
        }

        val skinRegistry = ctx.skinRegistry ?: return
        val delta = ctx.delta.coerceAtLeast(0f)

        // Groups first: they decide what every participant's fragments sort by, and reading
        // positions is cheap and advances no animation clock.
        buildRenderGroups()
        // Placed objects next: a carried product needs its holder's resolved frame to find the
        // hands socket, so the holder has to be resolved before anything can hang off it.
        planPlacedObjects(ctx, skinRegistry, delta)
        planProducts(ctx, skinRegistry, delta)
        planInMachineProduction(ctx, skinRegistry, delta)
        planned.sortWith(PlannedSprite.backToFront)
    }

    override fun drawSprite(ctx: ShopFloorRenderContext) {
        planned.forEach { it.draw(ctx.spriteBatch) }
    }

    /**
     * Pools the participants of any interaction that authored a layer order into one sort unit.
     * Interactions without one are left alone and sort by the ordinary per-entity rules.
     */
    private fun buildRenderGroups() {
        for (placedObject in shopFloor.placedObjects) {
            if (placedObject.id in groupsByObjectId) {
                continue
            }
            val interaction = placedObject.interaction ?: continue
            val definition = shopFloor.interactionDefinitionFor(interaction.definitionId) ?: continue
            if (definition.layerOrder.isEmpty()) {
                continue
            }
            val partner = shopFloor.findObjectById(interaction.partnerObjectId) ?: continue

            val isInitiator = interaction.role == InteractionRole.INITIATOR
            val group = InteractionRenderGroup(
                definition = definition,
                initiatorId = if (isInitiator) placedObject.id else partner.id,
                recipientId = if (isInitiator) partner.id else placedObject.id,
                payloadProductId = interaction.payloadProductId,
                anchor = listOf(placedObject, partner)
                    .map(geometry::renderPositionFor)
                    .minWith(compareBy({ it.worldY }, { it.worldX }))
            )
            groupsByObjectId[placedObject.id] = group
            groupsByObjectId[partner.id] = group
        }
    }

    private fun planPlacedObjects(ctx: ShopFloorRenderContext, skinRegistry: SkinRegistry, delta: Float) {
        for (placedObject in shopFloor.placedObjects) {
            val definition = skinDefinitionFor(placedObject, skinRegistry) ?: continue
            val frame = ctx.frameLookup.resolveFrame(
                definition = definition,
                animationId = placedObject.id,
                action = actionFor(placedObject),
                orientation = orientationFor(placedObject),
                delta = delta
            ) ?: continue

            // Cached because resolving twice in one frame would advance this entity's clock twice.
            framesByObjectId[placedObject.id] = frame

            val anchor = geometry.renderPositionFor(placedObject)
            val group = groupsByObjectId[placedObject.id]
            val roleKey = group?.roleKeyFor(placedObject.id)
            val sort = group?.anchor ?: anchor

            val base = PlannedSprite.standingOnTile(
                region = frame.region,
                tileWorldX = anchor.worldX,
                tileWorldY = anchor.worldY,
                tileSize = GameConfig.tileSize,
                depth = depthFor(group, roleKey, SocketPoint.BASE_DEPTH),
                sortAnchorX = sort.worldX,
                sortAnchorY = sort.worldY
            )
            planned += base
            planParts(frame, base.placement, group, roleKey)
            drawnIds += placedObject.id
        }
    }

    /**
     * Cutout layers composited around a base sprite, each at its own depth.
     *
     * Parts inherit the base fragment's exact position rather than being placed independently: they
     * are cutouts of the same pose registered on the same canvas, so re-centring each one would
     * break any part cropped tighter than the body — and would strand the parts of a carried
     * product back on its holder's tile instead of following it to the socket.
     */
    private fun planParts(
        frame: SkinFrameLookup.ResolvedFrame,
        base: SpritePlacement,
        group: InteractionRenderGroup? = null,
        roleKey: String? = null
    ) {
        for (part in frame.parts()) {
            val region = frame.partRegion(part) ?: continue
            val key = if (group != null && roleKey != null) group.partKey(roleKey, part.name) else null
            planned += PlannedSprite(
                region = region,
                placement = base.copy(
                    width = region.regionWidth.toFloat(),
                    height = region.regionHeight.toFloat(),
                    depth = depthFor(group, key, part.depth)
                )
            )
        }
    }

    /** A group's authored slot for [key], or the fragment's own depth when it is not grouped. */
    private fun depthFor(group: InteractionRenderGroup?, key: String?, fallbackDepth: Float): Float =
        if (group != null && key != null) group.depthFor(key, fallbackDepth) else fallbackDepth

    private fun planProducts(ctx: ShopFloorRenderContext, skinRegistry: SkinRegistry, delta: Float) {
        for (product in shopFloor.activeProducts) {
            val definition = skinDefinitionFor(product.productId, skinRegistry) ?: continue
            val action = ProductActionResolver.actionFor(shopFloor, product)
            val orientation = ProductActionResolver.orientationFor(shopFloor, product, ctx.productOrientations)
            val frame = ctx.frameLookup.resolveFrame(
                definition = definition,
                animationId = product.id,
                action = action,
                orientation = orientation,
                delta = delta
            ) ?: continue

            // With no mask art authored the base sprite is tinted instead, so a faulty product still
            // reads as faulty - the same information the shape renderer conveys through fill colour.
            val overlayRegion = overlayRegionFor(ctx, definition, product, orientation, delta)
            val tint = if (overlayRegion == null) ShopFloorPalette.productFaultTint(product.faultReason) else null

            val base = attachedSpriteFor(product, frame, tint)
                ?: tileSpriteFor(product, frame, tint)
                ?: continue

            planned += base
            planParts(frame, base.placement)
            if (overlayRegion != null) {
                planned += base.overlaidWith(overlayRegion, OVERLAY_DEPTH_STEP)
            }
            drawnProductIds += product.id
        }
    }

    /**
     * A carried product hangs off its holder's hands socket, offset by its own grip point so it
     * lines up by the spot being held rather than by its corner. Returns null when the holder or the
     * socket is unauthored, and the caller places the product on a tile instead.
     */
    private fun attachedSpriteFor(
        product: ShopProduct,
        frame: SkinFrameLookup.ResolvedFrame,
        tint: Color?
    ): PlannedSprite? {
        if (product.state != ShopProductState.CARRIED) {
            return null
        }
        val holder = geometry.holderFor(product) ?: return null
        val held = socketPlacementFor(holder, frame) ?: return null
        val placement = handoverPlacement(holder, product, frame, held)

        // Inside a layered interaction the payload takes its authored slot, which is what puts a
        // product inside a machine with one of the worker's arms in front of it and one behind.
        val group = groupsByObjectId[holder.id]?.takeIf { it.payloadProductId == product.id }
            ?: return PlannedSprite(frame.region, placement, tint)

        return PlannedSprite(
            region = frame.region,
            placement = placement.copy(
                groupX = group.anchor.worldX,
                groupY = group.anchor.worldY,
                depth = group.depthFor(InteractionRenderGroup.PAYLOAD, placement.depth)
            ),
            tint = tint
        )
    }

    /**
     * Slides a payload between the two participants' sockets while an exchange is in flight.
     *
     * The fraction peaks at the midpoint and falls away symmetrically, which is what makes the
     * motion continuous across the instant the product changes hands: before the transfer the
     * holder is the giver and the partner the taker, afterwards they swap, and the same curve read
     * from either side describes one unbroken path.
     */
    private fun handoverPlacement(
        holder: PlacedShopObject,
        product: ShopProduct,
        frame: SkinFrameLookup.ResolvedFrame,
        held: SpritePlacement
    ): SpritePlacement {
        val interaction = holder.interaction?.takeIf { it.payloadProductId == product.id } ?: return held
        val fraction = handoverFraction(
            elapsedSeconds = interaction.elapsedSeconds,
            transferSeconds = interaction.transferSeconds,
            durationSeconds = interaction.durationSeconds
        )
        if (fraction <= 0f) {
            return held
        }
        val partner = shopFloor.findObjectById(interaction.partnerObjectId) ?: return held
        val toward = socketPlacementFor(partner, frame) ?: return held

        return held.lerpTo(toward, fraction)
    }

    /** Where [holder] would hold [frame], or null when the holder authored no hands socket. */
    private fun socketPlacementFor(
        holder: PlacedShopObject,
        frame: SkinFrameLookup.ResolvedFrame
    ): SpritePlacement? {
        val socket = framesByObjectId[holder.id]?.socket(SocketNames.HANDS) ?: return null
        val anchor = geometry.holderAnchorFor(holder)
        return SpritePlacement.atSocket(
            regionWidth = frame.region.regionWidth.toFloat(),
            regionHeight = frame.region.regionHeight.toFloat(),
            holderTileWorldX = anchor.worldX,
            holderTileWorldY = anchor.worldY,
            tileSize = GameConfig.tileSize,
            socket = socket,
            grip = frame.socket(SocketNames.GRIP)
        )
    }

    private fun tileSpriteFor(
        product: ShopProduct,
        frame: SkinFrameLookup.ResolvedFrame,
        tint: Color?
    ): PlannedSprite? {
        val anchor = geometry.renderPositionFor(product) ?: return null
        return PlannedSprite.standingOnTile(
            region = frame.region,
            tileWorldX = anchor.worldX,
            tileWorldY = anchor.worldY,
            tileSize = GameConfig.tileSize,
            tint = tint
        )
    }

    /**
     * A product still inside a machine has no [ShopProduct] row yet, so the producing state is
     * driven off the production state of the machine instead.
     *
     * No fault overlay here on purpose: marking it would reveal defects before QA ever inspects them.
     */
    private fun planInMachineProduction(ctx: ShopFloorRenderContext, skinRegistry: SkinRegistry, delta: Float) {
        for (productionState in shopFloor.machineProductionStates) {
            val machine = shopFloor.findObjectById(productionState.machineId) ?: continue
            val definition = skinDefinitionFor(productionState.productId, skinRegistry) ?: continue
            val frame = ctx.frameLookup.resolveFrame(
                definition = definition,
                animationId = "$PRODUCING_ANIMATION_PREFIX${productionState.machineId}",
                action = ProductActions.PRODUCING,
                orientation = machine.orientation,
                delta = delta
            ) ?: continue

            val anchor = geometry.machineCenterFor(machine)
            val socket = framesByObjectId[machine.id]?.socket(SocketNames.HANDS)
            planned += if (socket != null) {
                PlannedSprite.atSocket(
                    region = frame.region,
                    holderTileWorldX = anchor.worldX,
                    holderTileWorldY = anchor.worldY,
                    tileSize = GameConfig.tileSize,
                    socket = socket,
                    grip = frame.socket(SocketNames.GRIP)
                )
            } else {
                PlannedSprite.standingOnTile(
                    region = frame.region,
                    tileWorldX = anchor.worldX,
                    tileWorldY = anchor.worldY,
                    tileSize = GameConfig.tileSize
                )
            }
        }
    }

    private fun overlayRegionFor(
        ctx: ShopFloorRenderContext,
        definition: SkinDefinition,
        product: ShopProduct,
        orientation: Orientation,
        delta: Float
    ) = ProductActions.faultOverlayActionFor(product.faultReason)?.let { overlayAction ->
        ctx.frameLookup.overlayRegion(
            definition = definition,
            animationId = "${product.id}$FAULT_ANIMATION_SUFFIX",
            action = overlayAction,
            orientation = orientation,
            delta = delta
        )
    }

    private fun actionFor(placedObject: PlacedShopObject): String = when (placedObject.kind) {
        PlacedShopObjectKind.MACHINE -> MachineActionResolver.actionFor(shopFloor, placedObject)
        PlacedShopObjectKind.WORKER -> WorkerActionResolver.actionFor(placedObject, shopFloor::interactionDefinitionFor)
    }

    private fun orientationFor(placedObject: PlacedShopObject): Orientation = when (placedObject.kind) {
        PlacedShopObjectKind.MACHINE -> placedObject.orientation
        PlacedShopObjectKind.WORKER -> WorkerActionResolver.orientationFor(placedObject)
    }

    private fun skinDefinitionFor(placedObject: PlacedShopObject, skinRegistry: SkinRegistry): SkinDefinition? {
        val skinId = when (placedObject.kind) {
            PlacedShopObjectKind.WORKER -> catalogLookup.workerProfilesById[placedObject.catalogId]?.skin
            PlacedShopObjectKind.MACHINE -> catalogLookup.machineSpecsById[placedObject.catalogId]?.skin
        } ?: return null

        return skinRegistry.get(skinId)
    }

    private fun skinDefinitionFor(productId: String, skinRegistry: SkinRegistry): SkinDefinition? {
        val skinId = catalogLookup.productDefinitionsById[productId]
            ?.skin
            ?.takeIf(String::isNotBlank)
            ?: return null
        return skinRegistry.get(skinId)
    }

    internal companion object {
        const val PRODUCING_ANIMATION_PREFIX = "producing:"
        const val FAULT_ANIMATION_SUFFIX = "#fault"

        /** Enough to hold a fault mask above the frame it marks, not enough to clear a near part. */
        const val OVERLAY_DEPTH_STEP = 0.001f

        /** Half way between the two participants, reached exactly as the payload changes hands. */
        const val MIDPOINT = 0.5f

        /**
         * How far from the current holder toward its partner the payload sits, peaking at
         * [MIDPOINT] exactly at the transfer.
         *
         * Read from the giver before the transfer and from the taker after it, this one symmetric
         * curve describes a single unbroken path: at the transfer instant both sides agree on the
         * midpoint, so the product does not jump when [ShopProduct.holderObjectId] flips.
         *
         * The window is the shorter half of the clip, so the slide never runs past either end.
         */
        fun handoverFraction(
            elapsedSeconds: Float,
            transferSeconds: Float,
            durationSeconds: Float
        ): Float {
            val window = minOf(transferSeconds, durationSeconds - transferSeconds)
            if (window <= 0f) {
                return 0f
            }
            val distanceFromTransfer = abs(elapsedSeconds - transferSeconds)
            return MIDPOINT * (1f - distanceFromTransfer / window).coerceIn(0f, 1f)
        }
    }
}
