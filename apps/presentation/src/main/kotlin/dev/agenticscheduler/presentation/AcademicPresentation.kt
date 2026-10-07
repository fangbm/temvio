package dev.agenticscheduler.presentation

import androidx.compose.runtime.*
import dev.agenticscheduler.application.academic.*
import dev.agenticscheduler.application.editing.*
import dev.agenticscheduler.application.history.ConflictAwareRead
import dev.agenticscheduler.domain.academic.*
import dev.agenticscheduler.domain.id.*
import dev.agenticscheduler.domain.planning.Flexibility
import dev.agenticscheduler.domain.planning.PinState
import dev.agenticscheduler.domain.time.ZonedTimeRange
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.*

enum class AcademicKind(val label: String) {
    YEAR("AcademicYear"), SEMESTER("Semester"), TEMPLATE("PeriodTemplate"), COURSE("Course"), RULE("CourseScheduleRule"), EXAM("Exam")
}
sealed interface AcademicRecord {
    val kind: AcademicKind
    val id: String
    val title: String
    data class Year(val value: AcademicYear) : AcademicRecord { override val kind = AcademicKind.YEAR; override val id = value.id.value; override val title = value.name }
    data class Term(val value: Semester) : AcademicRecord { override val kind = AcademicKind.SEMESTER; override val id = value.id.value; override val title = value.name }
    data class Template(val value: PeriodTemplate) : AcademicRecord { override val kind = AcademicKind.TEMPLATE; override val id = value.id.value; override val title = value.name }
    data class Subject(val value: Course) : AcademicRecord { override val kind = AcademicKind.COURSE; override val id = value.id.value; override val title = value.name }
    data class Rule(val value: CourseScheduleRule) : AcademicRecord { override val kind = AcademicKind.RULE; override val id = value.id.value; override val title = "${value.dayOfWeek} · weeks ${value.teachingWeeks.weeks.joinToString { it.value.toString() }}" }
    data class Assessment(val value: Exam) : AcademicRecord { override val kind = AcademicKind.EXAM; override val id = value.id.value; override val title = value.title }
}

fun AcademicAuthoringFacts.records(kind: AcademicKind): List<AcademicRecord> = when (kind) {
    AcademicKind.YEAR -> academicYears.map { AcademicRecord.Year(it) }
    AcademicKind.SEMESTER -> semesters.map { AcademicRecord.Term(it) }
    AcademicKind.TEMPLATE -> periodTemplates.map { AcademicRecord.Template(it) }
    AcademicKind.COURSE -> courses.map { AcademicRecord.Subject(it) }
    AcademicKind.RULE -> courseScheduleRules.map { AcademicRecord.Rule(it) }
    AcademicKind.EXAM -> exams.map { AcademicRecord.Assessment(it) }
}.sortedWith(compareBy<AcademicRecord> { it.title.lowercase() }.thenBy { it.id })

enum class RuleTimeChoice { CLOCK_TIME, PERIOD_BASED }
enum class ExamScheduleChoice { UNSCHEDULED, DATE_ONLY, EXACT }
data class WeekDraft(val number: String = "", val start: String = "", val end: String = "")
data class PeriodDraft(val number: String = "", val start: String = "", val end: String = "")

