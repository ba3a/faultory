package com.faultory.balance

import com.faultory.balance.tool.handleCompare
import com.faultory.balance.tool.handleDescribeContent
import com.faultory.balance.tool.handleDescribeMetrics
import com.faultory.balance.tool.handleGetValue
import com.faultory.balance.tool.handlePlanCheck
import com.faultory.balance.tool.handlePlanDelete
import com.faultory.balance.tool.handlePlanGet
import com.faultory.balance.tool.handlePlanList
import com.faultory.balance.tool.handlePlanPut
import com.faultory.balance.tool.handleReloadBase
import com.faultory.balance.tool.handleRun
import com.faultory.balance.tool.handleRunDetails
import com.faultory.balance.tool.handleScenarioCreate
import com.faultory.balance.tool.handleScenarioDiff
import com.faultory.balance.tool.handleScenarioDiscard
import com.faultory.balance.tool.handleScenarioExport
import com.faultory.balance.tool.handleScenarioGet
import com.faultory.balance.tool.handleScenarioList
import com.faultory.balance.tool.handleScenarioPatch
import com.faultory.balance.tool.handleSweep
import com.faultory.core.config.FaultoryJson
import io.modelcontextprotocol.kotlin.sdk.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.Implementation
import io.modelcontextprotocol.kotlin.sdk.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.TextContent
import io.modelcontextprotocol.kotlin.sdk.Tool
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class BalanceServer(
    private val cm: ContentManager,
    private val store: ScenarioStore,
    private val planStore: PlanStore,
    private val engine: RunEngine,
    private val runStore: RunStore
) {

    fun buildServer(): Server {
        val server = Server(
            serverInfo = Implementation(name = "balance", version = "0.2.0"),
            options = ServerOptions(
                capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))
            )
        )
        registerAll(server)
        return server
    }

    private fun registerAll(server: Server) {
        // --- Discovery ---
        tool(server, "describe_content", "What exists in the game content and what can be tuned.",
            Tool.Input(
                properties = buildJsonObject {
                    putJsonObject("scenario") { put("type", "string"); put("description", "Scenario id to read (default: baseline)") }
                    putJsonObject("document") { put("type", "string"); put("description", "Filter to 'catalog', 'levels', or 'blueprint'") }
                    putJsonObject("prefix") { put("type", "string"); put("description", "Path prefix to drill into, e.g. 'catalog/workers/anywork-1'") }
                    putJsonObject("depth") { put("type", "integer"); put("description", "Walk depth from the prefix (default: 2)") }
                }
            )
        ) { args -> handleDescribeContent(cm, store, args) }

        tool(server, "get_value", "Current base and tuned value for one or more paths.",
            Tool.Input(
                properties = buildJsonObject {
                    putJsonObject("scenario") { put("type", "string"); put("description", "Scenario id (default: baseline)") }
                    putJsonObject("paths") { put("type", "array"); put("description", "Paths to read, e.g. ['catalog/workers/anywork-1/hireCost']") }
                },
                required = listOf("paths")
            )
        ) { args -> handleGetValue(cm, store, args) }

        // --- Scenarios ---
        tool(server, "scenario_create", "Create a new named scenario forked from a parent.",
            Tool.Input(
                properties = buildJsonObject {
                    putJsonObject("id") { put("type", "string"); put("description", "Unique scenario id") }
                    putJsonObject("parent") { put("type", "string"); put("description", "Parent scenario to fork from (default: baseline)") }
                    putJsonObject("note") { put("type", "string"); put("description", "Human-readable description") }
                    putJsonObject("patches") { put("type", "array"); put("description", "Initial patch operations") }
                },
                required = listOf("id")
            )
        ) { args -> handleScenarioCreate(cm, store, args) }

        tool(server, "scenario_patch", "Apply patch operations to a scenario.",
            Tool.Input(
                properties = buildJsonObject {
                    putJsonObject("id") { put("type", "string"); put("description", "Scenario id to patch") }
                    putJsonObject("patches") { put("type", "array"); put("description", "List of patch operations") }
                    putJsonObject("replace") { put("type", "boolean"); put("description", "If true, replace the whole patch list instead of appending") }
                },
                required = listOf("id", "patches")
            )
        ) { args -> handleScenarioPatch(cm, store, args) }

        tool(server, "scenario_list", "List all scenarios with summary info.",
            Tool.Input()
        ) { _ -> handleScenarioList(store) }

        tool(server, "scenario_get", "Get the full patch list of a scenario.",
            Tool.Input(
                properties = buildJsonObject {
                    putJsonObject("id") { put("type", "string"); put("description", "Scenario id") }
                },
                required = listOf("id")
            )
        ) { args -> handleScenarioGet(store, args) }

        tool(server, "scenario_diff", "Paths whose effective value differs between two scenarios.",
            Tool.Input(
                properties = buildJsonObject {
                    putJsonObject("a") { put("type", "string"); put("description", "First scenario id") }
                    putJsonObject("b") { put("type", "string"); put("description", "Second scenario id") }
                },
                required = listOf("a", "b")
            )
        ) { args -> handleScenarioDiff(cm, store, args) }

        tool(server, "scenario_discard", "Remove a scenario and its cached results.",
            Tool.Input(
                properties = buildJsonObject {
                    putJsonObject("id") { put("type", "string"); put("description", "Scenario id to discard") }
                },
                required = listOf("id")
            )
        ) { args -> handleScenarioDiscard(store, args) }

        tool(server, "scenario_export", "Export a scenario's changes as path/old/new records. Never writes to assets/.",
            Tool.Input(
                properties = buildJsonObject {
                    putJsonObject("id") { put("type", "string"); put("description", "Scenario id to export") }
                },
                required = listOf("id")
            )
        ) { args -> handleScenarioExport(cm, store, args) }

        tool(server, "reload_base", "Re-read assets/ from disk. Reports scenarios created against an older base.",
            Tool.Input()
        ) { _ -> handleReloadBase(cm, store) }

        // --- Plans ---
        tool(server, "plan_put", "Store a named plan for a level.",
            Tool.Input(
                properties = buildJsonObject {
                    putJsonObject("name") { put("type", "string"); put("description", "Plan name") }
                    putJsonObject("plan") { put("type", "object"); put("description", "The plan object with forLevel, steps, etc.") }
                },
                required = listOf("name", "plan")
            )
        ) { args -> handlePlanPut(planStore, args) }

        tool(server, "plan_get", "Retrieve a stored plan by name.",
            Tool.Input(
                properties = buildJsonObject {
                    putJsonObject("name") { put("type", "string"); put("description", "Plan name") }
                },
                required = listOf("name")
            )
        ) { args -> handlePlanGet(planStore, args) }

        tool(server, "plan_list", "List all stored plans.",
            Tool.Input()
        ) { _ -> handlePlanList(planStore) }

        tool(server, "plan_delete", "Remove a stored plan.",
            Tool.Input(
                properties = buildJsonObject {
                    putJsonObject("name") { put("type", "string"); put("description", "Plan name to delete") }
                },
                required = listOf("name")
            )
        ) { args -> handlePlanDelete(planStore, args) }

        tool(server, "plan_check",
            "Execute only the at=0 steps of a plan to verify it works without running a full shift.",
            Tool.Input(
                properties = buildJsonObject {
                    putJsonObject("plan") { put("type", "string"); put("description", "Stored plan name") }
                    putJsonObject("inline") { put("type", "object"); put("description", "Inline plan object (alternative to plan name)") }
                    putJsonObject("forLevel") { put("type", "string"); put("description", "Level id (overrides plan's forLevel)") }
                    putJsonObject("scenario") { put("type", "string"); put("description", "Scenario id (default: baseline)") }
                }
            )
        ) { args -> handlePlanCheck(cm, store, planStore, args) }

        // --- Running ---
        tool(server, "run",
            "Run headless shifts for a cross-product of scenarios x levels x seeds and return aggregated statistics.",
            Tool.Input(
                properties = buildJsonObject {
                    putJsonObject("scenarios") { put("type", "array"); put("description", "Scenario ids (default: ['baseline'])") }
                    putJsonObject("levels") { put("type", "array"); put("description", "Level ids (default: all)") }
                    putJsonObject("player") { put("type", "object"); put("description", "PlayerSpec: {kind:'plan', plan?:name, inline?:planObj, decisionIntervalSeconds?:float}") }
                    putJsonObject("seedCount") { put("type", "integer"); put("description", "Seed count (default 30)") }
                    putJsonObject("explicitSeeds") { put("type", "array"); put("description", "Explicit seed values") }
                    putJsonObject("baseSeed") { put("type", "integer"); put("description", "First seed value (default: 1)") }
                    putJsonObject("metrics") { put("type", "array"); put("description", "Counter names to include") }
                    putJsonObject("strict") { put("type", "boolean"); put("description", "Fail on validation warnings (default: false)") }
                },
                required = listOf("player")
            )
        ) { args -> handleRun(cm, planStore, engine, runStore, args) }

        // --- Comparison ---
        tool(server, "sweep",
            "Vary one numeric parameter across values and return a table of per-value aggregated results.",
            Tool.Input(
                properties = buildJsonObject {
                    putJsonObject("base") { put("type", "string"); put("description", "Scenario id to fork from (default: baseline)") }
                    putJsonObject("path") { put("type", "string"); put("description", "Tunable path to vary, e.g. 'catalog/workers/anywork-1/hireCost'") }
                    putJsonObject("values") { put("type", "array"); put("description", "Explicit list of numeric values to sweep") }
                    putJsonObject("range") { put("type", "object"); put("description", "Generated range {from, to, steps}") }
                    putJsonObject("op") { put("type", "string"); put("description", "Patch operation: set (default), add, or mul") }
                    putJsonObject("levels") { put("type", "array"); put("description", "Level ids (default: all)") }
                    putJsonObject("player") { put("type", "object"); put("description", "PlayerSpec") }
                    putJsonObject("seeds") { put("type", "array"); put("description", "Explicit seed list") }
                    putJsonObject("seedCount") { put("type", "integer"); put("description", "Seed count if seeds not specified (default: 10)") }
                    putJsonObject("baseSeed") { put("type", "integer"); put("description", "First seed value (default: 1)") }
                    putJsonObject("metrics") { put("type", "array"); put("description", "Counter names to include") }
                },
                required = listOf("path", "player")
            )
        ) { args -> handleSweep(cm, store, planStore, engine, runStore, args) }

        tool(server, "compare",
            "Run two scenarios on the same levels and paired seeds; per-metric differences with 95% CIs and a significant flag.",
            Tool.Input(
                properties = buildJsonObject {
                    putJsonObject("a") { put("type", "string"); put("description", "First scenario id") }
                    putJsonObject("b") { put("type", "string"); put("description", "Second scenario id") }
                    putJsonObject("levels") { put("type", "array"); put("description", "Level ids (default: all)") }
                    putJsonObject("player") { put("type", "object"); put("description", "PlayerSpec") }
                    putJsonObject("seeds") { put("type", "array"); put("description", "Explicit seed list") }
                    putJsonObject("seedCount") { put("type", "integer"); put("description", "Seed count if seeds not specified (default: 10)") }
                    putJsonObject("baseSeed") { put("type", "integer"); put("description", "First seed value (default: 1)") }
                    putJsonObject("metrics") { put("type", "array"); put("description", "Counter names to include") }
                },
                required = listOf("a", "b", "player")
            )
        ) { args -> handleCompare(cm, store, planStore, engine, args) }

        tool(server, "run_details",
            "Paginated per-seed rows for a previous run result.",
            Tool.Input(
                properties = buildJsonObject {
                    putJsonObject("runId") { put("type", "string"); put("description", "Run id returned by run or sweep") }
                    putJsonObject("cursor") { put("type", "integer"); put("description", "0-based row offset (default: 0)") }
                    putJsonObject("rowCap") { put("type", "integer"); put("description", "Max rows per page, 1–200 (default: 50)") }
                },
                required = listOf("runId")
            )
        ) { args -> handleRunDetails(runStore, args) }

        tool(server, "describe_metrics",
            "Counter names seen in recent runs plus fixed report fields. Use this to choose metrics without guessing.",
            Tool.Input()
        ) { _ -> handleDescribeMetrics(runStore) }
    }

    private fun tool(
        server: Server,
        name: String,
        description: String,
        inputSchema: Tool.Input = Tool.Input(),
        handler: suspend (JsonObject) -> JsonElement
    ) {
        server.addTool(name, description, inputSchema) { req ->
            try {
                val args = req.arguments
                val result = handler(args)
                CallToolResult(content = listOf(TextContent(
                    text = FaultoryJson.instance.encodeToString(JsonElement.serializer(), result)
                )))
            } catch (e: OverlayException) {
                CallToolResult(
                    content = listOf(TextContent(text = buildJsonObject {
                        put("code", e.code)
                        put("path", e.path)
                        put("hint", e.message ?: "")
                    }.toString())),
                    isError = true
                )
            } catch (e: IllegalArgumentException) {
                CallToolResult(
                    content = listOf(TextContent(text = buildJsonObject {
                        put("code", "INVALID_ARGUMENT")
                        put("hint", e.message ?: "Invalid argument")
                    }.toString())),
                    isError = true
                )
            } catch (e: Exception) {
                CallToolResult(
                    content = listOf(TextContent(text = buildJsonObject {
                        put("code", "INTERNAL_ERROR")
                        put("hint", e.message ?: "Internal error")
                    }.toString())),
                    isError = true
                )
            }
        }
    }
}
