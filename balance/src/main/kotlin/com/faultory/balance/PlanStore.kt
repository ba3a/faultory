package com.faultory.balance

import com.faultory.core.config.FaultoryJson
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** Persists [StoredPlan]s to `.balance/plans/<name>.json`. */
class PlanStore(private val scratchDir: Path) {
    private val plans = mutableMapOf<String, StoredPlan>()

    init { loadFromDisk() }

    fun put(name: String, plan: Plan): StoredPlan {
        val stored = StoredPlan(name = name, plan = plan)
        plans[name] = stored
        save(stored)
        return stored
    }

    fun get(name: String): StoredPlan = plans[name] ?: error("Plan '$name' not found")

    fun list(): List<StoredPlan> = plans.values.sortedBy { it.name }

    fun delete(name: String) {
        require(plans.containsKey(name)) { "Plan '$name' not found" }
        plans.remove(name)
        planFile(name).deleteIfExists()
    }

    fun contains(name: String): Boolean = plans.containsKey(name)

    private fun save(stored: StoredPlan) {
        val dir = scratchDir.resolve("plans")
        if (!dir.exists()) Files.createDirectories(dir)
        planFile(stored.name).writeText(FaultoryJson.instance.encodeToString(stored), Charsets.UTF_8)
    }

    private fun loadFromDisk() {
        val dir = scratchDir.resolve("plans")
        if (!dir.exists()) return
        dir.listDirectoryEntries("*.json").forEach { file ->
            try {
                val stored = FaultoryJson.instance.decodeFromString<StoredPlan>(file.readText(Charsets.UTF_8))
                plans[stored.name] = stored
            } catch (_: Exception) {
                System.err.println("balance: skipping unreadable plan file: $file")
            }
        }
    }

    private fun planFile(name: String): Path {
        val dir = scratchDir.resolve("plans").normalize()
        val resolved = dir.resolve("$name.json").normalize()
        require(resolved.startsWith(dir)) { "Plan name '$name' is invalid" }
        return resolved
    }
}
