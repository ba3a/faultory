package com.faultory.core.i18n

import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class MessagesTest {

    @BeforeTest
    fun setUp() {
        LocaleManager.init(
            translations = CatalogTranslations(resourceReader = { null }),
            initialLocale = SupportedLocale.fallback,
        )
    }

    @AfterTest
    fun tearDown() {
        LocaleManager.init(
            translations = CatalogTranslations(resourceReader = { null }),
            initialLocale = SupportedLocale.fallback,
        )
    }

    @Test
    fun `text resolves all UI MessageKeys in en-US`() {
        UiMessageKey.entries.forEach { key ->
            val resolved = Messages.text(key)
            assertEquals(false, resolved.isBlank(), "Blank for ${key.name}")
            assertEquals(false, resolved == key.path, "Missing translation for ${key.name}")
        }
    }

    @Test
    fun `catalog falls back to id when translations are missing`() {
        assertEquals("worker-1", Messages.catalog(CatalogMessageKey.WORKER_DISPLAYNAME, "worker-1"))
    }

    @Test
    fun `format substitutes positional args`() {
        val text = Messages.format(UiMessageKey.UPGRADE_COST, 42)
        assertEquals("Cost 42", text)
    }

    @Test
    fun `text falls back to en-US when key missing in current locale`() {
        LocaleManager.setLocale(Locale.forLanguageTag("ru"))
        val title = Messages.text(UiMessageKey.GAME_TITLE)
        assertEquals("Faultory", title)
    }

    @Test
    fun `format returns correct result after locale switch`() {
        val before = Messages.format(UiMessageKey.UPGRADE_COST, 42)
        LocaleManager.setLocale(Locale.forLanguageTag("ru"))
        LocaleManager.setLocale(SupportedLocale.fallback)
        val after = Messages.format(UiMessageKey.UPGRADE_COST, 42)
        assertEquals(before, after)
    }

    @Test
    fun `setLocale switches active bundle`() {
        LocaleManager.setLocale(Locale.forLanguageTag("ru"))
        assertEquals("\u041E\u0442\u043A\u0440\u044B\u0442\u044C \u0443\u0440\u043E\u0432\u0435\u043D\u044C", Messages.text(UiMessageKey.LEVEL_SELECT_OPEN))

        LocaleManager.setLocale(SupportedLocale.fallback)
        assertEquals("Open Level", Messages.text(UiMessageKey.LEVEL_SELECT_OPEN))
    }
}
