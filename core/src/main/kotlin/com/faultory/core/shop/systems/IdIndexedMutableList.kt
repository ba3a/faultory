package com.faultory.core.shop.systems

internal class IdIndexedMutableList<T : Any>(
    initial: List<T>,
    private val idOf: (T) -> String
) : AbstractMutableList<T>() {

    private val backing: MutableList<T> = initial.toMutableList()
    private val byIdMap: HashMap<String, T> = HashMap(initial.size * 2)
    private val indexByIdMap: HashMap<String, Int> = HashMap(initial.size * 2)
    private val mutationListeners: MutableList<(old: T?, new: T?) -> Unit> = mutableListOf()

    init {
        backing.forEachIndexed { index, element ->
            val id = idOf(element)
            byIdMap[id] = element
            indexByIdMap[id] = index
        }
    }

    fun addMutationListener(listener: (old: T?, new: T?) -> Unit) {
        mutationListeners += listener
    }

    fun byId(id: String): T? = byIdMap[id]

    /** Index of the element whose id is [id], or -1. O(1) — no `indexOfFirst { }` scan. */
    fun indexOfId(id: String): Int = indexByIdMap[id] ?: -1

    /**
     * Replaces the element whose id is [id] with `transform(current)`, in place, and returns the
     * new element — or null when no element has that id. Saves the call site an
     * `indexOfFirst { it.id == id }` scan just to write a `.copy()` back.
     */
    fun replaceById(id: String, transform: (T) -> T): T? {
        val index = indexByIdMap[id] ?: return null
        val updated = transform(backing[index])
        set(index, updated)
        return updated
    }

    /** Removes the element whose id is [id], returning it, or null when absent. */
    fun removeById(id: String): T? {
        val index = indexByIdMap[id] ?: return null
        return removeAt(index)
    }

    override val size: Int
        get() = backing.size

    override fun get(index: Int): T = backing[index]

    override fun add(index: Int, element: T) {
        backing.add(index, element)
        byIdMap[idOf(element)] = element
        if (index == backing.size - 1) {
            indexByIdMap[idOf(element)] = index
        } else {
            reindexFrom(index)
        }
        notifyMutation(null, element)
    }

    override fun set(index: Int, element: T): T {
        val previous = backing.set(index, element)
        val previousId = idOf(previous)
        val newId = idOf(element)
        if (previousId != newId) {
            byIdMap.remove(previousId)
            indexByIdMap.remove(previousId)
        }
        byIdMap[newId] = element
        indexByIdMap[newId] = index
        notifyMutation(previous, element)
        return previous
    }

    override fun removeAt(index: Int): T {
        val removed = backing.removeAt(index)
        byIdMap.remove(idOf(removed))
        indexByIdMap.remove(idOf(removed))
        reindexFrom(index)
        notifyMutation(removed, null)
        return removed
    }

    /** Rewrites the id→index entries for everything from [startIndex] to the end after a shift. */
    private fun reindexFrom(startIndex: Int) {
        for (i in startIndex until backing.size) {
            indexByIdMap[idOf(backing[i])] = i
        }
    }

    private fun notifyMutation(old: T?, new: T?) {
        if (mutationListeners.isEmpty()) return
        for (listener in mutationListeners) listener(old, new)
    }
}
