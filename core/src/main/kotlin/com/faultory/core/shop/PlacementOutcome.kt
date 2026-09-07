package com.faultory.core.shop

/**
 * Outcome of a purchase-and-place request handled by
 * [com.faultory.core.shop.systems.PlacementSystem.purchaseAndPlace]. A [Failure] carries the
 * reason so a UI adapter can blink the right thing and a headless driver can tell why a command
 * did nothing.
 */
sealed interface PurchaseResult {
    data class Success(
        val placedObject: PlacedShopObject
    ) : PurchaseResult

    data class Failure(
        val reason: PurchaseFailureReason
    ) : PurchaseResult
}

enum class PurchaseFailureReason {
    UNKNOWN_CATALOG_ID,
    INSUFFICIENT_CASH,
    NO_VALID_PLACEMENT
}

/**
 * One upgrade a placed object could become, enumerated by
 * [com.faultory.core.shop.systems.PlacementSystem.upgradeOptionsFor]. Carries no layout — a screen
 * adapter wraps it with its own laid-out bounds; a headless caller uses it as-is.
 */
data class UpgradeOption(
    val targetCatalogId: String,
    val kind: PlacedShopObjectKind,
    val cost: Int
)
