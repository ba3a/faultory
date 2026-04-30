package com.faultory.core.i18n

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class CatalogTranslationsTest {

    private val resources = mapOf(
        "i18n/workers/line-inspector.en-US.json" to """{"displayName":"Line Inspector"}""",
        "i18n/workers/line-inspector.ru.json" to """{"displayName":"Контролёр линии"}""",
        "i18n/workers/no-russian.en-US.json" to """{"displayName":"English Only"}""",
    )

    private val translations = CatalogTranslations(
        resourceReader = { path -> resources[path] },
    )

    @Test
    fun `resolves localized displayName when available`() {
        val text = translations.resolve(
            MessageKey.WORKER_DISPLAYNAME,
            "line-inspector",
            Locale.forLanguageTag("ru"),
        )
        assertEquals("Контролёр линии", text)
    }

    @Test
    fun `falls back to en-US when locale file missing`() {
        val text = translations.resolve(
            MessageKey.WORKER_DISPLAYNAME,
            "no-russian",
            Locale.forLanguageTag("ru"),
        )
        assertEquals("English Only", text)
    }

    @Test
    fun `unknown id returns id verbatim`() {
        val text = translations.resolve(
            MessageKey.WORKER_DISPLAYNAME,
            "ghost",
            SupportedLocale.fallback,
        )
        assertEquals("ghost", text)
    }

    @Test
    fun `cache invalidation forces re-read`() {
        var reads = 0
        val tracking = CatalogTranslations(
            resourceReader = { path ->
                reads += 1
                resources[path]
            },
        )

        tracking.resolve(MessageKey.WORKER_DISPLAYNAME, "line-inspector", SupportedLocale.fallback)
        tracking.resolve(MessageKey.WORKER_DISPLAYNAME, "line-inspector", SupportedLocale.fallback)
        val readsBefore = reads
        tracking.invalidateCache()
        tracking.resolve(MessageKey.WORKER_DISPLAYNAME, "line-inspector", SupportedLocale.fallback)

        assertEquals(true, reads > readsBefore)
    }
}
