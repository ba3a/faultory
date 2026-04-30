package com.faultory.core.save

import com.faultory.core.i18n.SupportedLocale
import kotlinx.serialization.Serializable

@Serializable
data class PlayerPreferences(
    val localeTag: String = SupportedLocale.fallback.toLanguageTag()
)
