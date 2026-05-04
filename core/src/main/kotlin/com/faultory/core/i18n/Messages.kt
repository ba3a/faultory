package com.faultory.core.i18n

import java.text.MessageFormat
import java.util.Locale
import java.util.MissingResourceException

object Messages {
    private val formatCache = HashMap<Pair<UiMessageKey, Locale>, MessageFormat>()

    init {
        LocaleManager.addListener { formatCache.clear() }
    }

    fun text(key: UiMessageKey): String {
        return try {
            LocaleManager.currentBundle.getString(key.path)
        } catch (_: MissingResourceException) {
            key.path
        }
    }

    fun format(key: UiMessageKey, vararg args: Any?): String {
        val locale = LocaleManager.currentLocale
        val mf = formatCache.getOrPut(key to locale) { MessageFormat(text(key), locale) }
        return mf.format(args)
    }

    fun catalog(key: CatalogMessageKey, id: String): String {
        return LocaleManager.catalogTranslations().resolve(key, id, LocaleManager.currentLocale)
    }
}
