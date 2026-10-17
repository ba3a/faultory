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

class ScenarioStore(private val scratchDir: Path) {
    private val scenarios = mutableMapOf<String, Scenario>()

    val hardCap = 200

    init {
        scenarios[BASELINE_ID] = Scenario(id = BASELINE_ID, baseHash = "", patches = emptyList())
        loadFromDisk()
    }

    fun create(
        id: String,
        parent: String? = null,
        note: String? = null,
        patches: List<PatchOperation> = emptyList(),
        baseHash: String
    ): Scenario {
        require(id != BASELINE_ID) { "Cannot create a scenario with the reserved id '$BASELINE_ID'" }
        require(!scenarios.containsKey(id)) { "Scenario '$id' already exists" }
        require(scenarios.size < hardCap) {
            "Scenario hard cap ($hardCap) reached; discard some before creating new ones"
        }
        val parentId = parent ?: BASELINE_ID
        require(scenarios.containsKey(parentId)) { "Parent scenario '$parentId' does not exist" }
        val scenario = Scenario(id = id, parent = parentId, note = note, baseHash = baseHash, patches = patches)
        scenarios[id] = scenario
        autosave(scenario)
        return scenario
    }

    fun patch(id: String, patches: List<PatchOperation>, replace: Boolean = false): Scenario {
        require(id != BASELINE_ID) { "Cannot patch the baseline scenario" }
        val existing = scenarios[id] ?: error("Scenario '$id' not found")
        val newPatches = if (replace) patches else existing.patches + patches
        val updated = existing.copy(patches = newPatches)
        scenarios[id] = updated
        autosave(updated)
        return updated
    }

    fun list(): List<Scenario> = scenarios.values.toList().sortedBy { it.id }

    fun get(id: String): Scenario = scenarios[id] ?: error("Scenario '$id' not found")

    fun discard(id: String) {
        require(id != BASELINE_ID) { "Cannot discard the baseline scenario" }
        require(scenarios.containsKey(id)) { "Scenario '$id' not found" }
        scenarios.remove(id)
        scenarioFile(id).deleteIfExists()
    }

    fun staleIds(currentHash: String): List<String> =
        scenarios.values.filter { it.id != BASELINE_ID && it.baseHash.isNotEmpty() && it.baseHash != currentHash }
            .map { it.id }

    private fun autosave(scenario: Scenario) {
        val dir = scratchDir.resolve("scenarios")
        if (!dir.exists()) Files.createDirectories(dir)
        scenarioFile(scenario.id).writeText(FaultoryJson.instance.encodeToString(scenario), Charsets.UTF_8)
    }

    private fun loadFromDisk() {
        val dir = scratchDir.resolve("scenarios")
        if (!dir.exists()) return
        dir.listDirectoryEntries("*.json").forEach { file ->
            try {
                val scenario = FaultoryJson.instance.decodeFromString<Scenario>(file.readText(Charsets.UTF_8))
                if (scenario.id != BASELINE_ID) {
                    scenarios[scenario.id] = scenario
                }
            } catch (_: Exception) {
                System.err.println("balance: skipping unreadable scenario file: $file")
            }
        }
    }

    private fun scenarioFile(id: String): Path = scratchDir.resolve("scenarios/$id.json")

    companion object {
        const val BASELINE_ID = "baseline"
    }
}
