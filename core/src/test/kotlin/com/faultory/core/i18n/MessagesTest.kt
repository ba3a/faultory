package com.faultory.core.i18n

import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
        MessageKey.values().filterNot { it.isCatalog }.forEach { key ->
            val resolved = Messages.text(key)
            assertEquals(false, resolved.isBlank(), "Blank for ${key.name}")
            assertEquals(false, resolved == key.path, "Missing translation for ${key.name}")
        }
    }

    @Test
    fun `text on catalog key throws`() {
        assertFailsWith<IllegalArgumentException> { Messages.text(MessageKey.WORKER_DISPLAYNAME) }
    }

    @Test
    fun `format substitutes positional args`() {
        val text = Messages.format(MessageKey.UPGRADE_COST, 42)
        assertEquals("Cost 42", text)
    }

    @Test
    fun `text falls back to en-US when key missing in current locale`() {
        LocaleManager.setLocale(Locale.forLanguageTag("ru"))
        val title = Messages.text(MessageKey.GAME_TITLE)
        assertEquals("Faultory", title)
    }

    @Test
    fun `format returns correct result after locale switch`() {
        val before = Messages.format(MessageKey.UPGRADE_COST, 42)
        LocaleManager.setLocale(Locale.forLanguageTag("ru"))
        LocaleManager.setLocale(SupportedLocale.fallback)
        val after = Messages.format(MessageKey.UPGRADE_COST, 42)
        assertEquals(before, after)
    }

    @Test
    fun `setLocale switches active bundle`() {
        LocaleManager.setLocale(Locale.forLanguageTag("ru"))
        assertEquals("Открыть уровень", Messages.text(MessageKey.LEVEL_SELECT_OPEN))

        LocaleManager.setLocale(SupportedLocale.fallback)
        assertEquals("Open Level", Messages.text(MessageKey.LEVEL_SELECT_OPEN))
    }
}
