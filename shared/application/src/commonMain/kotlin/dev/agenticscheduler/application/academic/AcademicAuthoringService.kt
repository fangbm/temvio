package dev.agenticscheduler.application.academic

import dev.agenticscheduler.application.history.*
import dev.agenticscheduler.application.id.UuidV7Generator
import dev.agenticscheduler.application.persistence.AcademicRepository
import dev.agenticscheduler.domain.academic.*
import dev.agenticscheduler.domain.id.*
import dev.agenticscheduler.sync.*
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.first

/** Local explicit authoring. Each call is one HST-001 command, never a UI repository upsert. */
class AcademicAuthoringService(
    private val academics: AcademicRepository,
    private val ids: UuidV7Generator,
    private val mutations: MutationCoordinator,
    private val conflictWritePolicy: SyncConflictWritePolicy,
    private val sourceFacts: ConflictAwareSourceFactQuery,
) {
    suspend fun createAcademicYear(input: AcademicYearInput): AcademicEditingResult<AcademicYear> = write(
        input, true, { null }, null,
        { AcademicYear(AcademicYearId(ids.next()), input.name, input.startDate, input.endDateExclusive) },
        academics::upsertAcademicYear, { before, after -> AcademicYearPut(before?.toSemanticImage(), after.toSemanticImage()) },
    )

    suspend fun updateAcademicYear(id: AcademicYearId, input: AcademicYearInput, expectedBefore: AcademicYear? = null): AcademicEditingResult<AcademicYear> = write(
        input, false, { academics.getAcademicYear(id) }, expectedBefore,
        { AcademicYear(id, input.name, input.startDate, input.endDateExclusive) },
        academics::upsertAcademicYear, { before, after -> AcademicYearPut(before?.toSemanticImage(), after.toSemanticImage()) },
    )

    suspend fun createSemester(input: SemesterInput): AcademicEditingResult<Semester> = write(
        input, true, { null }, null,
        { Semester(SemesterId(ids.next()), input.academicYearId, input.name, input.startDate, input.endDateExclusive, input.timeZone, input.academicWeeks) },
        academics::upsertSemester, { before, after -> SemesterPut(before?.toSemanticImage(), after.toSemanticImage()) },
    )

    suspend fun updateSemester(id: SemesterId, input: SemesterInput, expectedBefore: Semester? = null): AcademicEditingResult<Semester> = write(
        input, false, { academics.getSemester(id) }, expectedBefore,
        { Semester(id, input.academicYearId, input.name, input.startDate, input.endDateExclusive, input.timeZone, input.academicWeeks) },
        academics::upsertSemester, { before, after -> SemesterPut(before?.toSemanticImage(), after.toSemanticImage()) },
    )

    suspend fun createPeriodTemplate(input: PeriodTemplateInput): AcademicEditingResult<PeriodTemplate> = write(
        input, true, { null }, null,
        { PeriodTemplate(PeriodTemplateId(ids.next()), input.name, input.periods) },
        academics::upsertPeriodTemplate, { before, after -> PeriodTemplatePut(before?.toSemanticImage(), after.toSemanticImage()) },
    )

    suspend fun updatePeriodTemplate(id: PeriodTemplateId, input: PeriodTemplateInput, expectedBefore: PeriodTemplate? = null): AcademicEditingResult<PeriodTemplate> = write(
        input, false, { academics.getPeriodTemplate(id) }, expectedBefore,
        { PeriodTemplate(id, input.name, input.periods) },
        academics::upsertPeriodTemplate, { before, after -> PeriodTemplatePut(before?.toSemanticImage(), after.toSemanticImage()) },
    )

    suspend fun createCourse(input: CourseInput): AcademicEditingResult<Course> = write(
        input, true, { null }, null,
        { Course(CourseId(ids.next()), input.semesterId, input.name, input.code) },
        academics::upsertCourse, { before, after -> CoursePut(before?.toSemanticImage(), after.toSemanticImage()) },
    )

    suspend fun updateCourse(id: CourseId, input: CourseInput, expectedBefore: Course? = null): AcademicEditingResult<Course> = write(
        input, false, { academics.getCourse(id) }, expectedBefore,
        { Course(id, input.semesterId, input.name, input.code) },
        academics::upsertCourse, { before, after -> CoursePut(before?.toSemanticImage(), after.toSemanticImage()) },
    )

    suspend fun createRule(input: CourseScheduleRuleInput): AcademicEditingResult<CourseScheduleRule> = write(
        input, true, { null }, null,
        { CourseScheduleRule(CourseScheduleRuleId(ids.next()), input.courseId, input.dayOfWeek, input.teachingWeeks, input.time, input.room) },
        academics::upsertCourseScheduleRule, { before, after -> CourseScheduleRulePut(before?.toSemanticImage(), after.toSemanticImage()) },
    )

    suspend fun updateRule(id: CourseScheduleRuleId, input: CourseScheduleRuleInput, expectedBefore: CourseScheduleRule? = null): AcademicEditingResult<CourseScheduleRule> = write(
        input, false, { academics.getCourseScheduleRule(id) }, expectedBefore,
        { CourseScheduleRule(id, input.courseId, input.dayOfWeek, input.teachingWeeks, input.time, input.room) },
        academics::upsertCourseScheduleRule, { before, after -> CourseScheduleRulePut(before?.toSemanticImage(), after.toSemanticImage()) },
    )

    suspend fun createExam(input: ExamInput): AcademicEditingResult<Exam> = write(
        input, true, { null }, null,
        { Exam(ExamId(ids.next()), input.semesterId, input.courseId, input.title, input.schedule) },
        academics::upsertExam, { before, after -> ExamPut(before?.toSemanticImage(), after.toSemanticImage()) },
    )

    suspend fun updateExam(id: ExamId, input: ExamInput, expectedBefore: Exam? = null): AcademicEditingResult<Exam> = write(
        input, false, { academics.getExam(id) }, expectedBefore,
        { Exam(id, input.semesterId, input.courseId, input.title, input.schedule) },
        academics::upsertExam, { before, after -> ExamPut(before?.toSemanticImage(), after.toSemanticImage()) },
    )

    private suspend fun <T : Any> write(
        input: Any,
        create: Boolean,
        readBefore: suspend () -> T?,
        expectedBefore: T?,
        build: () -> T,
        save: suspend (T) -> Unit,
        encode: (T?, T) -> EntityMutation,
    ): AcademicEditingResult<T> {
        val fields = academicFields(input)
        if (fields.isNotEmpty()) return AcademicEditingResult.Invalid(fields.toImmutableList())
        var rejected: AcademicEditingResult<Nothing>? = null
        val execution = mutations.executeIfAny(MutationOrigin.User) {
            val before = readBefore()
            if (!create && before == null) {
                rejected = AcademicEditingResult.NotFound
                return@executeIfAny null
            }
            if (expectedBefore != null && expectedBefore != before) {
                rejected = AcademicEditingResult.Stale
                return@executeIfAny null
            }
            val after = build()
            val current = snapshot()
            if (create && current.contains(after)) {
                rejected = AcademicEditingResult.AlreadyExists
                return@executeIfAny null
            }
            val graph = current.replacing(after)
            val validation = AcademicGraphValidation(graph)
            validation.affected(before, after)
            // Complete fact probes guard the facts consumed by this whole-form command.
            // They are never recorded: only the real before/after operation is journaled.
            val blocks = conflictWritePolicy.blocks(validation.reads.values.sortedWith(compareBy(EntityMutation::entityKind, EntityMutation::entityId)))
            if (blocks.isNotEmpty()) {
                rejected = AcademicEditingResult.BlockedBySyncConflict(blocks.toImmutableList())
                return@executeIfAny null
            }
            if (validation.issues.isNotEmpty()) {
                rejected = AcademicEditingResult.Invalid(validation.issues.distinct().toImmutableList())
                return@executeIfAny null
            }
            save(after)
            record(encode(before, after))
            after
        }
        return if (execution == null) requireNotNull(rejected) else AcademicEditingResult.Success(requireNotNull(execution.value), execution.mutationId)
    }

    /** D8 provisional projection is read-only and visibly carries OPEN conflicts. */
    suspend fun loadFacts(): ConflictAwareRead<AcademicAuthoringFacts> {
        val graph = snapshot()
        val refs = mutableListOf<SyncConflictProjectionRef>()
        val kinds = listOf(
            EntityKind.ACADEMIC_YEAR to graph.years.map { AcademicYearPut(null, it.toSemanticImage()) },
            EntityKind.SEMESTER to graph.semesters.map { SemesterPut(null, it.toSemanticImage()) },
            EntityKind.COURSE to graph.courses.map { CoursePut(null, it.toSemanticImage()) },
            EntityKind.PERIOD_TEMPLATE to graph.templates.map { PeriodTemplatePut(null, it.toSemanticImage()) },
            EntityKind.COURSE_SCHEDULE_RULE to graph.rules.map { CourseScheduleRulePut(null, it.toSemanticImage()) },
            EntityKind.EXAM to graph.exams.map { ExamPut(null, it.toSemanticImage()) },
        )
        val projected = mutableListOf<EntityMutation>()
        for ((kind, facts) in kinds) {
            when (val result = sourceFacts.projectCollection(kind, facts)) {
                is ConflictCollectionProjection.Unprojectable -> return ConflictAwareRead.Unprojectable(result.conflictIds, result.reason)
                is ConflictCollectionProjection.Projected -> { projected += result.mutations; refs += result.syncConflictRefs }
            }
        }
        return try {
            ConflictAwareRead.Projected(AcademicAuthoringFacts(
                projected.filterIsInstance<AcademicYearPut>().map { it.after.toDomain() }.toImmutableList(),
                projected.filterIsInstance<SemesterPut>().map { it.after.toDomain() }.toImmutableList(),
                projected.filterIsInstance<CoursePut>().map { it.after.toDomain() }.toImmutableList(),
                projected.filterIsInstance<PeriodTemplatePut>().map { it.after.toDomain() }.toImmutableList(),
                projected.filterIsInstance<CourseScheduleRulePut>().map { it.after.toDomain() }.toImmutableList(),
                projected.filterIsInstance<ExamPut>().map { it.after.toDomain() }.toImmutableList(),
            ), refs.toList())
        } catch (_: IllegalArgumentException) {
            ConflictAwareRead.Unprojectable(refs.flatMap { it.conflictIds }.distinct().sorted(), "Academic provisional image violates Domain construction invariants.")
        }
    }

    private suspend fun snapshot() = AcademicGraph(
        academics.observeAcademicYears().first(), academics.observeSemesters().first(), academics.observeCourses().first(),
        academics.observePeriodTemplates().first(), academics.observeCourseScheduleRules().first(),
        academics.observeAcademicHolidays().first(), academics.observeCourseOccurrenceExceptions().first(), academics.observeExams().first(),
    )
}

