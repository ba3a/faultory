package com.faultory.core.i18n

import java.util.Locale
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocaleManagerTest {

    @BeforeTest
    fun setUp() {
        LocaleManager.init(
            translations = CatalogTranslations(resourceReader = { null }),
            initialLocale = SupportedLocale.fallback,
        )
    }

    @Test
    fun `setLocale invokes persist callback`() {
        val persisted = mutableListOf<Locale>()
        LocaleManager.init(
            translations = CatalogTranslations(resourceReader = { null }),
            initialLocale = SupportedLocale.fallback,
            persist = { persisted += it },
        )

        LocaleManager.setLocale(Locale.forLanguageTag("ru"))

        assertEquals(listOf(Locale.forLanguageTag("ru")), persisted)
    }

    @Test
    fun `init does not persist initial locale`() {
        val persisted = mutableListOf<Locale>()
        LocaleManager.init(
            translations = CatalogTranslations(resourceReader = { null }),
            initialLocale = Locale.forLanguageTag("ru"),
            persist = { persisted += it },
        )

        assertTrue(persisted.isEmpty())
        assertEquals(Locale.forLanguageTag("ru"), LocaleManager.currentLocale)
    }

    @Test
    fun `cycleLocale walks through SupportedLocale all`() {
        assertEquals(SupportedLocale.fallback, LocaleManager.currentLocale)
        LocaleManager.cycleLocale()
        assertEquals(Locale.forLanguageTag("ru"), LocaleManager.currentLocale)
        LocaleManager.cycleLocale()
        assertEquals(SupportedLocale.fallback, LocaleManager.currentLocale)
    }

    @Test
    fun `init clears previously registered listeners`() {
        val fired = mutableListOf<Locale>()
        LocaleManager.addListener { fired += it }

        LocaleManager.init(
            translations = CatalogTranslations(resourceReader = { null }),
            initialLocale = SupportedLocale.fallback,
        )
        LocaleManager.setLocale(Locale.forLanguageTag("ru"))

        assertTrue(fired.isEmpty(), "listener registered before init should not fire after re-init")
    }

    @Test
    fun `setLocale clears catalog translation cache`() {
        var reads = 0
        val translations = CatalogTranslations(resourceReader = { _ ->
            reads += 1
            null
        })
        LocaleManager.init(translations = translations, initialLocale = SupportedLocale.fallback)

        Messages.catalog(CatalogMessageKey.WORKER_DISPLAYNAME, "x")
        Messages.catalog(CatalogMessageKey.WORKER_DISPLAYNAME, "x")
        val readsBefore = reads

        LocaleManager.setLocale(Locale.forLanguageTag("ru"))
        Messages.catalog(CatalogMessageKey.WORKER_DISPLAYNAME, "x")

        assertTrue(reads > readsBefore, "expected reader to be called again after locale switch")
    }
}
