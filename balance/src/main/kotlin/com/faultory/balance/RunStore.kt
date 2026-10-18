package com.faultory.balance

/**
 * In-memory store for run results. Bounded to [maxRuns] most-recent results; evicts oldest on overflow.
 * Also accumulates counter keys seen across all stored runs, which [describe_metrics] exposes.
 */
class RunStore(private val maxRuns: Int = 20) {
    private val runs: LinkedHashMap<String, List<CellResult>> = LinkedHashMap()
    private val counterKeys: MutableSet<String> = mutableSetOf()
    private var nextId = 1

    @Synchronized
    fun store(results: List<CellResult>): String {
        val id = "run-$nextId"
        nextId++
        if (runs.size >= maxRuns) {
            runs.remove(runs.keys.first())
        }
        runs[id] = results
        results.forEach { cell ->
            cell.seedResults.forEach { sr -> counterKeys.addAll(sr.report.counters.keys) }
        }
        return id
    }

    @Synchronized
    fun get(runId: String): List<CellResult>? = runs[runId]

    @Synchronized
    fun seenCounterKeys(): Set<String> = counterKeys.toSet()
}
