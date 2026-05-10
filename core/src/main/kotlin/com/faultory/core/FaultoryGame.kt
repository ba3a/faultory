package com.faultory.core

import com.badlogic.gdx.Game
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.assets.AssetManager
import com.badlogic.gdx.assets.loaders.resolvers.InternalFileHandleResolver
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator.FreeTypeFontParameter
import com.faultory.core.assets.AssetPaths
import com.faultory.core.content.LevelCatalog
import com.faultory.core.content.LevelCatalogAssetLoader
import com.faultory.core.content.LevelDefinition
import com.faultory.core.content.ShopCatalog
import com.faultory.core.content.ShopCatalogAssetLoader
import com.faultory.core.graphics.SkinDefinition
import com.faultory.core.graphics.SkinDefinitionAssetLoader
import com.faultory.core.graphics.SkinReferences
import com.faultory.core.graphics.SkinRegistry
import com.faultory.core.i18n.CatalogTranslations
import com.faultory.core.i18n.LocaleManager
import com.faultory.core.i18n.SupportedLocale
import com.faultory.core.render.RenderContext
import com.faultory.core.save.GameSave
import com.faultory.core.save.LocalPlayerPreferencesRepository
import com.faultory.core.save.LocalSaveRepository
import com.faultory.core.save.PlayerPreferences
import com.faultory.core.save.PlayerPreferencesRepository
import com.faultory.core.save.SaveRepository
import com.faultory.core.screens.BootScreen
import com.faultory.core.screens.shopfloor.ShiftLifecycleHost
import com.faultory.core.screens.LevelSelectionScreen
import com.faultory.core.shop.ShopBlueprint
import com.faultory.core.shop.ShopBlueprintAssetLoader
import kotlin.text.Charsets

class FaultoryGame : Game(), ShiftLifecycleHost {
    lateinit var renderContext: RenderContext
        private set

    override lateinit var saveRepository: SaveRepository
        private set

    lateinit var assetManager: AssetManager
        private set

    lateinit var skinRegistry: SkinRegistry
        private set

    lateinit var preferencesRepository: PlayerPreferencesRepository
        private set

    override fun create() {
        renderContext = RenderContext(
            spriteBatch = SpriteBatch(),
            uiFont = createUiFont(),
            shapeRenderer = ShapeRenderer()
        )
        saveRepository = LocalSaveRepository()
        preferencesRepository = LocalPlayerPreferencesRepository()
        val preferences = preferencesRepository.load()
        val translations = CatalogTranslations(resourceReader = { path ->
            val handle = Gdx.files.internal(path)
            if (handle.exists()) handle.readString(Charsets.UTF_8.name()) else null
        })
        LocaleManager.init(
            translations = translations,
            initialLocale = SupportedLocale.resolve(preferences.localeTag),
            persist = { locale ->
                preferencesRepository.save(PlayerPreferences(locale.toLanguageTag()))
            }
        )

        val fileHandleResolver = InternalFileHandleResolver()
        assetManager = AssetManager(fileHandleResolver).apply {
            setLoader(ShopCatalog::class.java, ShopCatalogAssetLoader(fileHandleResolver))
            setLoader(LevelCatalog::class.java, LevelCatalogAssetLoader(fileHandleResolver))
            setLoader(ShopBlueprint::class.java, ShopBlueprintAssetLoader(fileHandleResolver))
            setLoader(SkinDefinition::class.java, SkinDefinitionAssetLoader(fileHandleResolver))
            load(AssetPaths.levelCatalog, LevelCatalog::class.java)
            load(AssetPaths.shopCatalog, ShopCatalog::class.java)
            finishLoadingAsset<ShopCatalog>(AssetPaths.shopCatalog)
            enqueueSkinDefinitions(fileHandleResolver)
        }
        skinRegistry = SkinRegistry(assetManager)

        setScreen(BootScreen(this))
    }

    override fun dispose() {
        super.dispose()
        renderContext.dispose()
        assetManager.dispose()
    }

    override fun openLevelSelection() {
        setScreen(LevelSelectionScreen(this))
    }

    override fun openLevel(level: LevelDefinition) {
        assetManager.load(level.shopAssetPath, ShopBlueprint::class.java)
        setScreen(BootScreen(this, level))
    }

    fun loadOrCreateLevelSave(
        slotId: String,
        shopId: String,
        unlockedWorkerIds: List<String>,
        unlockedMachineIds: List<String>,
        startingCash: Int = 0
    ): GameSave {
        return saveRepository.load(slotId)
            ?: GameSave.forLevel(
                slotId = slotId,
                shopId = shopId,
                unlockedWorkerIds = unlockedWorkerIds,
                unlockedMachineIds = unlockedMachineIds,
                startingCash = startingCash
            ).also(saveRepository::save)
    }

    private fun createUiFont(): BitmapFont {
        val handle = Gdx.files.internal(AssetPaths.uiFont)
        if (!handle.exists()) {
            return BitmapFont()
        }
        val generator = FreeTypeFontGenerator(handle)
        try {
            val parameter = FreeTypeFontParameter().apply {
                size = 16
                characters = FreeTypeFontGenerator.DEFAULT_CHARS +
                    "АБВГДЕЁЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯ" +
                    "абвгдеёжзийклмнопрстуфхцчшщъыьэюя" +
                    "«»№—–"
                minFilter = com.badlogic.gdx.graphics.Texture.TextureFilter.Nearest
                magFilter = com.badlogic.gdx.graphics.Texture.TextureFilter.Nearest
                mono = true
                hinting = FreeTypeFontGenerator.Hinting.None
            }
            return generator.generateFont(parameter)
        } finally {
            generator.dispose()
        }
    }

    private fun AssetManager.enqueueSkinDefinitions(fileHandleResolver: InternalFileHandleResolver) {
        val catalog = get(AssetPaths.shopCatalog, ShopCatalog::class.java)
        SkinReferences.referencedSkinIds(catalog).forEach { skinId ->
            val path = AssetPaths.skinPath(skinId)
            if (fileHandleResolver.resolve(path).exists()) {
                load(path, SkinDefinition::class.java)
            }
        }
    }
}
