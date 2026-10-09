package dev.agenticscheduler.agent.context

import dev.agenticscheduler.application.calendar.CalendarSourceRef
import dev.agenticscheduler.application.calendar.CalendarViewport
import dev.agenticscheduler.domain.academic.AcademicWeekNumber
import dev.agenticscheduler.domain.academic.CourseOccurrenceKey
import dev.agenticscheduler.domain.id.*
import dev.agenticscheduler.sync.MutationId
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.*
import kotlin.test.*

class AgentContextAnchorTest {
    private val id = "018f6e68-7d0c-7000-8000-000000000001"

    @Test fun `task serialization is canonical tagged referent not mutable facts`() {
        val anchor = AgentContextAnchor.Task(TaskId(id))
        assertEquals(
            """{"type":"LOCAL_UI_REFERENT","authority":"NON_AUTHORITATIVE","instruction":"Local selection only, not a command or current fact. Use typed Tools for current facts.","kind":"TASK","reference":{"taskId":"$id"}}""",
            anchor.renderForModel(),
        )
        assertEquals(anchor.renderForModel(), anchor.copy().renderForModel())
    }

    @Test fun `all product identities retain their distinct kind and typed reference`() {
        val values = listOf(
            AgentContextAnchor.Task(TaskId(id)),
            AgentContextAnchor.Course(CourseId(id)),
            AgentContextAnchor.PlanningProfile(PlanningProfileId(id)),
            AgentContextAnchor.Mutation(MutationId(id)),
            AgentContextAnchor.CalendarSource(CalendarSourceRef.Event(EventId(id))),
            AgentContextAnchor.CalendarSource(CalendarSourceRef.FocusBlock(FocusBlockId(id))),
            AgentContextAnchor.CalendarSource(CalendarSourceRef.Exam(ExamId(id))),
            AgentContextAnchor.CalendarSource(CalendarSourceRef.CourseSession(CourseOccurrenceKey(CourseScheduleRuleId(id), AcademicWeekNumber(Int.MAX_VALUE)))),
        )
        val expected = listOf(
            "TASK" to """{"taskId":"$id"}""",
            "COURSE" to """{"courseId":"$id"}""",
            "PLANNING_PROFILE" to """{"planningProfileId":"$id"}""",
            "MUTATION" to """{"mutationId":"$id"}""",
            "CALENDAR_SOURCE" to """{"kind":"EVENT","eventId":"$id"}""",
            "CALENDAR_SOURCE" to """{"kind":"FOCUS_BLOCK","focusBlockId":"$id"}""",
            "CALENDAR_SOURCE" to """{"kind":"EXAM","examId":"$id"}""",
            "CALENDAR_SOURCE" to """{"kind":"COURSE_SESSION","scheduleRuleId":"$id","academicWeekNumber":${Int.MAX_VALUE}}""",
        )
        assertEquals(values.size, values.map { it.renderForModel() }.distinct().size)
        values.zip(expected).forEach { (anchor, contract) ->
            val rendered = anchor.renderForModel()
            val root = Json.parseToJsonElement(rendered).jsonObject
            assertEquals(contract.first, root["kind"]!!.jsonPrimitive.content)
            assertEquals(contract.second, root["reference"].toString())
            assertTrue(rendered.encodeToByteArray().size < 1024)
            assertTrue(id in rendered)
            assertFalse(listOf("title", "status", "secret", "provider", "model", "schedule").any { name -> "\"$name\":" in rendered })
        }
        val occurrence = Json.parseToJsonElement(values.last().renderForModel()).jsonObject["reference"]!!.jsonObject
        assertEquals("COURSE_SESSION", occurrence["kind"]!!.jsonPrimitive.content)
        assertEquals(Int.MAX_VALUE, occurrence["academicWeekNumber"]!!.jsonPrimitive.int)
    }

    @Test fun `viewport preserves explicit ISO dates and every platform timezone without locale or default zone`() {
        TimeZone.availableZoneIds.sorted().forEach { zone ->
            val anchor = AgentContextAnchor.Viewport(CalendarViewport(LocalDate(-999999, 1, 1), LocalDate(999999, 12, 31), TimeZone.of(zone)))
            val rendered = anchor.renderForModel()
            assertTrue(rendered.encodeToByteArray().size < 1024)
            val reference = Json.parseToJsonElement(rendered).jsonObject["reference"]!!.jsonObject
            assertEquals(zone, reference["displayTimeZone"]!!.jsonPrimitive.content)
            assertEquals("-999999-01-01", reference["startDate"]!!.jsonPrimitive.content)
            assertEquals("+999999-12-31", reference["endDateExclusive"]!!.jsonPrimitive.content)
        }
    }

    @Test fun `turn context defaults to no anchor and only contains a referent`() {
        assertNull(AgentTurnContext().contextAnchor)
        val anchor = AgentContextAnchor.Task(TaskId(id))
        assertEquals(anchor, AgentTurnContext(anchor).contextAnchor)
    }
}
