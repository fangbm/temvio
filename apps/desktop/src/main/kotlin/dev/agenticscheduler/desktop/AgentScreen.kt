package dev.agenticscheduler.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.agenticscheduler.agent.history.*
import dev.agenticscheduler.application.sync.PlatformSecretStore
import dev.agenticscheduler.application.id.UuidV7Generator
import dev.agenticscheduler.ui.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Separate local settings route; selecting/configuring a Provider never creates a turn. */
@Composable
internal fun DesktopProviderPanel(state:AgentStateRepository,secrets:PlatformSecretStore,ids:UuidV7Generator,
    scope:CoroutineScope) {
    var providers by remember {mutableStateOf(emptyList<ProviderConfig>())}
    var selected by remember {mutableStateOf<ProviderConfigId?>(null)}
    var editing by remember {mutableStateOf<ProviderConfig?>(null)}
    var editorOpen by remember {mutableStateOf(false)}
    suspend fun load() {providers=state.providerConfigs();selected=state.selectedProviderConfigId()}
    LaunchedEffect(state) {load()}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp).testTag("provider-settings"),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        SectionHeading("Provider settings", "Device-local config; credentials stay behind the platform secure store")
        providers.forEach {config ->
            PresentationCard {
                Text(config.model)
                Text(if(config.id==selected) "Selected Provider" else "Available local configuration")
                ActionButton({scope.launch {state.selectProviderConfig(config.id);load()}},enabled=config.id!=selected,role=ActionRole.SECONDARY) {Text("Use ${config.model}")}
                ActionButton({editing=config;editorOpen=true},role=ActionRole.TERTIARY) {Text("Edit ${config.model}")}
            }
        }
        ActionButton({editing=null;editorOpen=true},role=ActionRole.SECONDARY) {Text("Add Provider")}
        Text("Switching Provider/model preserves application-owned conversation history and never Sends.")
    }
    if(editorOpen) ProviderConfigurationDialog(editing,state,secrets,ids,{editorOpen=false}, {scope.launch {load();editorOpen=false}})
}
