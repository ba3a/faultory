package com.faultory.core.i18n

enum class CatalogMessageKey(
    val category: String,
    val field: String
) : MessageKey {
    WORKER_DISPLAYNAME("workers", "displayName"),
    MACHINE_DISPLAYNAME("machines", "displayName"),
    PRODUCT_DISPLAYNAME("products", "displayName"),
    LEVEL_DISPLAYNAME("levels", "displayName"),
    LEVEL_SUBTITLE("levels", "subtitle"),
    LEVEL_LOCKED_HINT("levels", "lockedHint"),
    WORKER_LOCKED_HINT("workers", "lockedHint"),
    MACHINE_LOCKED_HINT("machines", "lockedHint");

    override val path: String = "catalog.$category.$field"
}
