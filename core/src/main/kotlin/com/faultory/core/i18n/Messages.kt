package com.faultory.core.i18n

import java.text.MessageFormat
import java.util.MissingResourceException

object Messages {
    fun text(key: MessageKey): String {
        require(!key.isCatalog) { "Use Messages.catalog(...) for catalog keys: ${key.path}" }
        return try {
            LocaleManager.currentBundle.getString(key.path)
        } catch (_: MissingResourceException) {
            key.path
        }
    }

    fun format(key: MessageKey, vararg args: Any?): String {
        val pattern = text(key)
        return MessageFormat(pattern, LocaleManager.currentLocale).format(args)
    }

    fun catalog(key: MessageKey, id: String): String {
        require(key.isCatalog) { "Use Messages.text(...)/format(...) for non-catalog keys: ${key.path}" }
        return LocaleManager.catalogTranslations().resolve(key, id, LocaleManager.currentLocale)
    }
}
