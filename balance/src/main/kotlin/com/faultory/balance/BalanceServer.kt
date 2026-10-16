package com.faultory.balance

import com.faultory.balance.tool.handleDescribeContent
import com.faultory.balance.tool.handleGetValue
import com.faultory.balance.tool.handleReloadBase
import com.faultory.balance.tool.handleScenarioCreate
import com.faultory.balance.tool.handleScenarioDiff
import com.faultory.balance.tool.handleScenarioDiscard
import com.faultory.balance.tool.handleScenarioGet
import com.faultory.balance.tool.handleScenarioList
import com.faultory.balance.tool.handleScenarioPatch
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

class BalanceServer(private val cm: ContentManager, private val store: ScenarioStore) {

    fun buildServer(): Server {
        val server = Server(
            serverInfo = Implementation(name = "balance", version = "0.1.0"),
            options = ServerOptions(
                capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))
            )
        )
        registerAll(server)
        return server
    }

    private fun registerAll(server: Server) {
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
        ) { _ -> handleScenarioList(store, buildJsonObject {}) }

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

        tool(server, "reload_base", "Re-read assets/ from disk. Reports scenarios created against an older base.",
            Tool.Input()
        ) { _ -> handleReloadBase(cm, store, buildJsonObject {}) }
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
                val args = req.params.arguments ?: buildJsonObject {}
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
