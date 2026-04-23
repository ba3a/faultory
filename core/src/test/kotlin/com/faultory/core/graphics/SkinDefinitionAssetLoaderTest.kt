package com.faultory.core.graphics

import com.badlogic.gdx.assets.AssetManager
import com.badlogic.gdx.assets.loaders.resolvers.AbsoluteFileHandleResolver
import com.badlogic.gdx.files.FileHandle
import com.faultory.core.shop.Orientation
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.text.Charsets

class SkinDefinitionAssetLoaderTest {
    @Test
    fun `load decodes a skin definition from json`() {
        val tempRoot = createTempDirectory("faultory-skin-loader")
        val assetManager = AssetManager()
        try {
            val skinFile = tempRoot.resolve("worker_line_inspector.json")
            skinFile.writeText(
                """
                {
                  "atlas": "textures/worker_line_inspector.atlas",
                  "actions": {
                    "idle": {
                      "frames": {
                        "NORTH": ["idle_north_000"],
                        "EAST": ["idle_east_000"],
                        "SOUTH": ["idle_south_000"],
                        "WEST": ["idle_west_000"]
                      }
                    },
                    "walk": {
                      "frames": {
                        "NORTH": ["walk_north_000", "walk_north_001"],
                        "EAST": ["walk_east_000", "walk_east_001"],
                        "SOUTH": ["walk_south_000", "walk_south_001"],
                        "WEST": ["walk_west_000", "walk_west_001"]
                      },
                      "fps": 12.0,
                      "loop": false
                    }
                  }
                }
                """.trimIndent(),
                Charsets.UTF_8
            )

            val loader = SkinDefinitionAssetLoader(AbsoluteFileHandleResolver())
            val fileHandle = FileHandle(skinFile.toFile())

            val definition = loader.load(
                assetManager,
                skinFile.toAbsolutePath().toString(),
                fileHandle,
                null
            )

            assertEquals("textures/worker_line_inspector.atlas", definition.atlas)
            assertEquals(listOf("idle_north_000"), definition.actions[SkinActions.IDLE]?.frames?.get(Orientation.NORTH))
            assertEquals(8f, definition.actions[SkinActions.IDLE]?.fps)
            assertEquals(true, definition.actions[SkinActions.IDLE]?.loop)
            assertEquals(12f, definition.actions[SkinActions.WALK]?.fps)
            assertEquals(false, definition.actions[SkinActions.WALK]?.loop)
            assertNull(loader.getDependencies(skinFile.toString(), fileHandle, null))
        } finally {
            assetManager.dispose()
            tempRoot.toFile().deleteRecursively()
        }
    }
}
