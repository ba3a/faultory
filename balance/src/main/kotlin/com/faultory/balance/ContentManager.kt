package com.faultory.balance

import com.faultory.core.config.FaultoryJson
import com.faultory.core.content.FileSystemContentSource
import com.faultory.core.content.GameContent
import com.faultory.core.content.LevelCatalog
import com.faultory.core.content.ShopCatalog
import com.faultory.core.shop.ShopBlueprint
import java.nio.file.Path
import java.security.MessageDigest

class ContentManager(private val assetsRoot: Path) {
    private var base: GameContent = FileSystemContentSource(assetsRoot).load()

    val baseContent: GameContent get() = base

    val baseHash: String get() = computeHash(base)

    fun reload(): GameContent {
        base = FileSystemContentSource(assetsRoot).load()
        return base
    }

    fun tunedContent(scenario: Scenario): GameContent {
        if (scenario.patches.isEmpty()) return base
        return OverlayApplier.apply(base, scenario.patches).content
    }

    fun contentHash(content: GameContent): String = computeHash(content)

    private fun computeHash(content: GameContent): String {
        val json = FaultoryJson.instance
        val parts = buildString {
            append(json.encodeToString(ShopCatalog.serializer(), content.shopCatalog))
            append(json.encodeToString(LevelCatalog.serializer(), content.levelCatalog))
            content.blueprintsByPath.entries.sortedBy { it.key }.forEach { (_, bp) ->
                append(json.encodeToString(ShopBlueprint.serializer(), bp))
            }
        }
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(parts.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }.take(8)
    }
}