private data class AcademicGraph(
    val years: List<AcademicYear>, val semesters: List<Semester>, val courses: List<Course>,
    val templates: List<PeriodTemplate>, val rules: List<CourseScheduleRule>, val holidays: List<AcademicHoliday>,
    val exceptions: List<CourseOccurrenceException>, val exams: List<Exam>,
) {
    fun contains(value: Any): Boolean = when (value) {
        is AcademicYear -> years.any { it.id == value.id }
        is Semester -> semesters.any { it.id == value.id }
        is Course -> courses.any { it.id == value.id }
        is PeriodTemplate -> templates.any { it.id == value.id }
        is CourseScheduleRule -> rules.any { it.id == value.id }
        is Exam -> exams.any { it.id == value.id }
        else -> error("Unsupported academic aggregate.")
    }
    fun replacing(after: Any): AcademicGraph = when (after) {
        is AcademicYear -> copy(years = years.filter { it.id != after.id } + after)
        is Semester -> copy(semesters = semesters.filter { it.id != after.id } + after)
        is Course -> copy(courses = courses.filter { it.id != after.id } + after)
        is PeriodTemplate -> copy(templates = templates.filter { it.id != after.id } + after)
        is CourseScheduleRule -> copy(rules = rules.filter { it.id != after.id } + after)
        is Exam -> copy(exams = exams.filter { it.id != after.id } + after)
        else -> error("Unsupported academic aggregate.")
    }
}

