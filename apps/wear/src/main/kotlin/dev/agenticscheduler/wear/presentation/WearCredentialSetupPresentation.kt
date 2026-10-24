package dev.agenticscheduler.wear.presentation

import dev.agenticscheduler.application.sync.*
import dev.agenticscheduler.sync.DeviceId
import dev.agenticscheduler.sync.WearProviderBindingMetadataV1
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

enum class CredentialSetupAction { REQUEST, FETCH, APPROVE, INSTALL, RETRY, RECOVER }
data class CredentialSetupCopy(val title: String, val detail: String, val actions: Set<CredentialSetupAction>)

fun sendingDeviceLabel(id: DeviceId) = "Sending device · …${id.value.takeLast(6)}"

fun credentialFailureCopy(failure: ProviderTargetFailure): String = when (failure) {
    is ProviderTargetFailure.Eligibility -> eligibilityCopy(failure.reason)
    is ProviderTargetFailure.Protocol -> when (failure.reason) {
        ProviderCredentialRejection.PROVISIONING_APPROVAL_REQUIRED -> "Comparison was not approved. Review the codes and enter the sending device's eight digits."
        ProviderCredentialRejection.CREDENTIAL_INTEGRITY_CONFLICT -> "Credential integrity conflict. The accepted credential has not been replaced."
        ProviderCredentialRejection.CREDENTIAL_UNAVAILABLE -> "The installed credential is unavailable. Review setup before requesting a replacement."
        ProviderCredentialRejection.SECURE_STORE_UNAVAILABLE -> "Secure credential storage is unavailable."
        ProviderCredentialRejection.CREDENTIAL_STATE_LOST -> "Saved credential setup state is unavailable. Restore the Watch identity before continuing."
        ProviderCredentialRejection.WRONG_PROVISIONER -> "Delivery came from a different sending device."
        ProviderCredentialRejection.UNRESERVED_REVISION -> "Delivery does not match the current credential request."
        ProviderCredentialRejection.PROVIDER_BINDING_MISMATCH -> "The Provider configuration changed. Review the selected Provider."
        ProviderCredentialRejection.ROLLBACK_REJECTED -> "This request was removed or replaced and cannot be used."
        ProviderCredentialRejection.REVISION_OVERFLOW -> "This Watch cannot create another credential request."
        ProviderCredentialRejection.CREDENTIAL_AUTHENTICATION_FAILED -> "Credential delivery could not be authenticated."
        ProviderCredentialRejection.INVALID_CREDENTIAL_ENVELOPE -> "Credential delivery is invalid."
        ProviderCredentialRejection.TARGET_MISMATCH -> "Credential delivery belongs to a different Watch."
    }
    is ProviderTargetFailure.Transport -> when (failure.reason) {
        ProviderTargetTransportFailure.AUTHORIZATION -> "Sync authorization is unavailable. Check this Watch's account."
        ProviderTargetTransportFailure.DELIVERY_CONFLICT -> "The delivery conflicts with this credential request."
        ProviderTargetTransportFailure.INVALID_RESPONSE -> "The credential service returned an invalid response."
        ProviderTargetTransportFailure.NETWORK -> "The credential service cannot be reached."
        ProviderTargetTransportFailure.SERVER -> "The credential service is temporarily unavailable."
        ProviderTargetTransportFailure.REJECTED -> "The credential service rejected this action."
    }
    ProviderTargetFailure.Internal -> "Credential setup could not continue. No success is assumed."
}

