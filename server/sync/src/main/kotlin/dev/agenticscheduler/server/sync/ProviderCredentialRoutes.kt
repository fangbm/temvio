package dev.agenticscheduler.server.sync

import dev.agenticscheduler.sync.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray

internal fun Route.providerCredentialRoutes(repository: OpaqueSyncRepository) {
    val mailbox = repository as? ServerProviderCredentialMailbox ?: return
    route("/v1/provider-credentials") {
        post("/requests") {
            val actor = call.credentialActor(repository) ?: return@post
            val request = ProviderCredentialMailboxWireCodec.decodeRequest(call.credentialBody())
                ?: return@post call.respond(HttpStatusCode.BadRequest, ServerErrorResponse("INVALID_CREDENTIAL_REQUEST"))
            call.mailboxAction { mailbox.publishCredentialRequest(actor, request); call.respond(HttpStatusCode.NoContent) }
        }
        get("/requests/assigned") {
            val actor = call.credentialActor(repository) ?: return@get
            call.mailboxAction {
                val request = mailbox.assignedCredentialRequest(actor)
                if (request == null) call.respond(HttpStatusCode.NoContent)
                else call.respondText(ProviderCredentialMailboxWireCodec.encodeDelivery(request).decodeToString(), ContentType.Application.Json)
            }
        }
        post("/envelopes") {
            val actor = call.credentialActor(repository) ?: return@post
            val bytes = call.credentialBody()
            call.mailboxAction { mailbox.uploadCredentialEnvelope(actor, bytes); call.respond(HttpStatusCode.NoContent) }
        }
        get("/mailbox/{configId}") {
            val actor = call.credentialActor(repository) ?: return@get
            val config = call.parameters["configId"] ?: return@get call.respond(HttpStatusCode.BadRequest, ServerErrorResponse("INVALID_PROVIDER_CONFIG"))
            try { MutationId(config) } catch (_: IllegalArgumentException) { return@get call.respond(HttpStatusCode.BadRequest, ServerErrorResponse("INVALID_PROVIDER_CONFIG")) }
            call.mailboxAction {
                val delivery = mailbox.fetchCredentialMailbox(actor, config)
                if (delivery == null) call.respond(HttpStatusCode.NoContent)
                else call.respondText(ProviderCredentialMailboxWireCodec.encodeDelivery(delivery).decodeToString(), ContentType.Application.Json)
            }
        }
        post("/acknowledgements") {
            val actor = call.credentialActor(repository) ?: return@post
            val decoded = ProviderCredentialWireCodec.decodeAcknowledgement(call.credentialBody())
            if (decoded !is ProviderCredentialDecodeResult.Accepted) return@post call.respond(HttpStatusCode.BadRequest, ServerErrorResponse("INVALID_CREDENTIAL_ACK"))
            call.mailboxAction { mailbox.acknowledgeCredential(actor, decoded.value); call.respond(HttpStatusCode.NoContent) }
        }
    }
}
private suspend fun ApplicationCall.credentialActor(repository: OpaqueSyncRepository): AuthenticatedDevice? {
    val bearer = request.header(HttpHeaders.Authorization)?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")
    val actor = bearer?.let(repository::authenticate)
    if (actor == null) respond(HttpStatusCode.Unauthorized, ServerErrorResponse("UNAUTHORIZED"))
    return actor
}
private suspend fun ApplicationCall.credentialBody(): ByteArray {
    val cap = ProviderCredentialWireCodec.MAX_HTTP_BODY_BYTES.toLong()
    if ((request.header(HttpHeaders.ContentLength)?.toLongOrNull() ?: 0) > cap) throw RequestTooLarge()
    return receiveChannel().readRemaining(cap + 1).readByteArray().also { if (it.size > cap) throw RequestTooLarge() }
}
private suspend fun ApplicationCall.mailboxAction(action: suspend () -> Unit) {
    try { action() } catch (e: CredentialMailboxException) {
        val status = when (e.reason) {
            CredentialMailboxFailure.UNAUTHORIZED, CredentialMailboxFailure.NOT_ASSIGNED -> HttpStatusCode.Forbidden
            CredentialMailboxFailure.DELIVERY_CONFLICT, CredentialMailboxFailure.UNRESERVED_REVISION -> HttpStatusCode.Conflict
            CredentialMailboxFailure.DELIVERY_EXPIRED -> HttpStatusCode.Gone
            CredentialMailboxFailure.INVALID_CREDENTIAL_ENVELOPE -> HttpStatusCode.BadRequest
        }
        respond(status, ServerErrorResponse(e.reason.name))
    }
}
