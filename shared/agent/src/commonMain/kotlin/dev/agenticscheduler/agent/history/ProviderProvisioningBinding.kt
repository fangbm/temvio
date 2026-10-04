package dev.agenticscheduler.agent.history

import dev.agenticscheduler.sync.WearProviderBindingMetadataV1

/** Target owns identity; source values come only from the explicitly selected existing ProviderConfig. */
fun ProviderConfig.provisioningBinding(targetConfigId: ProviderConfigId, credentialRequired: Boolean): WearProviderBindingMetadataV1 =
    WearProviderBindingMetadataV1(providerConfigId = targetConfigId.value, baseUrl = baseUrl, model = model,
        maxContextUnits = maxContextUnits, reservedOutputUnits = reservedOutputUnits, streamingSupported = streamingSupported,
        toolCallingSupported = toolCallingSupported, credentialRequired = credentialRequired)