private fun eligibilityCopy(reason: ProviderTargetEligibility): String = when (reason) {
    ProviderTargetEligibility.NO_ACTIVE_TARGET -> "A synced Watch account is required."
    ProviderTargetEligibility.MULTIPLE_ACTIVE_TARGETS -> "More than one Watch identity is active. Review sync setup."
    ProviderTargetEligibility.ACCOUNT_MISMATCH -> "The configured account does not match this Watch."
    ProviderTargetEligibility.TARGET_CHANGED -> "Watch identity changed. Close setup and reopen the app."
    ProviderTargetEligibility.MISSING_DEVICE_CREDENTIAL -> "Watch sync credential is unavailable."
    ProviderTargetEligibility.MISSING_TARGET_PRIVATE_KEY -> "Watch encryption identity is unavailable."
    ProviderTargetEligibility.INVALID_DIRECTORY -> "The active device list could not be verified."
    ProviderTargetEligibility.TARGET_NOT_ACTIVE -> "This Watch is no longer active in the configured account."
    ProviderTargetEligibility.PROVISIONER_NOT_ACTIVE -> "The selected sending device is no longer active."
    ProviderTargetEligibility.PROVIDER_CONFIG_UNAVAILABLE -> "This Provider configuration is unavailable."
    ProviderTargetEligibility.NO_CONFIG_SELECTED -> "Select a Provider before opening credential setup."
}

fun credentialSetupCopy(state: ProviderTargetState): CredentialSetupCopy {
    fun copy(title: String, detail: String, vararg actions: CredentialSetupAction) = CredentialSetupCopy(title, detail, actions.toSet())
    return when (state) {
        ProviderTargetState.NotStarted -> copy("Opening credential setup", "No credential has been requested.")
        is ProviderTargetState.NotEligible -> copy("Credential setup unavailable", eligibilityCopy(state.reason))
        is ProviderTargetState.Ready -> copy("Credential setup required", "Choose the exact sending device, then request a credential.", CredentialSetupAction.REQUEST)
        is ProviderTargetState.Reserved -> when {
            state.comparisonRefreshRequired -> copy("Reload comparison", "The request is saved. Reload its comparison before approval.", CredentialSetupAction.FETCH)
            state.publication == ProviderTargetPublication.UNCONFIRMED -> copy("Request saved on this Watch", "Publication is not confirmed. Retry the same request.", CredentialSetupAction.RETRY)
            else -> copy("Credential requested", "Continue on the selected sending device, then check for delivery.", CredentialSetupAction.FETCH)
        }
        is ProviderTargetState.ComparisonReady -> copy("Compare both devices", "Independently compare these digits with the sending device. Enter its eight digits below.", CredentialSetupAction.APPROVE)
        is ProviderTargetState.UserApproved -> copy("Comparison approved", "Credential is not installed yet.", CredentialSetupAction.INSTALL)
        is ProviderTargetState.Installing -> copy("Installing credential", "Wait for the local installation result.")
        is ProviderTargetState.Installed -> copy("Credential installed", when (state.acknowledgement.status) {
            ProviderTargetAckStatus.CONFIRMED -> "Delivery acknowledged."
            ProviderTargetAckStatus.PENDING -> "Acknowledgement pending. The local installation remains committed."
            ProviderTargetAckStatus.EXPIRED -> "Acknowledgement expired. The local installation remains committed."
            ProviderTargetAckStatus.BLOCKED -> "Acknowledgement needs attention. The local installation remains committed."
        }, *if (state.acknowledgement.status in setOf(ProviderTargetAckStatus.PENDING, ProviderTargetAckStatus.BLOCKED)) arrayOf(CredentialSetupAction.RETRY) else emptyArray())
        is ProviderTargetState.Expired -> copy("Credential request expired", "Request again explicitly from ${sendingDeviceLabel(state.flow.provisioner)}.", CredentialSetupAction.REQUEST)
        is ProviderTargetState.RetryableFailure -> copy("${when(state.work) {
            ProviderTargetWork.PUBLISH -> "Request publication"
            ProviderTargetWork.FETCH -> "Delivery check"
            ProviderTargetWork.ACK -> "Acknowledgement"
            else -> "Setup refresh"
        }} needs retry", credentialFailureCopy(state.failure), CredentialSetupAction.RETRY)
        is ProviderTargetState.Blocked -> copy("Credential setup needs attention", credentialFailureCopy(state.failure), CredentialSetupAction.RETRY)
        is ProviderTargetState.RecoveryRequired -> copy("Setup needs recovery", "Resume saved setup. This does not approve or install a credential.", CredentialSetupAction.RECOVER)
    }
}

