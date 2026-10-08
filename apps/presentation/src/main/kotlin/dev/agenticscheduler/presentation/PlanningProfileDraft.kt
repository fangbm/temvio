package dev.agenticscheduler.presentation

import dev.agenticscheduler.domain.planning.*
import dev.agenticscheduler.domain.id.PlanningProfileId
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.time.Duration

/** Unchosen fields remain unchosen. Text preserves exact durations, including sub-minute values. */
data class AvailabilityRowDraft(val day: DayOfWeek? = null, val start: String = "", val endExclusive: String = "")
data class PlanningProfileDraft(
    val name: String,
    val configured: Boolean,
    val timeZone: String = "",
    val availability: ImmutableList<AvailabilityRowDraft> = emptyList<AvailabilityRowDraft>().toImmutableList(),
    val minimum: String = "",
    val preferred: String = "",
    val maximum: String = "",
    val allDayPolicy: AllDayEventPolicy? = null,
) {
    fun toProfile(id: PlanningProfileId): PlanningProfile {
        val configuration = if (!configured) PlanningProfileConfiguration.Unconfigured else {
            require(timeZone.isNotBlank()) { "Enter an explicit time zone ID." }
            val zone = try { TimeZone.of(timeZone.trim()) } catch (_: IllegalArgumentException) {
                throw IllegalArgumentException("Enter a valid time zone ID, such as Europe/Paris.")
            }
            val windows = availability.mapIndexed { index, row ->
                val day = requireNotNull(row.day) { "Choose a weekday for availability row ${index + 1}." }
                val start = try { LocalTime.parse(row.start.trim()) } catch (_: IllegalArgumentException) {
                    throw IllegalArgumentException("Enter a valid start time for availability row ${index + 1}.")
                }
                val end = try { LocalTime.parse(row.endExclusive.trim()) } catch (_: IllegalArgumentException) {
                    throw IllegalArgumentException("Enter a valid exclusive end time for availability row ${index + 1}.")
                }
                WeeklyAvailabilityWindow(day, start, end)
            }.toImmutableList()
            fun duration(value: String, label: String): Duration = try { Duration.parse(value.trim()) }
                catch (_: IllegalArgumentException) { throw IllegalArgumentException("Enter $label duration with an explicit unit, such as 25m or 1h.") }
            PlanningProfileConfiguration.Configured(zone, windows, duration(minimum, "minimum"),
                duration(preferred, "preferred"), duration(maximum, "maximum"),
                requireNotNull(allDayPolicy) { "Choose an all-day Event policy." })
        }
        return PlanningProfile(id, name, configuration)
    }

    companion object {
        fun from(profile: PlanningProfile): PlanningProfileDraft = when (val c = profile.configuration) {
            PlanningProfileConfiguration.Unconfigured -> PlanningProfileDraft(profile.name, false)
            is PlanningProfileConfiguration.Configured -> PlanningProfileDraft(profile.name, true, c.timeZone.id,
                c.weeklyAvailability.map { AvailabilityRowDraft(it.dayOfWeek, it.start.toString(), it.endExclusive.toString()) }.toImmutableList(),
                c.minimumFocusBlock.toString(), c.preferredFocusBlock.toString(), c.maximumFocusBlock.toString(), c.allDayEventPolicy)
        }
    }
}

/** Exact opening snapshot only changes on explicit Reload, never on refresh or Save preflight. */
data class PlanningProfileEditSession(val expectedBefore: PlanningProfile, val draft: PlanningProfileDraft = PlanningProfileDraft.from(expectedBefore)) {
    val dirty: Boolean get() = draft != PlanningProfileDraft.from(expectedBefore)
}
