package dev.agenticscheduler.agent.context

import dev.agenticscheduler.domain.id.TaskId
import kotlin.test.*

class ContextAnchorAssemblyTest {
    private val anchor = AgentContextAnchor.Task(TaskId("018f6e68-7d0c-7000-8000-000000000001"))
    private fun assemble(request: ContextRequest, input: Long = 4096): ContextAssemblyResult =
        ContextAssembler(Utf8ByteBudgetMeter((input * 5 + 3) / 4, (input + 3) / 4)).assemble(request)

    @Test fun `mandatory ordering distinguishes anchor and tool continuation and measures each once`() {
        val request = ContextRequest("system", listOf("z" to "Z", "a" to "A"), "command", anchor, emptyList(), "paired-tool-result")
        val result = assertIs<ContextAssemblyResult.Ready>(assemble(request))
        assertEquals(listOf("SYSTEM", "TOOL_SCHEMA", "TOOL_SCHEMA", "CURRENT_COMMAND", "CONTEXT_ANCHOR", "TOOL_CONTINUATION"), result.parts.map { it.source })
        assertEquals(listOf("system", "a", "z", "command", "anchor", "required-tool-continuation"), result.parts.map { it.id })
        assertEquals(anchor.renderForModel(), result.parts[4].serialized)
        assertEquals(result.parts.sumOf { it.serialized.encodeToByteArray().size.toLong() }, result.usedUnits)
        assertEquals(0, result.rawMessageUnits)
    }

    @Test fun `no anchor no continuation preserves original assembly exactly`() {
        val result = assertIs<ContextAssemblyResult.Ready>(assemble(ContextRequest("S", emptyList(), "C", null, emptyList())))
        assertEquals(listOf(ContextPart("SYSTEM", "system", "S"), ContextPart("CURRENT_COMMAND", "command", "C")), result.parts)
        assertEquals(2, result.usedUnits)
    }

    @Test fun `latest required tool continuation is mandatory independently of any UI anchor`() {
        val result = assertIs<ContextAssemblyResult.Ready>(assemble(ContextRequest("S", emptyList(), "C", null, emptyList(), "pair")))
        assertEquals(6, result.usedUnits)
        assertTrue(result.parts.none { it.source == "CONTEXT_ANCHOR" })
        assertEquals("pair", result.parts.last().serialized)
        assertEquals("TOOL_CONTINUATION", result.parts.last().source)
    }

    @Test fun `exact mandatory limit retains anchor and cannot be displaced by any candidate`() {
        val cost = 2L + anchor.renderForModel().encodeToByteArray().size
        val request = ContextRequest("S", emptyList(), "C", anchor, ContextClass.entries.map { ContextCandidate(it.name, it, "lower priority", 999, 999) })
        val result = assertIs<ContextAssemblyResult.Ready>(assemble(request, cost))
        assertEquals(cost, result.usedUnits)
        assertEquals(3, result.parts.size)
        assertEquals("CONTEXT_ANCHOR", result.parts.last().source)
    }

    @Test fun `mandatory anchor overflow returns ContextTooLarge without silent omission`() {
        val result = assertIs<ContextAssemblyResult.ContextTooLarge>(assemble(ContextRequest("S", emptyList(), "C", anchor, emptyList()), 100))
        assertTrue(result.mandatoryUnits > result.maxInputUnits)
    }

    @Test fun `required continuation overflow fails closed instead of becoming a capped candidate`() {
        val result = assertIs<ContextAssemblyResult.ContextTooLarge>(assemble(ContextRequest("S", emptyList(), "C", anchor, emptyList(), "T".repeat(4096))))
        assertTrue(result.mandatoryUnits > result.maxInputUnits)
    }
}
