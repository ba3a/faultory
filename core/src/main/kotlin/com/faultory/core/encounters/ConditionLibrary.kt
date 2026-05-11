package com.faultory.core.encounters

import kotlinx.serialization.Serializable

@Serializable
data class NamedCondition(val id: String, val body: Condition)

@Serializable
data class ConditionLibrary(val namedConditions: List<NamedCondition> = emptyList()) {
    private val byId by lazy { namedConditions.associateBy({ it.id }, { it.body }) }

    fun resolve(refId: String): Condition? = byId[refId]

    fun validate() {
        val visiting = mutableSetOf<String>()
        val visited = mutableSetOf<String>()
        for (nc in namedConditions) {
            dfs(nc.id, visiting, visited)
        }
    }

    private fun dfs(id: String, visiting: MutableSet<String>, visited: MutableSet<String>) {
        if (id in visited) return
        if (id in visiting) {
            val cycle = visiting.joinToString(" -> ") + " -> $id"
            throw IllegalStateException("Cycle detected in ConditionLibrary: $cycle")
        }
        val body = byId[id] ?: return
        visiting += id
        for (refId in collectRefs(body)) {
            dfs(refId, visiting, visited)
        }
        visiting -= id
        visited += id
    }

    private fun collectRefs(cond: Condition): List<String> = when (cond) {
        is Condition.Ref -> listOf(cond.refId)
        is Condition.And -> cond.operands.flatMap { collectRefs(it) }
        is Condition.Or -> cond.operands.flatMap { collectRefs(it) }
        is Condition.Xor -> cond.operands.flatMap { collectRefs(it) }
        is Condition.Not -> collectRefs(cond.operand)
        else -> emptyList()
    }
}
