package com.faultory.core.i18n

import java.util.Locale
import java.util.ResourceBundle

class MessageBundleLoader(
    private val baseName: String = DEFAULT_BASE_NAME,
    private val classLoader: ClassLoader = MessageBundleLoader::class.java.classLoader
) {
    fun load(locale: Locale): ResourceBundle {
        val control = ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES)
        return ResourceBundle.getBundle(baseName, locale, classLoader, control)
    }

    companion object {
        const val DEFAULT_BASE_NAME = "i18n.messages"
    }
}