/** Validates only the candidate's affected graph, preserving unrelated legacy facts. */
private class AcademicGraphValidation(private val graph: AcademicGraph) {
    val issues = mutableListOf<AcademicEditingIssue>()
    val reads = linkedMapOf<Pair<EntityKind, String>, EntityMutation>()
    private val validatedCourses = mutableSetOf<CourseId>()
    private fun read(value: EntityMutation) { reads[value.entityKind to value.entityId] = value }

    fun affected(before: Any?, after: Any) {
        when (after) {
            is AcademicYear -> {
                read(AcademicYearPut(null, after.toSemanticImage()))
                graph.semesters.filter { it.academicYearId == after.id }.sortedBy { it.id.value }.forEach(::semester)
            }
            is Semester -> {
                semester(after)
                holidays(after)
                graph.courses.filter { it.semesterId == after.id }.sortedBy { it.id.value }.forEach(::course)
                graph.exams.filter { it.semesterId == after.id }.sortedBy { it.id.value }.forEach(::exam)
            }
            is Course -> {
                course(after)
                graph.exams.filter { it.courseId == after.id }.sortedBy { it.id.value }.forEach(::exam)
            }
            is PeriodTemplate -> {
                read(PeriodTemplatePut(null, after.toSemanticImage()))
                graph.rules.filter { (it.time as? CourseTimeSpec.PeriodBased)?.periodTemplateId == after.id }
                    .map { it.courseId }.distinct().sortedBy { it.value }.forEach(::courseById)
            }
            is CourseScheduleRule -> {
                read(CourseScheduleRulePut(null, after.toSemanticImage()))
                listOfNotNull((before as? CourseScheduleRule)?.courseId, after.courseId).distinct().sortedBy { it.value }.forEach(::courseById)
            }
            is Exam -> exam(after)
            else -> error("Unsupported academic aggregate.")
        }
    }