data class WearCredentialSetupView(val state: ProviderTargetState, val providerLabel: String,
    val selectedSource: DeviceId? = null, val enteredCode: String = "", val busy: Boolean = false) {
    val copy get() = credentialSetupCopy(state)
    val canApprove get() = !busy && state is ProviderTargetState.ComparisonReady && Regex("[0-9]{8}").matches(enteredCode)
    override fun toString() = "WearCredentialSetupView(state=$state, enteredCode=<redacted>, busy=$busy)"
}

/** UI drafts and explicit delegation only. The accepted target owns every protocol transition. */
class WearCredentialSetupPresentation(private val target: ProviderCredentialTarget,
    private val binding: WearProviderBindingMetadataV1, private val scope: CoroutineScope,
    private val onInstalled: suspend () -> Unit = {},
) : AutoCloseable {
    private val mutable = MutableStateFlow(WearCredentialSetupView(target.state.value, binding.model))
    val view = mutable.asStateFlow()
    private var work: Job? = null
    private var closed = false
    private val observer = scope.launch { target.state.collect { state ->
        val previous = mutable.value
        val old = (previous.state as? ProviderTargetState.ComparisonReady)?.comparison
        val next = (state as? ProviderTargetState.ComparisonReady)?.comparison
        mutable.value = previous.copy(state = state, enteredCode = if (old != null && old === next) previous.enteredCode else "",
            selectedSource = previous.selectedSource?.takeUnless { state is ProviderTargetState.Ready && it !in state.provisioners })
    } }
    fun open() = delegate { target.resume(binding.providerConfigId) }
    fun chooseSource(value: DeviceId) {
        val state = target.state.value as? ProviderTargetState.Ready ?: return
        if (!mutable.value.busy && value in state.provisioners) mutable.value = mutable.value.copy(selectedSource = value, enteredCode = "")
    }
    fun enterCode(value: String) {
        if (!mutable.value.busy && target.state.value is ProviderTargetState.ComparisonReady) mutable.value = mutable.value.copy(enteredCode = value)
    }
    fun act(action: CredentialSetupAction) {
        val state = target.state.value
        if (action !in credentialSetupCopy(state).actions || mutable.value.busy) return
        val entered = mutable.value.enteredCode
        if (action == CredentialSetupAction.APPROVE && !Regex("[0-9]{8}").matches(entered)) return
        if (action == CredentialSetupAction.APPROVE &&
            (mutable.value.state as? ProviderTargetState.ComparisonReady)?.comparison !== (state as ProviderTargetState.ComparisonReady).comparison) {
            mutable.value = mutable.value.copy(enteredCode = ""); return
        }
        val source = mutable.value.selectedSource ?: (state as? ProviderTargetState.Expired)?.flow?.provisioner
        if (action == CredentialSetupAction.REQUEST && source == null) return
        if (action == CredentialSetupAction.REQUEST && state is ProviderTargetState.Ready && source !in state.provisioners) return
        delegate {
            when (action) {
                CredentialSetupAction.REQUEST -> target.reserveAndPublish(binding, requireNotNull(source))
                CredentialSetupAction.FETCH -> target.fetchDelivery()
                CredentialSetupAction.APPROVE -> target.approveComparison((state as ProviderTargetState.ComparisonReady).comparison, entered)
                CredentialSetupAction.INSTALL -> target.installApproved()
                CredentialSetupAction.RETRY -> target.retry()
                CredentialSetupAction.RECOVER -> target.recover()
            }
        }
    }
    private fun delegate(block: suspend () -> Unit) {
        if (closed || work?.isActive == true) return
        mutable.value = mutable.value.copy(busy = true)
        work = scope.launch {
            try { block(); if (target.state.value is ProviderTargetState.Installed) onInstalled() }
            finally { mutable.value = mutable.value.copy(busy = false) }
        }
    }
    override fun close() { closed = true; work?.cancel(); observer.cancel(); mutable.value = mutable.value.copy(enteredCode = "") }
}