/** Ephemeral form text, not a second wire/source model. Missing choices remain missing. */
data class AcademicDraft(
    val kind: AcademicKind,
    val before: AcademicRecord? = null,
    val name: String = "",
    val start: String = "",
    val end: String = "",
    val yearId: AcademicYearId? = null,
    val semesterId: SemesterId? = null,
    val courseId: CourseId? = null,
    val templateId: PeriodTemplateId? = null,
    val zone: String = "",
    val code: String = "",
    val room: String = "",
    val weeks: List<WeekDraft> = emptyList(),
    val periods: List<PeriodDraft> = emptyList(),
    val weekday: DayOfWeek? = null,
    val teachingWeeks: String = "",
    val ruleTime: RuleTimeChoice? = null,
    val examSchedule: ExamScheduleChoice? = null,
) {
    companion object {
        fun from(record: AcademicRecord): AcademicDraft = when (record) {
            is AcademicRecord.Year -> record.value.let { AcademicDraft(record.kind, record, it.name, it.startDate.toString(), it.endDateExclusive.toString()) }
            is AcademicRecord.Term -> record.value.let { AcademicDraft(record.kind, record, it.name, it.startDate.toString(), it.endDateExclusive.toString(), yearId = it.academicYearId, zone = it.timeZone.id,
                weeks = it.academicWeeks.map { w -> WeekDraft(w.number.value.toString(), w.startDate.toString(), w.endDateExclusive.toString()) }) }
            is AcademicRecord.Template -> record.value.let { AcademicDraft(record.kind, record, it.name, periods = it.periods.map { p -> PeriodDraft(p.number.value.toString(), p.start.toString(), p.endExclusive.toString()) }) }
            is AcademicRecord.Subject -> record.value.let { AcademicDraft(record.kind, record, it.name, semesterId = it.semesterId, code = it.code.orEmpty()) }
            is AcademicRecord.Rule -> record.value.let { r ->
                when (val t = r.time) {
                    is CourseTimeSpec.ClockTime -> AcademicDraft(record.kind, record, courseId = r.courseId, weekday = r.dayOfWeek, teachingWeeks = r.teachingWeeks.weeks.joinToString(",") { it.value.toString() }, ruleTime = RuleTimeChoice.CLOCK_TIME, start = t.start.toString(), end = t.endExclusive.toString(), room = r.room.orEmpty())
                    is CourseTimeSpec.PeriodBased -> AcademicDraft(record.kind, record, courseId = r.courseId, weekday = r.dayOfWeek, teachingWeeks = r.teachingWeeks.weeks.joinToString(",") { it.value.toString() }, ruleTime = RuleTimeChoice.PERIOD_BASED, templateId = t.periodTemplateId, start = t.startPeriod.value.toString(), end = t.endPeriodInclusive.value.toString(), room = r.room.orEmpty())
                }
            }
            is AcademicRecord.Assessment -> record.value.let { e ->
                val base = AcademicDraft(record.kind, record, e.title, semesterId = e.semesterId, courseId = e.courseId)
                when (val t = e.schedule) {
                    ExamSchedule.Unscheduled -> base.copy(examSchedule = ExamScheduleChoice.UNSCHEDULED)
                    is ExamSchedule.DateOnly -> base.copy(examSchedule = ExamScheduleChoice.DATE_ONLY, start = t.date.toString())
                    is ExamSchedule.Exact -> base.copy(examSchedule = ExamScheduleChoice.EXACT, start = t.time.start.toLocalDateTime(t.time.timeZone).toString(), end = t.time.endExclusive.toLocalDateTime(t.time.timeZone).toString())
                }
            }
        }
    }
}

sealed interface AcademicCommand {
    data class Year(val input: AcademicYearInput) : AcademicCommand
    data class Term(val input: SemesterInput) : AcademicCommand
    data class Template(val input: PeriodTemplateInput) : AcademicCommand
    data class Subject(val input: CourseInput) : AcademicCommand
    data class Rule(val input: CourseScheduleRuleInput) : AcademicCommand
    data class Assessment(val input: ExamInput) : AcademicCommand
}

