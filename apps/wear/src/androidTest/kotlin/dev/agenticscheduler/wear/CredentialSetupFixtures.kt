package dev.agenticscheduler.wear

import android.content.Context
import android.content.ContextWrapper
import dev.agenticscheduler.agent.history.*
import dev.agenticscheduler.application.sync.*
import dev.agenticscheduler.database.*
import dev.agenticscheduler.database.repository.*
import dev.agenticscheduler.sync.*
import dev.agenticscheduler.wear.capability.*
import kotlinx.coroutines.flow.MutableStateFlow

/** Synthetic display handles only; production never constructs an approval handle. */
internal object CredentialSetupFixtures {
    val config = ProviderConfigId("01900000-0000-7000-8000-000000000801")
    val target = DeviceId("Synthetic-Watch-12AB34")
    val source = DeviceId("Synthetic-Sender-56CD78")
    val binding = WearProviderBindingMetadataV1(providerConfigId=config.value,baseUrl="https://synthetic.invalid/v1",model="Synthetic credential profile",
        maxContextUnits=8192,reservedOutputUnits=2048,streamingSupported=false,toolCallingSupported=true,credentialRequired=true)
    val flow = ProviderTargetFlow(target,config.value,1,source)
    fun comparison(revision:Long=1):ProviderTargetComparison = ProviderTargetComparison::class.java
        .getDeclaredConstructor(ProviderTargetFlow::class.java,String::class.java,String::class.java)
        .newInstance(flow.copy(revision=revision),binding.model,"12345678")
    fun ready()=ProviderTargetState.Ready(config.value,binding.model,listOf(source,target))
    class Port(initial:ProviderTargetState):ProviderCredentialTarget {
        override val state=MutableStateFlow(initial)
        val calls=mutableListOf<String>();var chosen:DeviceId?=null;var approved:Pair<ProviderTargetComparison,String>?=null
        private var compared:ProviderTargetComparison?=null
        override suspend fun resume(config:String){calls+="resume"}
        override suspend fun reserveAndPublish(binding:WearProviderBindingMetadataV1,provisioner:DeviceId){calls+="request";chosen=provisioner;state.value=ProviderTargetState.Reserved(flow,ProviderTargetPublication.UNCONFIRMED,false)}
        override suspend fun fetchDelivery(){calls+="fetch";compared=comparison();state.value=ProviderTargetState.ComparisonReady(compared!!)}
        override suspend fun approveComparison(displayed:ProviderTargetComparison,independentlyComparedCode:String){calls+="approve";compared=displayed;approved=displayed to independentlyComparedCode
            state.value=if(independentlyComparedCode==displayed.code)ProviderTargetState.UserApproved(flow) else ProviderTargetState.Blocked(ProviderTargetWork.APPROVE,flow,ProviderTargetFailure.Protocol(ProviderCredentialRejection.PROVISIONING_APPROVAL_REQUIRED))}
        override suspend fun installApproved(){calls+="install";state.value=ProviderTargetState.Installed(flow,ProviderTargetAcknowledgement(ProviderTargetAckStatus.PENDING))}
        override suspend fun retry(){calls+="retry";state.value=when(state.value){is ProviderTargetState.Reserved->ProviderTargetState.Reserved(flow,ProviderTargetPublication.PUBLISHED,false)
            is ProviderTargetState.Installed->ProviderTargetState.Installed(flow,ProviderTargetAcknowledgement(ProviderTargetAckStatus.CONFIRMED))
            else->ProviderTargetState.ComparisonReady(requireNotNull(compared))}}
        override suspend fun recover(){calls+="recover";state.value=ready()}
    }
}

