package com.faultory.editor

import com.faultory.editor.graphics.SkinConvention
import com.faultory.editor.graphics.SkinSyncService
import java.io.PrintStream
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * Headless entry point behind `./gradlew :editor:syncSkin --args=<skinId>`, which roto's
 * `afterPublish` hook runs. Opens no window and needs no GL context.
 *
 * Exit codes: 0 synced, 1 failed or finished with validation errors, 2 wrong usage.
 */
object SyncSkinCli {
    const val EXIT_OK = 0
    const val EXIT_FAILED = 1
    const val EXIT_USAGE = 2

    /**
     * @param assetsRoot the `assets/` directory; the raw art lives in its sibling `raw-art/`.
     */
    fun run(args: Array<String>, assetsRoot: Path, out: PrintStream): Int {
        if (args.size != 1 || args[0].isBlank()) {
            out.println("Usage: syncSkin --args=<skinId>")
            return EXIT_USAGE
        }
        val skinId = args[0].trim()
        val rawDir = assetsRoot.resolve("..").resolve(SkinConvention.rawArtRoot).normalize()

        return try {
            val result = SkinSyncService(assetsRoot, rawDir).sync(skinId)
            out.println("Synced $skinId: ${result.clipCount} clip(s), ${result.bake.regionNames.size} region(s)")
            out.println("Atlas: ${result.bake.atlasPath}")
            result.issues.forEach { out.println("${it.severity}: ${it.message}") }
            if (result.hasErrors) EXIT_FAILED else EXIT_OK
        } catch (e: IllegalArgumentException) {
            out.println("syncSkin failed: ${e.message}")
            EXIT_FAILED
        }
    }
}

fun main(args: Array<String>) {
    exitProcess(SyncSkinCli.run(args, Path.of("").toAbsolutePath(), System.out))
}