/** Parse using value constructors and the existing strict Application time validator. */
fun AcademicDraft.command(facts: AcademicAuthoringFacts, eventEditor: EventEditingService): AcademicCommand = when (kind) {
    AcademicKind.YEAR -> AcademicCommand.Year(AcademicYearInput(name, LocalDate.parse(start), LocalDate.parse(end)))
    AcademicKind.SEMESTER -> AcademicCommand.Term(SemesterInput(requireNotNull(yearId) { "Choose an AcademicYear." }, name, LocalDate.parse(start), LocalDate.parse(end), TimeZone.of(zone),
        weeks.map { AcademicWeek(AcademicWeekNumber(it.number.toInt()), LocalDate.parse(it.start), LocalDate.parse(it.end)) }.toImmutableList()))
    AcademicKind.TEMPLATE -> AcademicCommand.Template(PeriodTemplateInput(name, periods.map { AcademicPeriod(AcademicPeriodNumber(it.number.toInt()), LocalTime.parse(it.start), LocalTime.parse(it.end)) }.toImmutableList()))
    AcademicKind.COURSE -> AcademicCommand.Subject(CourseInput(requireNotNull(semesterId) { "Choose a Semester." }, name, code.takeUnless { it.isEmpty() }))
    AcademicKind.RULE -> AcademicCommand.Rule(CourseScheduleRuleInput(requireNotNull(courseId) { "Choose a saved Course." }, requireNotNull(weekday) { "Choose a weekday." },
        TeachingWeekSet.of(teachingWeeks.split(',').map { AcademicWeekNumber(it.trim().toInt()) }), when (ruleTime) {
            RuleTimeChoice.CLOCK_TIME -> CourseTimeSpec.ClockTime(LocalTime.parse(start), LocalTime.parse(end))
            RuleTimeChoice.PERIOD_BASED -> CourseTimeSpec.PeriodBased(requireNotNull(templateId) { "Choose an existing PeriodTemplate." }, AcademicPeriodNumber(start.toInt()), AcademicPeriodNumber(end.toInt()))
            null -> error("Choose ClockTime or PeriodBased.")
        }, room.takeUnless { it.isEmpty() }))
    AcademicKind.EXAM -> {
        val term = facts.semesters.firstOrNull { it.id == semesterId } ?: error("Choose an existing Semester.")
        val schedule = when (examSchedule) {
            ExamScheduleChoice.UNSCHEDULED -> ExamSchedule.Unscheduled
            ExamScheduleChoice.DATE_ONLY -> ExamSchedule.DateOnly(LocalDate.parse(start))
            ExamScheduleChoice.EXACT -> {
                val preview = eventEditor.previewCreate(CreateEventInput("Exam time validation", EventTimeInput.Zoned(LocalDateTime.parse(start), LocalDateTime.parse(end), term.timeZone), Flexibility.HARD, PinState.UNPINNED))
                require(preview is EditingResult.Success) { "Invalid Exact range or DST transition: $preview" }
                ExamSchedule.Exact(preview.value.time as ZonedTimeRange)
            }
            null -> error("Choose an Exam schedule type.")
        }
        AcademicCommand.Assessment(ExamInput(term.id, courseId, name, schedule))
    }
}

enum class AcademicFeedback { INVALID, NOT_FOUND, ALREADY_EXISTS, STALE, SYNC_CONFLICT, TECHNICAL_ERROR }

/** App-owned feature coordinator. It owns no committed facts and never accesses repositories. */
class AcademicScreenCoordinator(val service: AcademicAuthoringService, private val eventEditor: EventEditingService) {
    var read by mutableStateOf<ConflictAwareRead<AcademicAuthoringFacts>?>(null); private set
    var loading by mutableStateOf(false); private set
    var technicalError by mutableStateOf<String?>(null); private set
    var selected by mutableStateOf<AcademicRecord?>(null)
    var requestedCourse: CourseId? = null
    var requestedExam: ExamId? = null
    var draft by mutableStateOf<AcademicDraft?>(null)
    var originalDraft by mutableStateOf<AcademicDraft?>(null); private set
    var feedback by mutableStateOf<AcademicFeedback?>(null); private set
    var feedbackDetail by mutableStateOf<String?>(null); private set
    var saving by mutableStateOf(false); private set
    var lastCommittedMutationId by mutableStateOf<String?>(null); private set
    private var loadGeneration = 0
    val facts get() = (read as? ConflictAwareRead.Projected)?.value
    val dirty get() = draft != originalDraft

