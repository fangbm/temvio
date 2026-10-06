package dev.agenticscheduler.desktop

import androidx.compose.runtime.mutableStateOf
import dev.agenticscheduler.application.planner.PlannerPreview
import dev.agenticscheduler.domain.id.PlanningProfileId
import dev.agenticscheduler.domain.planning.PlanningProfile

/** Device-session drafts and display facts only; services retain all semantic authority. */
internal class DesktopPlannerScreenCoordinator {
    var scope: kotlinx.coroutines.CoroutineScope? = null
    val selectedProfileId = mutableStateOf<PlanningProfileId?>(null)
    val createProfile = mutableStateOf(false)
    val editingProfile = mutableStateOf<PlanningProfile?>(null)
    val horizonStart = mutableStateOf("")
    val horizonEnd = mutableStateOf("")
    val affectedId = mutableStateOf("")
    val preview = mutableStateOf<PlannerPreview?>(null)
    val message = mutableStateOf<String?>(null)
}
