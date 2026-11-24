package com.faultory.balance

import io.modelcontextprotocol.kotlin.sdk.GetPromptRequest
import io.modelcontextprotocol.kotlin.sdk.Implementation
import io.modelcontextprotocol.kotlin.sdk.TextContent
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

/** Starts the real server entry point as a child process and talks to it over its stdin/stdout. */
class BalanceServerSmokeTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `server lists tools and the balance-loop prompt over stdio`() {
        val java = File(System.getProperty("java.home"), "bin/java").path
        val process = ProcessBuilder(
            java, "-cp", System.getProperty("java.class.path"),
            "com.faultory.balance.BalanceMainKt", TestAssets.root.toString()
        )
            .directory(tempDir.toFile())
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        try {
            runBlocking {
                withTimeout(TIMEOUT_MS) {
                    val transport = StdioClientTransport(
                        process.inputStream.asSource().buffered(),
                        process.outputStream.asSink().buffered()
                    )
                    val client = Client(Implementation(name = "smoke-test", version = "0"))
                    client.connect(transport)

                    val tools = client.listTools()!!.tools.map { it.name }
                    assertTrue("run" in tools && "sweep" in tools, "tools: $tools")

                    val prompts = client.listPrompts()!!.prompts.map { it.name }
                    assertContains(prompts, BalanceLoopPrompt.NAME)

                    val prompt = client.getPrompt(GetPromptRequest(BalanceLoopPrompt.NAME, null))!!
                    val text = (prompt.messages.single().content as TextContent).text
                    assertContains(text!!, "scenario_export")
                }
            }
        } finally {
            process.destroyForcibly().waitFor()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 90_000L
    }
}
