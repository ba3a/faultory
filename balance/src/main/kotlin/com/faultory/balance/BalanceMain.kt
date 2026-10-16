package com.faultory.balance

import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import kotlinx.coroutines.runBlocking
import java.nio.file.Paths

fun main(args: Array<String>) {
    val assetsRoot = if (args.isNotEmpty()) Paths.get(args[0]) else Paths.get("assets")
    val scratchDir = Paths.get(".balance")

    System.err.println("balance: loading content from $assetsRoot")
    val cm = ContentManager(assetsRoot)
    System.err.println("balance: content loaded (hash=${cm.baseHash})")

    val store = ScenarioStore(scratchDir)
    System.err.println("balance: ${store.list().size} scenario(s) loaded")

    val server = BalanceServer(cm, store).buildServer()
    System.err.println("balance: MCP server ready, listening on stdio")

    runBlocking { server.connect(StdioServerTransport()) }
}
