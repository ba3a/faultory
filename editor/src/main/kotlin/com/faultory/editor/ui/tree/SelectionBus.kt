package com.faultory.editor.ui.tree

object SelectionBus {
    private val listeners = mutableListOf<(AssetSelection?) -> Unit>()

    var current: AssetSelection? = null
        private set

    fun addListener(listener: (AssetSelection?) -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: (AssetSelection?) -> Unit) {
        listeners -= listener
    }

    fun select(selection: AssetSelection?) {
        current = selection
        listeners.toList().forEach { it(selection) }
    }

    fun reset() {
        current = null
        listeners.clear()
    }
}
