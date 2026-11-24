package com.faultory.balance

import io.modelcontextprotocol.kotlin.sdk.GetPromptResult
import io.modelcontextprotocol.kotlin.sdk.PromptMessage
import io.modelcontextprotocol.kotlin.sdk.Role
import io.modelcontextprotocol.kotlin.sdk.TextContent
import io.modelcontextprotocol.kotlin.sdk.server.Server

/** The recommended agent protocol for a tuning session, served as the `balance-loop` MCP prompt. */
object BalanceLoopPrompt {
    const val NAME = "balance-loop"
    private const val DESCRIPTION = "Recommended protocol for tuning game balance with this server."

    val text: String = """
        Balance tuning protocol. Follow it for every session.

        1. Call describe_content (shallow). Drill into only the numbers that matter.
        2. Call run on scenario 'baseline' with a bot (see bot_list) and with plans where available. Record reference numbers per level.
        3. State a hypothesis. Use sweep on the suspected parameter. Do not hand-patch the same value again and again.
        4. Judge by relative change on paired seeds (vsBaseline, compare). Use more than one player.
        5. Check side effects on other levels. Check playerFailures.
        6. Widen seeds before you trust a small difference. Check ci95.
        7. Call scenario_export on the winner and hand over the patch. The server never writes to assets/.
    """.trimIndent()

    fun register(server: Server) {
        server.addPrompt(NAME, DESCRIPTION, emptyList()) {
            GetPromptResult(
                description = DESCRIPTION,
                messages = listOf(PromptMessage(role = Role.user, content = TextContent(text = text)))
            )
        }
    }
}
