package com.faultory.core.i18n

import java.util.Locale
import java.util.ResourceBundle

object LocaleManager {
    private var bundleLoader: MessageBundleLoader = MessageBundleLoader()
    private var catalogTranslations: CatalogTranslations? = null
    private var persist: ((Locale) -> Unit)? = null
    private val listeners = mutableListOf<(Locale) -> Unit>()

    var currentLocale: Locale = SupportedLocale.fallback
        private set

    var currentBundle: ResourceBundle = bundleLoader.load(SupportedLocale.fallback)
        private set

    var fallbackBundle: ResourceBundle = bundleLoader.load(SupportedLocale.fallback)
        private set

    fun init(
        loader: MessageBundleLoader = MessageBundleLoader(),
        translations: CatalogTranslations,
        initialLocale: Locale = SupportedLocale.fallback,
        persist: ((Locale) -> Unit)? = null
    ) {
        this.bundleLoader = loader
        this.fallbackBundle = loader.load(SupportedLocale.fallback)
        this.catalogTranslations = translations
        this.persist = persist
        applyLocale(initialLocale, persistChange = false)
    }

    fun catalogTranslations(): CatalogTranslations {
        return catalogTranslations
            ?: error("LocaleManager.init must be called before catalog translations are used")
    }

    fun setLocale(locale: Locale) {
        applyLocale(locale, persistChange = true)
    }

    fun cycleLocale() {
        setLocale(SupportedLocale.next(currentLocale))
    }

    fun addListener(listener: (Locale) -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: (Locale) -> Unit) {
        listeners -= listener
    }

    private fun applyLocale(locale: Locale, persistChange: Boolean) {
        currentLocale = locale
        currentBundle = bundleLoader.load(locale)
        catalogTranslations?.invalidateCache()
        if (persistChange) {
            persist?.invoke(locale)
        }
        listeners.toList().forEach { it(locale) }
    }
}
