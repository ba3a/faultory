package com.faultory.core.i18n

import java.util.Locale

object SupportedLocale {
    val fallback: Locale = Locale.forLanguageTag("en-US")
    val russian: Locale = Locale.forLanguageTag("ru")

    val all: List<Locale> = listOf(fallback, russian)

    fun resolve(tag: String?): Locale {
        if (tag.isNullOrBlank()) return fallback
        val parsed = Locale.forLanguageTag(tag)
        return all.firstOrNull { it.toLanguageTag().equals(parsed.toLanguageTag(), ignoreCase = true) }
            ?: fallback
    }

    fun next(current: Locale): Locale {
        val index = all.indexOfFirst { it.toLanguageTag() == current.toLanguageTag() }
        return all[((index.coerceAtLeast(0)) + 1) % all.size]
    }
}
