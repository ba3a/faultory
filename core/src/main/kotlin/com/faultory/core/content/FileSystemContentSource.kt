package com.faultory.core.content

import com.faultory.core.assets.AssetPaths
import com.faultory.core.config.FaultoryJson
import com.faultory.core.encounters.ConditionLibrary
import com.faultory.core.encounters.EncounterCatalog
import com.faultory.core.graphics.InteractionCatalog
import com.faultory.core.shop.ShopBlueprint
import kotlinx.serialization.decodeFromString
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.text.Charsets

/**
 * Reads [GameContent] straight off disk with [FaultoryJson] — no `AssetManager`, no LibGDX
 * application required. Reads the same paths [AssetPaths] names, plus every level's own
 * [LevelDefinition.shopAssetPath]. Read-only, like every other consumer of [AssetPaths]; the
 * editor's [com.faultory.editor.repository.AssetRepository] is the one place that writes content,
 * and does its own thing entirely — atomic saves, backups, dirty tracking.
 */
class FileSystemContentSource(private val root: Path) {
    fun load(): GameContent {
        val levelCatalog = decode<LevelCatalog>(AssetPaths.levelCatalog)
        val shopCatalog = decode<ShopCatalog>(AssetPaths.shopCatalog)
        val conditionLibrary = decodeOrDefault(AssetPaths.conditionLibrary) { ConditionLibrary() }
        val encounterCatalog = decodeOrDefault(AssetPaths.encounterCatalog) { EncounterCatalog() }
        val interactionCatalog = decodeOrDefault(AssetPaths.interactionCatalog) { InteractionCatalog() }
        val blueprintsByPath = levelCatalog.levels.associate { level ->
            level.shopAssetPath to decode<ShopBlueprint>(level.shopAssetPath)
        }
        return GameContent(
            shopCatalog = shopCatalog,
            levelCatalog = levelCatalog,
            conditionLibrary = conditionLibrary,
            encounterCatalog = encounterCatalog,
            interactionCatalog = interactionCatalog,
            blueprintsByPath = blueprintsByPath
        )
    }

    private inline fun <reified T> decode(relativePath: String): T {
        val text = root.resolve(relativePath).readText(Charsets.UTF_8)
        return FaultoryJson.instance.decodeFromString(text)
    }

    private inline fun <reified T> decodeOrDefault(relativePath: String, default: () -> T): T {
        val path = root.resolve(relativePath)
        if (!path.exists()) return default()
        return FaultoryJson.instance.decodeFromString(path.readText(Charsets.UTF_8))
    }
}
