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
import com.faultory.core.encounters.ConditionLibrary
import com.faultory.core.encounters.ConditionLibraryAssetLoader
import com.faultory.core.encounters.EncounterCatalog
import com.faultory.core.encounters.EncounterCatalogAssetLoader
import com.faultory.core.encounters.EncounterEngine
import com.faultory.core.encounters.EvaluationContext
import com.faultory.core.encounters.EventBus
import com.faultory.core.graphics.SkinDefinition
import com.faultory.core.graphics.InteractionCatalog
import com.faultory.core.graphics.InteractionCatalogAssetLoader
import com.faultory.core.graphics.SkinDefinitionAssetLoader
import com.faultory.core.graphics.SkinReferences
import com.faultory.core.graphics.SkinRegistry
import com.faultory.core.i18n.CatalogTranslations
import com.faultory.core.i18n.LocaleManager
import com.faultory.core.i18n.SupportedLocale
import com.faultory.core.render.RenderContext
import com.faultory.core.save.EncounterProgressRepository
import com.faultory.core.save.GameSave
import com.faultory.core.save.LocalEncounterProgressRepository
import com.faultory.core.save.LocalPlayerPreferencesRepository
import com.faultory.core.save.LocalSaveRepository
import com.faultory.core.save.PlayerPreferences
import com.faultory.core.save.PlayerPreferencesRepository
import com.faultory.core.save.SaveRepository
import com.faultory.core.screens.BootScreen
import com.faultory.core.screens.shopfloor.ShiftLifecycleHost
import com.faultory.core.screens.LevelSelectionScreen
import com.faultory.core.shop.PlacedShopObject
import com.faultory.core.shop.ShopBlueprint
import com.faultory.core.shop.ShopBlueprintAssetLoader
import com.faultory.core.tutorial.TutorialCoordinator
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

    val eventBus: EventBus = EventBus()

    private lateinit var encounterProgressRepository: EncounterProgressRepository
    private var encounterEngine: EncounterEngine? = null
    private val tutorialCoordinator = TutorialCoordinator()

    override fun create() {
        renderContext = RenderContext(
            spriteBatch = SpriteBatch(),
            uiFont = createUiFont(),
            shapeRenderer = ShapeRenderer()
        )
        saveRepository = LocalSaveRepository()
        encounterProgressRepository = LocalEncounterProgressRepository()
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
            setLoader(ConditionLibrary::class.java, ConditionLibraryAssetLoader(fileHandleResolver))
            setLoader(EncounterCatalog::class.java, EncounterCatalogAssetLoader(fileHandleResolver))
            setLoader(InteractionCatalog::class.java, InteractionCatalogAssetLoader(fileHandleResolver))
            load(AssetPaths.levelCatalog, LevelCatalog::class.java)
            load(AssetPaths.shopCatalog, ShopCatalog::class.java)
            load(AssetPaths.conditionLibrary, ConditionLibrary::class.java)
            load(AssetPaths.encounterCatalog, EncounterCatalog::class.java)
            load(AssetPaths.interactionCatalog, InteractionCatalog::class.java)
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
        ensureEncounterEngineReady()
        encounterEngine?.currentLevelId = null
        encounterEngine?.currentPlacedObjects = null
        setScreen(LevelSelectionScreen(this))
    }

    override fun openLevel(level: LevelDefinition) {
        ensureEncounterEngineReady()
        encounterEngine?.currentLevelId = level.id
        encounterEngine?.currentPlacedObjects = null
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

    fun buildEvaluationContext(
        levelId: String? = null,
        placedObjects: List<PlacedShopObject>? = null
    ): EvaluationContext {
        val library = if (assetManager.isLoaded(AssetPaths.conditionLibrary))
            assetManager.get(AssetPaths.conditionLibrary, ConditionLibrary::class.java)
        else ConditionLibrary()
        return EvaluationContext(
            saveRepository = saveRepository,
            encounterProgress = encounterEngine?.progress ?: encounterProgressRepository.load(),
            conditionLibrary = library,
            currentLevelId = levelId,
            placedObjects = placedObjects
        )
    }

    fun updateEncounterPlacedObjects(placedObjects: List<PlacedShopObject>?) {
        encounterEngine?.currentPlacedObjects = placedObjects
    }

    /**
     * Read through a provider rather than captured once: the catalog is queued at boot and a shop
     * floor may be built before it lands, in which case interactions use their fallback duration.
     */
    fun interactionCatalog(): InteractionCatalog? =
        if (assetManager.isLoaded(AssetPaths.interactionCatalog)) {
            assetManager.get(AssetPaths.interactionCatalog, InteractionCatalog::class.java)
        } else {
            null
        }

    private fun ensureEncounterEngineReady() {
        if (encounterEngine != null) return
        if (!assetManager.isLoaded(AssetPaths.encounterCatalog)) return
        if (!assetManager.isLoaded(AssetPaths.conditionLibrary)) return
        encounterEngine = EncounterEngine(
            encounterCatalog = assetManager.get(AssetPaths.encounterCatalog, EncounterCatalog::class.java),
            conditionLibrary = assetManager.get(AssetPaths.conditionLibrary, ConditionLibrary::class.java),
            progressRepository = encounterProgressRepository,
            saveRepository = saveRepository,
            eventBus = eventBus,
            customHandlers = tutorialCoordinator.handlers
        )
    }

    private fun createUiFont(): BitmapFont {
        val handle = Gdx.files.internal(AssetPaths.uiFont)
        if (!handle.exists()) {
            Gdx.app.log("FaultoryGame", "UI font missing at '${AssetPaths.uiFont}', falling back to default ASCII font")
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
