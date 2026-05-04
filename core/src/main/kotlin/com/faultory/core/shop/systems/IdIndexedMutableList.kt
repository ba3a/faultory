package com.faultory.core.shop.systems

internal class IdIndexedMutableList<T : Any>(
    initial: List<T>,
    private val idOf: (T) -> String
) : AbstractMutableList<T>() {

    private val backing: MutableList<T> = initial.toMutableList()
    private val byIdMap: HashMap<String, T> = HashMap<String, T>(initial.size * 2).apply {
        backing.forEach { put(idOf(it), it) }
    }
    private val mutationListeners: MutableList<(old: T?, new: T?) -> Unit> = mutableListOf()

    fun addMutationListener(listener: (old: T?, new: T?) -> Unit) {
        mutationListeners += listener
    }

    fun byId(id: String): T? = byIdMap[id]

    override val size: Int
        get() = backing.size

    override fun get(index: Int): T = backing[index]

    override fun add(index: Int, element: T) {
        backing.add(index, element)
        byIdMap[idOf(element)] = element
        notifyMutation(null, element)
    }

    override fun set(index: Int, element: T): T {
        val previous = backing.set(index, element)
        val previousId = idOf(previous)
        val newId = idOf(element)
        if (previousId != newId) {
            byIdMap.remove(previousId)
        }
        byIdMap[newId] = element
        notifyMutation(previous, element)
        return previous
    }

    override fun removeAt(index: Int): T {
        val removed = backing.removeAt(index)
        byIdMap.remove(idOf(removed))
        notifyMutation(removed, null)
        return removed
    }

    private fun notifyMutation(old: T?, new: T?) {
        if (mutationListeners.isEmpty()) return
        for (listener in mutationListeners) listener(old, new)
    }
}
