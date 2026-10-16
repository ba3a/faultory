package com.faultory.balance

import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.isDirectory

object TestAssets {
    val root: Path by lazy {
        var dir: Path? = Paths.get("").toAbsolutePath()
        while (dir != null) {
            val candidate = dir.resolve("assets")
            if (candidate.isDirectory()) return@lazy candidate
            dir = dir.parent
        }
        error("Could not find assets/ directory above ${Paths.get("").toAbsolutePath()}")
    }
}