    private fun semester(value: Semester) {
        read(SemesterPut(null, value.toSemanticImage()))
        val year = graph.years.find { it.id == value.academicYearId }
        if (year == null) { issues += AcademicEditingIssue.MissingAcademicYear(value.academicYearId); return }
        read(AcademicYearPut(null, year.toSemanticImage()))
        val result = validateSemesterAgainstAcademicYear(year, value)
        if (result != SemesterAcademicYearValidationResult.VALID) issues += AcademicEditingIssue.SemesterMembership(value.id, result)
    }

    private fun courseById(id: CourseId) {
        val value = graph.courses.find { it.id == id }
        if (value == null) issues += AcademicEditingIssue.MissingCourse(id) else course(value)
    }

    private fun holidays(value: Semester): List<AcademicHoliday> = graph.holidays.filter { it.semesterId == value.id }.sortedBy { it.id.value }.also { list ->
        list.forEach {
            read(AcademicHolidayPut(null, it.toSemanticImage()))
            if (it.dates.startDate < value.startDate || it.dates.endDateExclusive > value.endDateExclusive) issues += AcademicEditingIssue.HolidayOutsideSemester(it.id)
        }
    }

    private fun course(value: Course) {
        if (!validatedCourses.add(value.id)) return
        read(CoursePut(null, value.toSemanticImage()))
        val term = graph.semesters.find { it.id == value.semesterId }
        if (term == null) { issues += AcademicEditingIssue.MissingSemester(value.semesterId); return }
        semester(term)
        val rules = graph.rules.filter { it.courseId == value.id }.sortedBy { it.id.value }
        rules.forEach { read(CourseScheduleRulePut(null, it.toSemanticImage())) }
        val templateIds = rules.mapNotNull { (it.time as? CourseTimeSpec.PeriodBased)?.periodTemplateId }.toSet()
        val templates = graph.templates.filter { it.id in templateIds }.sortedBy { it.id.value }
        templates.forEach { read(PeriodTemplatePut(null, it.toSemanticImage())) }
        val ruleIds = rules.map { it.id }.toSet()
        val exceptions = graph.exceptions.filter { it.occurrenceKey.scheduleRuleId in ruleIds }.sortedBy { it.id.value }
        exceptions.forEach { read(CourseOccurrenceExceptionPut(null, it.toSemanticImage())) }
        val result = resolveCourseSessions(term, value, rules, templates, holidays(term), exceptions)
        if (result is CourseSessionResolutionResult.Invalid) issues += AcademicEditingIssue.CourseResolution(value.id, result.issues)
    }

    private fun exam(value: Exam) {
        read(ExamPut(null, value.toSemanticImage()))
        val term = graph.semesters.find { it.id == value.semesterId }
        if (term == null) { issues += AcademicEditingIssue.MissingSemester(value.semesterId); return }
        semester(term)
        val course = value.courseId?.let { id -> graph.courses.find { it.id == id } }
        course?.let { read(CoursePut(null, it.toSemanticImage())) }
        val result = validateExamAgainstSemester(value, term, course)
        if (result != ExamValidationResult.VALID) issues += AcademicEditingIssue.ExamValidation(value.id, result)
    }
}