/** Existing real coordinator/service/Room/Tink/Android Keystore; only network ports are fake. */
internal class RealWearCredentialFixture(base:Context, namespace:String) {
    val context=object:ContextWrapper(base){
        override fun getApplicationContext():Context=this
        override fun getDatabasePath(name:String)=base.getDatabasePath("d1005_${namespace}_$name")
        override fun getSharedPreferences(name:String,mode:Int)=base.getSharedPreferences("d1005_${namespace}_$name",mode)
    }
    private val dbName=context.getDatabasePath(AgenticSchedulerDatabaseFileName).name
    private val baseContext=base
    lateinit var db:AgenticSchedulerDatabase
    lateinit var local:RoomAgentStateRepository
    lateinit var repo:RoomProviderCredentialProvisioningRepository
    lateinit var enrollment:LocalEnrollmentState.Active
    var store=AndroidKeystoreSecureStore(context)
    val hpke=TinkPairingHpke()
    val binding=CredentialSetupFixtures.binding
    val target=CredentialSetupFixtures.target
    val source=CredentialSetupFixtures.source
    val config=CredentialSetupFixtures.config
    val secret="D1005_PUBLIC_SYNTHETIC_CREDENTIAL_CANARY"
    var imports=0;var acks=0;var publishes=0;var fetches=0
    var ackFails=false
    lateinit var envelope:ProviderCredentialEnvelopeV1
    lateinit var request:ProviderCredentialReservationRequestV1
    private val mailbox=object:ProviderCredentialTransport {
        override suspend fun publish(value:ProviderCredentialReservationRequestV1){publishes++;request=value
            envelope=hpke.encryptProviderCredential(enrollment.hpkePublicKey,ProviderCredentialPlaintextV1(targetDeviceId=target,providerConfigId=config.value,
                credentialRevision=value.credentialRevision,credentialSecretBase64Url=encodeCanonicalBase64Url(secret.encodeToByteArray())))}
        override suspend fun fetch(config:String):ProviderCredentialMailboxDeliveryV1{fetches++;return ProviderCredentialMailboxDeliveryV1(request,ProviderCredentialMailboxState.DELIVERED,1_800_604_800,envelope)}
        override suspend fun acknowledge(ack:ProviderCredentialAcknowledgementV1){acks++;if(ackFails)throw ProviderCredentialTransportException(503,"PRIVATE_BODY_NOT_FOR_UI")}
        override suspend fun assignedRequest():ProviderCredentialMailboxDeliveryV1?=error("Not source UI")
        override suspend fun uploadExact(canonicalEnvelope:ByteArray):Unit=error("Not source UI")
    }
    suspend fun initialize() {
        baseContext.deleteDatabase(dbName);open()
        val key=store.generatePairingDeviceKey();val credential=store.store(DeviceCredential(encodeCanonicalBase64Url(ByteArray(32){9})))
        enrollment=LocalEnrollmentState.Active(AccountId("synthetic-credential-account"),target,EnrollmentRequestId("synthetic-enrollment"),key.publicKey,
            key.privateKeyReference,SyncSpaceId("synthetic-space"),SecretReference("synthetic://unused-amk"),credential)
        RoomLocalEnrollmentRepository(db).saveActive(enrollment)
        local.saveProviderConfig(ProviderConfig(config,binding.baseUrl,binding.model,binding.maxContextUnits,binding.reservedOutputUnits,false,true,null))
        local.selectProviderConfig(config)
    }
    private fun open(){db=openAndroidDatabase(context);local=RoomAgentStateRepository(db);repo=RoomProviderCredentialProvisioningRepository(db)}
    fun reopen(){db.close();store=AndroidKeystoreSecureStore(context);open()}
    fun coordinator():ProviderCredentialTargetCoordinator {
        val native=store
        val counted=object:PlatformProviderCredentialStore by native {
            override suspend fun importPreparedProviderSecret(slot:PreparedProviderSecretSlot,credential:ByteArray){imports++;native.importPreparedProviderSecret(slot,credential)}
        }
        val directory=object:RevocationRotationTransport{
            override suspend fun activeDevices()=listOf(ClientActiveDeviceDirectoryEntry(source.value,enrollment.hpkePublicKey.value),ClientActiveDeviceDirectoryEntry(target.value,enrollment.hpkePublicKey.value))
            override suspend fun revokeDeviceAndRotate(deviceId:String,request:ClientAtomicRevocationRequest)=error("Not target setup")
        }
        return ProviderCredentialTargetCoordinator(enrollment.accountId,RoomLocalEnrollmentRepository(db),native,native,counted,hpke,repo,mailbox,directory,
            {id->local.providerConfig(ProviderConfigId(id))?.provisioningBinding(ProviderConfigId(id),true)},{1_800_000_000})
    }
    suspend fun providerObservation():WearBindingObservation = WearProviderBindingSource({local.providerConfig(it)},repo,store,target,{true},{false})
        .observe(WearProviderBinding(binding,WearEndpointRoute.PUBLIC_HTTPS))
    suspend fun close(){
        if(::enrollment.isInitialized){
            ProviderCredentialProvisioningService(repo,store,hpke,target,requireNotNull(store.privateKey(enrollment.hpkePrivateKeyReference))).disableAndWipe(config.value)
            store.delete(enrollment.hpkePrivateKeyReference);store.delete(enrollment.deviceCredentialReference)
        }
        db.close();baseContext.deleteDatabase(dbName)
    }
}
