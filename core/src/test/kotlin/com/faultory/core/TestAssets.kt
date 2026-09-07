package com.faultory.core

import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.isDirectory

/**
 * Locates this repo's real `assets/` directory by walking up from the test working directory.
 * Shared by tests that deliberately exercise the shipped content rather than a curated
 * `src/test/resources` fixture — see [com.faultory.core.graphics.InteractionCatalogAssetTest] for
 * why that distinction matters, and the `headless` package's tests for the other user of this.
 */
object TestAssets {
    val root: Path by lazy { findAssetsRoot() }

    private fun findAssetsRoot(): Path {
        var directory: Path? = Paths.get("").toAbsolutePath()
        while (directory != null) {
            val candidate = directory.resolve("assets")
            if (candidate.isDirectory()) {
                return candidate
            }
            directory = directory.parent
        }
        error("Could not find an assets/ directory above ${Paths.get("").toAbsolutePath()}")
    }
}