    suspend fun refresh() {
        val generation = ++loadGeneration
        loading = true
        try {
            val result = service.loadFacts()
            if (generation == loadGeneration) { read = result; technicalError = null }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { if (generation == loadGeneration) { read = null; technicalError = "Academic load failed. Retry explicitly." } }
        finally { if (generation == loadGeneration) loading = false }
    }
    fun create(kind: AcademicKind, courseId: CourseId? = null) { open(AcademicDraft(kind, courseId = courseId)) }
    fun edit(record: AcademicRecord) { open(AcademicDraft.from(record)) }
    private fun open(value: AcademicDraft) { if (!saving) { draft = value; originalDraft = value; feedback = null; feedbackDetail = null } }
    fun closeDraft() { if (!saving) { draft = null; originalDraft = null; feedback = null; feedbackDetail = null } }
    fun reloadDraftFromFacts() {
        val before = draft?.before ?: return
        val latest = facts?.records(before.kind)?.firstOrNull { it.id == before.id }
        if (latest == null) { feedback = AcademicFeedback.NOT_FOUND; feedbackDetail = "Source no longer available. Cancel this draft." }
        else edit(latest)
    }
    suspend fun save() {
        if (saving) return
        val current = draft ?: return
        val snapshot = facts ?: return
        val command = try { current.command(snapshot, eventEditor) } catch (_: IllegalArgumentException) {
            reject(AcademicFeedback.INVALID, "Check required selections, dates, week/period rows and time range. DST gaps/ambiguity are rejected."); return
        } catch (_: IllegalStateException) { reject(AcademicFeedback.INVALID, "Choose all required prerequisites and time/schedule types."); return }
        saving = true
        try {
            val result: AcademicEditingResult<AcademicRecord> = when (command) {
                is AcademicCommand.Year -> (current.before as? AcademicRecord.Year)?.let { service.updateAcademicYear(it.value.id, command.input, it.value) }?.map { AcademicRecord.Year(it) } ?: service.createAcademicYear(command.input).map { AcademicRecord.Year(it) }
                is AcademicCommand.Term -> (current.before as? AcademicRecord.Term)?.let { service.updateSemester(it.value.id, command.input, it.value) }?.map { AcademicRecord.Term(it) } ?: service.createSemester(command.input).map { AcademicRecord.Term(it) }
                is AcademicCommand.Template -> (current.before as? AcademicRecord.Template)?.let { service.updatePeriodTemplate(it.value.id, command.input, it.value) }?.map { AcademicRecord.Template(it) } ?: service.createPeriodTemplate(command.input).map { AcademicRecord.Template(it) }
                is AcademicCommand.Subject -> (current.before as? AcademicRecord.Subject)?.let { service.updateCourse(it.value.id, command.input, it.value) }?.map { AcademicRecord.Subject(it) } ?: service.createCourse(command.input).map { AcademicRecord.Subject(it) }
                is AcademicCommand.Rule -> (current.before as? AcademicRecord.Rule)?.let { service.updateRule(it.value.id, command.input, it.value) }?.map { AcademicRecord.Rule(it) } ?: service.createRule(command.input).map { AcademicRecord.Rule(it) }
                is AcademicCommand.Assessment -> (current.before as? AcademicRecord.Assessment)?.let { service.updateExam(it.value.id, command.input, it.value) }?.map { AcademicRecord.Assessment(it) } ?: service.createExam(command.input).map { AcademicRecord.Assessment(it) }
            }
            when (result) {
                is AcademicEditingResult.Success -> { selected = result.value; lastCommittedMutationId = result.mutationId.value; draft = null; originalDraft = null; feedback = null; feedbackDetail = null; refresh() }
                is AcademicEditingResult.Invalid -> reject(AcademicFeedback.INVALID, result.issues.joinToString("\n"))
                AcademicEditingResult.NotFound -> reject(AcademicFeedback.NOT_FOUND, "Source disappeared. Refresh and cancel/reload; no change saved.")
                AcademicEditingResult.AlreadyExists -> reject(AcademicFeedback.ALREADY_EXISTS, "ID collision. No change saved; retry explicitly.")
                AcademicEditingResult.Stale -> reject(AcademicFeedback.STALE, "Source changed. Draft retained. Refresh, then explicitly reload before saving.")
                is AcademicEditingResult.BlockedBySyncConflict -> reject(AcademicFeedback.SYNC_CONFLICT, "Unresolved Sync conflict in consumed facts. No change saved. ${result.blocks.joinToString()}")
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { reject(AcademicFeedback.TECHNICAL_ERROR, "Save failed. No success is claimed; refresh to inspect durable state before retrying.") }
        finally { saving = false }
    }
    private fun reject(reason: AcademicFeedback, detail: String) { feedback = reason; feedbackDetail = detail }
}

private fun <T, R> AcademicEditingResult<T>.map(transform: (T) -> R): AcademicEditingResult<R> = when (this) {
    is AcademicEditingResult.Success -> AcademicEditingResult.Success<R>(transform(value), mutationId)
    is AcademicEditingResult.Invalid -> AcademicEditingResult.Invalid(issues)
    AcademicEditingResult.NotFound -> AcademicEditingResult.NotFound
    AcademicEditingResult.AlreadyExists -> AcademicEditingResult.AlreadyExists
    AcademicEditingResult.Stale -> AcademicEditingResult.Stale
    is AcademicEditingResult.BlockedBySyncConflict -> AcademicEditingResult.BlockedBySyncConflict(blocks)
}
