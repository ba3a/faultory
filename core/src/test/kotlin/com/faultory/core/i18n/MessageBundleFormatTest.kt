package com.faultory.core.i18n

import java.util.MissingResourceException
import kotlin.test.Test
import kotlin.test.assertEquals

class MessageBundleFormatTest {

    private val loader = MessageBundleLoader()
    private val fallback = loader.load(SupportedLocale.fallback)

    @Test
    fun `placeholder counts match fallback bundle across all locales`() {
        val formatKeys = UiMessageKey.entries.filter { key ->
            try {
                fallback.getString(key.path).contains(Regex("""\{\d+"""))
            } catch (_: MissingResourceException) {
                false
            }
        }

        SupportedLocale.all
            .filter { it.toLanguageTag() != SupportedLocale.fallback.toLanguageTag() }
            .forEach { locale ->
                val bundle = loader.load(locale)
                formatKeys.forEach { key ->
                    val baselinePattern = fallback.getString(key.path)
                    val baselineCount = distinctPlaceholders(baselinePattern)
                    try {
                        val localizedPattern = bundle.getString(key.path)
                        val localizedCount = distinctPlaceholders(localizedPattern)
                        assertEquals(
                            baselineCount, localizedCount,
                            "Placeholder count mismatch for ${key.name} in ${locale.toLanguageTag()}: " +
                                    "en-US='$baselinePattern', ${locale.toLanguageTag()}='$localizedPattern'"
                        )
                    } catch (_: MissingResourceException) {
                        // missing key is caught by MessagesTest; skip placeholder check
                    }
                }
            }
    }

    private fun distinctPlaceholders(pattern: String): Int =
        Regex("""\{(\d+)""").findAll(pattern).map { it.groupValues[1].toInt() }.toSet().size
}
