package com.faultory.core.i18n

import java.text.MessageFormat
import java.util.Locale
import java.util.MissingResourceException

object Messages {
    private val formatCache = HashMap<Pair<UiMessageKey, Locale>, MessageFormat>()
    private val loggedFormatErrors = HashSet<UiMessageKey>()

    fun text(key: UiMessageKey): String {
        return try {
            LocaleManager.currentBundle.getString(key.path)
        } catch (_: MissingResourceException) {
            if (LocaleManager.currentLocale != SupportedLocale.fallback) {
                try {
                    LocaleManager.fallbackBundle.getString(key.path)
                } catch (_: MissingResourceException) {
                    key.path
                }
            } else {
                key.path
            }
        }
    }

    fun format(key: UiMessageKey, vararg args: Any?): String {
        val locale = LocaleManager.currentLocale
        val pattern = text(key)
        val mf = try {
            formatCache.getOrPut(key to locale) { MessageFormat(pattern, locale) }
        } catch (e: IllegalArgumentException) {
            if (loggedFormatErrors.add(key)) {
                System.err.println("[Messages] Bad format pattern for '${key.path}': ${e.message}")
            }
            return pattern
        }
        return try {
            mf.format(args)
        } catch (e: IllegalArgumentException) {
            if (loggedFormatErrors.add(key)) {
                System.err.println("[Messages] Format error for '${key.path}': ${e.message}")
            }
            pattern
        }
    }

    fun catalog(key: CatalogMessageKey, id: String): String {
        return LocaleManager.catalogTranslations().resolve(key, id, LocaleManager.currentLocale)
    }
}
