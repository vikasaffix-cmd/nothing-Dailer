package com.example.allinonedailer.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.OutcomeReceiver
import android.os.PowerManager
import android.telecom.VideoProfile
import android.view.Surface
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.CallEndpoint
import android.telecom.CallEndpointException
import android.telecom.InCallService
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import com.example.allinonedailer.R
import com.example.allinonedailer.data.CallerIdRepository
import com.example.allinonedailer.data.Haptics
import com.example.allinonedailer.data.QuietHoursGate
import com.example.allinonedailer.data.CallPlacer
import com.example.allinonedailer.data.RecordingRepository
import com.example.allinonedailer.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class ActiveCallData(
    val phoneNumber: String,
    val callerName: String?,
    val isSpam: Boolean,
    val spamCategory: String?,
    val state: Int, // Call.STATE_...
    val isMuted: Boolean = false,
    val isSpeakerOn: Boolean = false,
    val isRecording: Boolean = false,
    val inContacts: Boolean = false,
    val lookupPending: Boolean = false,
    val photoUri: String? = null,
    /** SIM / phone-account label, e.g. "Jio" or "SIM 1". */
    val accountLabel: String? = null,
    /** Offline carrier / region for non-contacts, e.g. "Airtel · Delhi". */
    val numberLabel: String? = null,
    /** The network offers two-way video on this call. */
    val videoCapable: Boolean = false,
    /** The call is currently a video call. */
    val isVideo: Boolean = false,
    /** The other side asked to switch this call to video. */
    val videoUpgradeRequested: Boolean = false,
    /** [callerName] came from the network (CNAP), not from your contacts. */
    val nameFromNetwork: Boolean = false,
    /** SIMs to pick from while the call is in [Call.STATE_SELECT_PHONE_ACCOUNT]. */
    val simChoices: List<SimOption> = emptyList(),
    /** Tells calls apart while two or more are up at once. */
    val callId: String = "",
    /** When the call connected (wall clock, ms), or 0 if it hasn't yet. */
    val connectTimeMillis: Long = 0,
    /** The network lets this call go on hold. */
    val canHold: Boolean = false,
    /** This call can be merged with the other one into a conference. */
    val canMerge: Boolean = false,
    val isConference: Boolean = false,
    /** For a conference: the people in it, as far as the network reports them. */
    val participants: List<ActiveCallData> = emptyList(),
    /** For a conference member: you can hang up on just this person. */
    val canEndFromConference: Boolean = false,
    /** For a conference member: you can take this person aside into a separate call. */
    val canSplit: Boolean = false,
    /** Where call audio can go right now; the active one has [AudioRoute.isActive]. */
    val audioRoutes: List<AudioRoute> = emptyList()
)

/** Kinds of audio output, in the order the route picker lists them. */
enum class AudioRouteType { EARPIECE, WIRED_HEADSET, SPEAKER, BLUETOOTH }

/** One place call audio can go, e.g. "Phone", "Speaker" or "Galaxy Buds2". */
data class AudioRoute(
    val id: String,
    val type: AudioRouteType,
    val name: String,
    val isActive: Boolean
)

/** A call-capable SIM, e.g. label "Jio" with detail "SIM 1". */
data class SimOption(
    val handle: PhoneAccountHandle,
    val label: String,
    val detail: String?
)

/** Call-capable SIMs, in slot order. Empty without READ_PHONE_STATE. */
fun callCapableSims(context: Context, only: List<PhoneAccountHandle>? = null): List<SimOption> = try {
    val telecom = context.getSystemService(TelecomManager::class.java)
    val handles = only?.takeIf { it.isNotEmpty() } ?: telecom.callCapablePhoneAccounts
    handles.mapIndexed { i, handle ->
        val account = telecom.getPhoneAccount(handle)
        SimOption(
            handle = handle,
            label = account?.label?.toString()?.takeIf { it.isNotBlank() } ?: context.getString(R.string.sim_slot, i + 1),
            detail = context.getString(R.string.sim_slot, i + 1)
        )
    }
} catch (e: SecurityException) {
    emptyList()
}

class CallService : InCallService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    companion object {
        private const val TAG = "CallService"
        private const val INCOMING_CHANNEL_ID = "incoming_calls"
        private const val INCOMING_NOTIFICATION_ID = 2001
        /** Low importance: an incoming call whose pill was swiped away waits here without a heads-up. */
        private const val INCOMING_QUIET_CHANNEL_ID = "incoming_calls_quiet"

        /** Live reference to the bound service so UI actions can reach the Telecom APIs. */
        private var instance: CallService? = null

        /**
         * Every call Telecom has handed us, in the order they arrived, with its
         * UI data. Telecom calls back on the main thread, so no locking.
         */
        private val callMap = LinkedHashMap<Call, ActiveCallData>()
        private var nextCallId = 0
        private var audioMuted = false
        private var audioSpeaker = false
        private var audioRoutes: List<AudioRoute> = emptyList()
        /** Route id → what to hand Telecom: a CallEndpoint (API 34+), a BluetoothDevice, or a route int. */
        private var routeTargets: Map<String, Any> = emptyMap()
        private var recordingOn = false

        /** The call the screen is about: the connected one, else dialing, ringing, held. */
        private val _currentCall = MutableStateFlow<Call?>(null)
        val currentCall: StateFlow<Call?> = _currentCall.asStateFlow()

        /** Data for [currentCall]; null only when no call is left. */
        private val _callData = MutableStateFlow<ActiveCallData?>(null)
        val callData: StateFlow<ActiveCallData?> = _callData.asStateFlow()

        /** Every top-level call (conference members are folded into their conference). */
        private val _allCalls = MutableStateFlow<List<ActiveCallData>>(emptyList())
        val allCalls: StateFlow<List<ActiveCallData>> = _allCalls.asStateFlow()

        private fun updateCall(call: Call?, transform: (ActiveCallData) -> ActiveCallData) {
            val current = callMap[call ?: return] ?: return
            callMap[call] = transform(current)
            publish()
        }

        /** Every call with its live Telecom state, for [CallStateLogic]. */
        private fun slots() = callMap.keys.map { CallSlot(it, it.state, topLevel = it.parent == null) }

        /** True while the dial pad is open over a call to add another one. */
        private val _addingCall = MutableStateFlow(false)
        val addingCall: StateFlow<Boolean> = _addingCall.asStateFlow()

        /** Telecom's word on whether another call can be placed now (InCallService.canAddCall). */
        private val _canAddCall = MutableStateFlow(false)
        val canAddCall: StateFlow<Boolean> = _canAddCall.asStateFlow()

        /** Add call: hold the current call and open the dial pad over the call screen. */
        fun startAddCall() {
            _currentCall.value?.takeIf { it.state == Call.STATE_ACTIVE }?.hold()
            _addingCall.value = true
        }

        /** Back to call from the add-call dial pad, resuming the call that was put on hold for it. */
        fun cancelAddCall() {
            _addingCall.value = false
            CallStateLogic.callToResumeAfterAddCall(slots())?.unhold()
        }

        private fun callById(id: String): Call? = callMap.entries.firstOrNull { it.value.callId == id }?.key

        /** Conference: hang up on one person, leaving the others connected. */
        fun endParticipant(callId: String) {
            callById(callId)?.disconnect()
        }

        /** Conference: take one person into a private call; the rest go on hold. */
        fun splitParticipant(callId: String) {
            callById(callId)?.splitFromConference()
        }

        /** Recomputes the call list and which call is in the foreground. */
        private fun publish() {
            val top = callMap.entries
                .filter { (call, data) -> call.parent == null && data.state != Call.STATE_DISCONNECTED }
                .map { (call, data) ->
                    val details = call.details
                    val conference = details.hasProperty(Call.Details.PROPERTY_CONFERENCE)
                    val manageable = details.can(Call.Details.CAPABILITY_MANAGE_CONFERENCE)
                    val participants = if (!conference) emptyList() else call.children.mapNotNull { child ->
                        val controls = CallStateLogic.conferenceControls(
                            manageable,
                            canDisconnect = child.details.can(Call.Details.CAPABILITY_DISCONNECT_FROM_CONFERENCE),
                            canSeparate = child.details.can(Call.Details.CAPABILITY_SEPARATE_FROM_CONFERENCE)
                        )
                        callMap[child]?.takeIf { it.state != Call.STATE_DISCONNECTED }?.copy(
                            connectTimeMillis = child.details.connectTimeMillis,
                            canEndFromConference = controls.canEnd,
                            canSplit = controls.canSplit
                        )
                    }
                    call to data.copy(
                        participants = participants,
                        isMuted = audioMuted,
                        isSpeakerOn = audioSpeaker,
                        isRecording = recordingOn,
                        connectTimeMillis = details.connectTimeMillis,
                        canHold = details.can(Call.Details.CAPABILITY_HOLD),
                        canMerge = CallStateLogic.canMerge(
                            call.conferenceableCalls.isNotEmpty(),
                            details.can(Call.Details.CAPABILITY_MERGE_CONFERENCE)
                        ),
                        isConference = conference,
                        audioRoutes = audioRoutes,
                        callerName = if (conference) instance?.getString(R.string.conference) ?: "Conference" else data.callerName
                    )
                }
            val foreground = CallStateLogic.foreground(top.map { CallSlot(it, it.second.state) })
            _currentCall.value = foreground?.first
            _allCalls.value = top.map { it.second }
            _callData.value = foreground?.second
            // Status-bar call chip: the ongoing CallStyle notification (phoneCall foreground service).
            instance?.let { OngoingCallNotification.update(it, foreground?.second, recordingOn) }
            instance?.let { GlyphController.update(it, _allCalls.value) }
        }

        private fun ringingCall(): Call? = CallStateLogic.ringing(slots())

        /** Answers the ringing call. If another call is connected, Telecom puts it on hold first. */
        fun answerCurrentCall() {
            val call = ringingCall() ?: _currentCall.value ?: return
            // Accept with the offered video state, so incoming video calls answer as video.
            call.answer(call.details.videoState)
        }

        /**
         * Call waiting: hold the current call and pick up the new one. Telecom
         * holds the active call itself when answering; holding it here as well
         * would send the modem two swap requests.
         */
        fun holdAndAnswer() {
            val ringing = ringingCall() ?: return
            ringing.answer(ringing.details.videoState)
        }

        /** Call waiting: hang up the current call, then pick up the new one once it's gone. */
        fun endAndAnswer() {
            val ringing = ringingCall() ?: return
            val current = _currentCall.value?.takeIf { it != ringing }
            if (current == null) {
                ringing.answer(ringing.details.videoState)
                return
            }
            val endedId = callMap[current]?.callId
            current.disconnect()
            val scope = instance?.serviceScope ?: return
            scope.launch {
                withTimeoutOrNull(3000) { _allCalls.first { list -> list.none { it.callId == endedId } } }
                if (ringing.state == Call.STATE_RINGING) ringing.answer(ringing.details.videoState)
            }
        }

        /** Two calls up: put the current one on hold and resume the other. */
        fun swapCalls() {
            val current = _currentCall.value ?: return
            if (current.details.can(Call.Details.CAPABILITY_SWAP_CONFERENCE)) {
                current.swapConference()
                return
            }
            val held = CallStateLogic.swapTarget(slots(), current) ?: return
            // Telecom holds the active call before resuming this one.
            held.unhold()
        }

        /**
         * Two calls up: join them into a conference. An existing conference with
         * CAPABILITY_MERGE_CONFERENCE pulls the other call in itself; otherwise
         * conference with a call the network lists as conferenceable. If neither
         * applies the network can't merge, and the UI hides Merge.
         */
        fun mergeCalls() {
            val current = _currentCall.value ?: return
            val partner = current.conferenceableCalls.firstOrNull()
            val mergeable = current.details.can(Call.Details.CAPABILITY_MERGE_CONFERENCE)
            val isConference = current.details.hasProperty(Call.Details.PROPERTY_CONFERENCE)
            when (CallStateLogic.mergeAction(isConference, mergeable, hasPartner = partner != null)) {
                MergeAction.MERGE_CONFERENCE -> current.mergeConference()
                MergeAction.CONFERENCE_WITH_PARTNER -> partner?.let(current::conference)
                MergeAction.NONE -> Unit
            }
        }

        /* ---------------- Video ---------------- */

        private var pendingUpgrade: Pair<Call, VideoProfile>? = null

        /** Ask the network to switch the active call to two-way video, or back to audio. */
        fun requestVideo(on: Boolean) {
            val videoCall = _currentCall.value?.videoCall ?: return
            val state = if (on) VideoProfile.STATE_BIDIRECTIONAL else VideoProfile.STATE_AUDIO_ONLY
            videoCall.sendSessionModifyRequest(VideoProfile(state))
        }

        fun acceptVideoUpgrade() {
            val (call, profile) = pendingUpgrade ?: return
            call.videoCall?.sendSessionModifyResponse(profile)
            pendingUpgrade = null
            updateCall(call) { it.copy(videoUpgradeRequested = false) }
        }

        fun declineVideoUpgrade() {
            val call = pendingUpgrade?.first ?: _currentCall.value
            call?.videoCall?.sendSessionModifyResponse(
                VideoProfile(VideoProfile.STATE_AUDIO_ONLY)
            )
            pendingUpgrade = null
            updateCall(call) { it.copy(videoUpgradeRequested = false) }
        }

        fun setPreviewSurface(surface: Surface?) {
            _currentCall.value?.videoCall?.setPreviewSurface(surface)
        }

        fun setDisplaySurface(surface: Surface?) {
            _currentCall.value?.videoCall?.setDisplaySurface(surface)
        }

        /** Front camera for the preview; null turns the camera off. */
        fun setCamera(context: Context, on: Boolean) {
            val videoCall = _currentCall.value?.videoCall ?: return
            if (!on) {
                videoCall.setCamera(null)
                return
            }
            val cameras = context.getSystemService(CameraManager::class.java)
            val front = cameras.cameraIdList.firstOrNull {
                cameras.getCameraCharacteristics(it)
                    .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
            } ?: cameras.cameraIdList.firstOrNull()
            videoCall.setCamera(front)
        }

        internal fun onRemoteVideoRequest(call: Call, profile: VideoProfile) {
            pendingUpgrade = call to profile
            updateCall(call) { it.copy(videoUpgradeRequested = true) }
        }

        /** Declines the ringing call (also a waiting call during another call). */
        fun rejectCurrentCall() {
            val call = ringingCall() ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                call.reject(Call.REJECT_REASON_DECLINED)
            } else {
                call.reject(false, null)
            }
        }

        /** Rejects the call; the network routes it to voicemail where one is set up. */
        fun sendToVoicemail() {
            ringingCall()?.reject(false, null)
        }

        /** Rejects the call and texts [message] to the caller, if the network allows it. */
        fun rejectWithMessage(message: String) {
            ringingCall()?.reject(true, message)
        }

        fun canReplyWithText(): Boolean =
            ringingCall()?.details?.can(Call.Details.CAPABILITY_RESPOND_VIA_TEXT) == true

        fun disconnectCurrentCall() {
            _currentCall.value?.disconnect()
        }

        /** Answers Telecom's "which SIM?" question for an outgoing call. */
        fun selectSim(handle: PhoneAccountHandle, setDefault: Boolean) {
            val call = _currentCall.value ?: return
            call.phoneAccountSelected(handle, setDefault)
            updateCall(call) { it.copy(simChoices = emptyList()) }
        }

        /** In-call keypad: start / stop a DTMF tone on the active call. */
        fun playDtmf(digit: Char) {
            _currentCall.value?.playDtmfTone(digit)
        }

        fun stopDtmf() {
            _currentCall.value?.stopDtmfTone()
        }

        fun holdCurrentCall(hold: Boolean) {
            val call = _currentCall.value ?: return
            if (hold) call.hold() else call.unhold()
        }

        /** Real microphone mute via the Telecom audio state. */
        fun setMute(muted: Boolean) {
            instance?.setMuted(muted)
        }

        /** Real speaker/earpiece routing via the Telecom audio state. */
        @Suppress("DEPRECATION")
        fun setSpeaker(on: Boolean) {
            val wanted = if (on) AudioRouteType.SPEAKER else AudioRouteType.EARPIECE
            val route = audioRoutes.firstOrNull { it.type == wanted }
            if (route != null) {
                selectAudioRoute(route.id)
                return
            }
            val fallback = if (on) CallAudioState.ROUTE_SPEAKER else CallAudioState.ROUTE_WIRED_OR_EARPIECE
            instance?.setAudioRoute(fallback)
        }

        /** Sends call audio to one of [ActiveCallData.audioRoutes]. */
        @Suppress("DEPRECATION")
        fun selectAudioRoute(id: String) {
            val service = instance ?: return
            val target = routeTargets[id] ?: return
            when {
                target is Int -> service.setAudioRoute(target)
                target is BluetoothDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ->
                    service.requestBluetoothAudio(target)
                target is BluetoothDevice -> service.setAudioRoute(CallAudioState.ROUTE_BLUETOOTH)
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && target is CallEndpoint ->
                    service.requestEndpoint(target)
            }
        }
    }

    private val callCallback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            super.onStateChanged(call, state)
            Log.d(TAG, "Call state changed to $state")
            val previous = callMap[call]?.state
            updateCallState(call, state)
            // After a connected call ends: the "Add a note" card and notification.
            CallNotePrompt.onCallStateChanged(applicationContext, call, state, callMap[call]?.callerName)
            buzzForStateChange(call, previous, state)
            // Smart SIM: remember which SIM this number was called on.
            if (state == Call.STATE_DIALING) com.example.allinonedailer.data.SmartSim.recordUsed(
                applicationContext, call.details.handle?.schemeSpecificPart.orEmpty(), call.details.accountHandle
            )
            updateCallWaitingBuzz()
            updateProximityLock()
            if (ringingCall() == null) endIncomingUi()

            if (state == Call.STATE_ACTIVE) {
                checkAndStartAutoRecording(call)
            } else if (state == Call.STATE_DISCONNECTED) {
                // Drop just this call; any other call stays on screen.
                callMap.remove(call)
                publish()
                if (callMap.isEmpty()) stopCallRecording()
                com.example.allinonedailer.widget.WidgetUpdates.onCallEnded(this@CallService)
            }
        }

        override fun onDetailsChanged(call: Call, details: Call.Details) {
            super.onDetailsChanged(call, details)
            // The SIM is known once picked from the chooser.
            accountLabelFor(call)?.let { label -> updateCall(call) { it.copy(accountLabel = label) } }
            updateVideoState(call)
            applyNetworkName(call)
            updateProximityLock()
        }

        override fun onVideoCallChanged(call: Call, videoCall: InCallService.VideoCall?) {
            super.onVideoCallChanged(call, videoCall)
            videoCall?.registerCallback(videoCallbackFor(call))
            updateVideoState(call)
        }

        // Merging, splitting and capability changes move calls in and out of the top-level list.
        override fun onParentChanged(call: Call, parent: Call?) = publish()
        override fun onChildrenChanged(call: Call, children: MutableList<Call>) = publish()
        override fun onConferenceableCallsChanged(call: Call, conferenceableCalls: MutableList<Call>) = publish()
    }

    private val videoCallbacks = HashMap<Call, InCallService.VideoCall.Callback>()

    /** Receives the other side's video requests for [call]; everything else is logged. */
    private fun videoCallbackFor(call: Call) = videoCallbacks.getOrPut(call) {
        object : InCallService.VideoCall.Callback() {
            override fun onSessionModifyRequestReceived(videoProfile: VideoProfile) {
                if (VideoProfile.isVideo(videoProfile.videoState)) onRemoteVideoRequest(call, videoProfile)
            }

            override fun onSessionModifyResponseReceived(
                status: Int, requestedProfile: VideoProfile, responseProfile: VideoProfile
            ) {
                Log.d(TAG, "Video modify response status=$status state=${responseProfile.videoState}")
            }

            override fun onCallSessionEvent(event: Int) = Unit
            override fun onPeerDimensionsChanged(width: Int, height: Int) = Unit
            override fun onCallDataUsageChanged(dataUsage: Long) = Unit
            override fun onCameraCapabilitiesChanged(cameraCapabilities: VideoProfile.CameraCapabilities) = Unit
            override fun onVideoQualityChanged(videoQuality: Int) = Unit
        }
    }

    /**
     * Uses the caller name the network sends with the call (CNAP; India
     * mandates it on 4G and newer networks from 1 Nov 2026) for callers not in
     * your contacts, and remembers it for Recents.
     */
    private fun applyNetworkName(call: Call) {
        val details = call.details
        val number = details.handle?.schemeSpecificPart ?: return
        // Some networks put the number itself in the name field; that's not a name.
        val name = CallStateLogic.acceptNetworkName(
            details.callerDisplayNamePresentation == TelecomManager.PRESENTATION_ALLOWED,
            details.callerDisplayName,
            number
        ) ?: return
        val current = callMap[call] ?: return
        if (current.nameFromNetwork && current.callerName == name) return

        serviceScope.launch(Dispatchers.IO) {
            CallerIdRepository(applicationContext).saveNetworkName(number, name)
        }
        updateCall(call) { data ->
            if (data.inContacts) data else data.copy(callerName = name, nameFromNetwork = true)
        }
        if (call.state == Call.STATE_RINGING && callMap[call]?.inContacts == false) {
            showIncomingUi(number, name)
        }
    }

    /**
     * Vibration for call events, played here so it works with the screen off:
     * a double buzz when an outgoing call is answered, one longer buzz when a
     * call ends. Conference members are left out; the conference itself counts.
     */
    private fun buzzForStateChange(call: Call, previous: Int?, state: Int) {
        if (call.parent != null || previous == null || previous == state) return
        val outgoing = previous == Call.STATE_DIALING || previous == Call.STATE_CONNECTING ||
            previous == Call.STATE_PULLING_CALL
        when {
            state == Call.STATE_ACTIVE && outgoing -> Haptics.play(this, Haptics.Effect.CONNECTED)
            state == Call.STATE_DISCONNECTED -> Haptics.play(this, Haptics.Effect.ENDED)
        }
    }

    private var callWaitingJob: Job? = null

    /** Face-down while ringing silences the ringer; also stops the call-waiting buzz. */
    private val flipToSilence by lazy { FlipToSilence(this) { updateCallWaitingBuzz() } }

    /**
     * Telecom doesn't vibrate for a second call that rings during a call, so
     * repeat a distinctive pattern while one is waiting.
     */
    private fun updateCallWaitingBuzz() {
        val live = callMap.keys.filter { it.parent == null && it.state != Call.STATE_DISCONNECTED }
        val audible = live.any {
            it.state == Call.STATE_RINGING && !QuietHoursGate.isSilenced(it.details.handle?.schemeSpecificPart)
        }
        // Flip to silence listens only while an audible call rings.
        flipToSilence.update(audible)
        val waiting = audible && !flipToSilence.silenced &&
            live.any { it.state != Call.STATE_RINGING && it.state != Call.STATE_SIMULATED_RINGING }
        if (!waiting) {
            callWaitingJob?.cancel()
            callWaitingJob = null
            return
        }
        if (callWaitingJob?.isActive == true) return
        callWaitingJob = serviceScope.launch {
            while (true) {
                Haptics.play(this@CallService, Haptics.Effect.CALL_WAITING)
                delay(3000)
            }
        }
    }

    private fun updateVideoState(call: Call) {
        val details = call.details
        updateCall(call) {
            it.copy(
                videoCapable = details.can(Call.Details.CAPABILITY_SUPPORTS_VT_LOCAL_BIDIRECTIONAL) &&
                    details.can(Call.Details.CAPABILITY_SUPPORTS_VT_REMOTE_BIDIRECTIONAL),
                isVideo = VideoProfile.isVideo(details.videoState)
            )
        }
    }

    /** Turns the screen off while the phone is at your ear, so a cheek can't press Mute or End. */
    private var proximityLock: PowerManager.WakeLock? = null

    /**
     * Holds the proximity lock while a call is dialing, connected or on hold and
     * audio goes to the earpiece. Not while a call is ringing (you need the answer
     * knob), on video, or on speaker / Bluetooth / a wired headset.
     */
    @Suppress("DEPRECATION")
    private fun updateProximityLock() {
        val live = calls.filter { it.state != Call.STATE_DISCONNECTED }
        val video = live.any { VideoProfile.isVideo(it.details.videoState) }
        val earpiece = callAudioState?.route == CallAudioState.ROUTE_EARPIECE
        val want = CallStateLogic.wantsProximityLock(live.map { it.state }, video, earpiece)

        if (want) {
            val lock = proximityLock ?: run {
                val power = getSystemService(PowerManager::class.java)
                if (!power.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) return
                power.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "$TAG:proximity")
                    .apply { setReferenceCounted(false) }
                    .also { proximityLock = it }
            }
            if (!lock.isHeld) {
                lock.acquire()
                Log.d(TAG, "Proximity lock acquired")
            }
        } else {
            proximityLock?.takeIf { it.isHeld }?.let {
                // Keep the screen off until the phone leaves the face.
                it.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY)
                Log.d(TAG, "Proximity lock released")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // The pill went away while the call still rings: fall back to the notification.
        CallPill.onFallback = { quiet ->
            ringingCall()?.let { call ->
                val data = callMap[call]
                showIncomingCallNotification(data?.phoneNumber ?: getString(R.string.unknown), data?.callerName, quiet)
            }
        }
    }

    override fun onCanAddCallChanged(canAddCall: Boolean) {
        super.onCanAddCallChanged(canAddCall)
        _canAddCall.value = canAddCall
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        Log.d(TAG, "onCallAdded (${callMap.size} already up)")
        instance = this
        call.registerCallback(callCallback)
        updateProximityLock()

        val handle = call.details.handle?.schemeSpecificPart ?: getString(R.string.unknown)
        callMap[call] = ActiveCallData(
            phoneNumber = handle,
            callerName = null,
            isSpam = false,
            spamCategory = null,
            state = call.state,
            lookupPending = true,
            accountLabel = accountLabelFor(call),
            simChoices = simChoicesFor(call, call.state),
            callId = "call-${nextCallId++}"
        )
        // The added call is placed: close the add-call dial pad so the two-call screen shows.
        if (call.state != Call.STATE_RINGING && call.parent == null) _addingCall.value = false
        _canAddCall.value = canAddCall()
        publish()
        if (call.state == Call.STATE_SELECT_PHONE_ACCOUNT) showSimChooser()
        // Smart SIM: an outgoing call that already has its SIM.
        if (call.state == Call.STATE_DIALING || call.state == Call.STATE_CONNECTING) {
            com.example.allinonedailer.data.SmartSim.recordUsed(applicationContext, call.details.handle?.schemeSpecificPart.orEmpty(), call.details.accountHandle)
        }
        applyNetworkName(call)
        if (call.details.handle != null) populateCallerInfo(call, handle)
        call.videoCall?.registerCallback(videoCallbackFor(call))
        updateVideoState(call)

        if (call.state == Call.STATE_RINGING) {
            showIncomingUi(handle, null)
        }
        updateCallWaitingBuzz()

        if (call.state == Call.STATE_ACTIVE) {
            checkAndStartAutoRecording(call)
        }
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        Log.d(TAG, "onCallRemoved")
        call.unregisterCallback(callCallback)
        videoCallbacks.remove(call)?.let { call.videoCall?.unregisterCallback(it) }
        if (pendingUpgrade?.first == call) pendingUpgrade = null
        updateProximityLock()
        if (ringingCall() == null) endIncomingUi()
        callMap.remove(call)
        publish()
        updateCallWaitingBuzz()
        // Only once the last call is gone does the screen go back to "no call".
        if (callMap.isEmpty()) {
            _addingCall.value = false
            stopCallRecording()
        }
    }

    @Suppress("DEPRECATION")
    override fun onCallAudioStateChanged(audioState: CallAudioState) {
        super.onCallAudioStateChanged(audioState)
        updateProximityLock()
        audioMuted = audioState.isMuted
        audioSpeaker = audioState.route == CallAudioState.ROUTE_SPEAKER
        // Android 14+ reports routes through call endpoints instead.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) setRoutesFrom(audioState)
        publish()
    }

    /* ---------------- Audio routes ---------------- */

    private var endpoints: List<CallEndpoint> = emptyList()
    private var activeEndpoint: CallEndpoint? = null

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    override fun onAvailableCallEndpointsChanged(availableEndpoints: MutableList<CallEndpoint>) {
        super.onAvailableCallEndpointsChanged(availableEndpoints)
        endpoints = availableEndpoints.toList()
        setRoutesFromEndpoints()
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    override fun onCallEndpointChanged(callEndpoint: CallEndpoint) {
        super.onCallEndpointChanged(callEndpoint)
        activeEndpoint = callEndpoint
        setRoutesFromEndpoints()
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun setRoutesFromEndpoints() {
        val active = activeEndpoint?.identifier
        val routes = mutableListOf<AudioRoute>()
        val targets = mutableMapOf<String, Any>()
        // The active endpoint can arrive before the list; show it on its own until then.
        for (endpoint in endpoints.ifEmpty { listOfNotNull(activeEndpoint) }) {
            val type = when (endpoint.endpointType) {
                CallEndpoint.TYPE_EARPIECE -> AudioRouteType.EARPIECE
                CallEndpoint.TYPE_WIRED_HEADSET -> AudioRouteType.WIRED_HEADSET
                CallEndpoint.TYPE_SPEAKER -> AudioRouteType.SPEAKER
                CallEndpoint.TYPE_BLUETOOTH -> AudioRouteType.BLUETOOTH
                else -> continue
            }
            val id = endpoint.identifier.toString()
            val name = routeName(type, endpoint.endpointName.toString())
            routes += AudioRoute(id, type, name, isActive = endpoint.identifier == active)
            targets[id] = endpoint
        }
        applyRoutes(routes, targets)
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun requestEndpoint(endpoint: CallEndpoint) {
        requestCallEndpointChange(endpoint, mainExecutor, object : OutcomeReceiver<Void, CallEndpointException> {
            override fun onResult(result: Void?) = Unit
            override fun onError(error: CallEndpointException) {
                Log.w(TAG, "Couldn't switch audio to ${endpoint.endpointName}", error)
            }
        })
    }

    /** Android 13 and older: routes from the route mask and the paired Bluetooth devices. */
    @Suppress("DEPRECATION")
    private fun setRoutesFrom(state: CallAudioState) {
        val mask = state.supportedRouteMask
        val routes = mutableListOf<AudioRoute>()
        val targets = mutableMapOf<String, Any>()
        fun add(route: Int, type: AudioRouteType) {
            if (mask and route == 0) return
            val id = "route-$route"
            routes += AudioRoute(id, type, routeName(type, null), isActive = state.route == route)
            targets[id] = route
        }
        add(CallAudioState.ROUTE_EARPIECE, AudioRouteType.EARPIECE)
        add(CallAudioState.ROUTE_WIRED_HEADSET, AudioRouteType.WIRED_HEADSET)
        add(CallAudioState.ROUTE_SPEAKER, AudioRouteType.SPEAKER)
        val devices = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            state.supportedBluetoothDevices.toList()
        } else {
            emptyList()
        }
        if (devices.isEmpty()) {
            add(CallAudioState.ROUTE_BLUETOOTH, AudioRouteType.BLUETOOTH)
        } else {
            val activeDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) state.activeBluetoothDevice else null
            devices.forEachIndexed { i, device ->
                val id = "bt-$i"
                val on = state.route == CallAudioState.ROUTE_BLUETOOTH &&
                    (activeDevice == null || activeDevice == device)
                val name = routeName(AudioRouteType.BLUETOOTH, bluetoothName(device))
                routes += AudioRoute(id, AudioRouteType.BLUETOOTH, name, isActive = on)
                targets[id] = device
            }
        }
        applyRoutes(routes, targets)
    }

    /** Needs BLUETOOTH_CONNECT on Android 12+; without it the device shows as "Bluetooth". */
    @Suppress("MissingPermission")
    private fun bluetoothName(device: BluetoothDevice): String? = try {
        device.name
    } catch (e: SecurityException) {
        null
    }

    private fun routeName(type: AudioRouteType, deviceName: String?): String =
        CallStateLogic.routeName(type, deviceName)

    private fun applyRoutes(routes: List<AudioRoute>, targets: Map<String, Any>) {
        audioRoutes = routes.sortedBy { it.type.ordinal }
        routeTargets = targets
        audioRoutes.firstOrNull { it.isActive }?.let { audioSpeaker = it.type == AudioRouteType.SPEAKER }
        updateProximityLock()
        publish()
    }

    private fun updateCallState(call: Call, state: Int) {
        val handle = call.details.handle?.schemeSpecificPart ?: getString(R.string.unknown)
        updateCall(call) { current ->
            current.copy(
                phoneNumber = handle,
                state = state,
                simChoices = simChoicesFor(call, state),
                accountLabel = accountLabelFor(call) ?: current.accountLabel
            )
        }
    }

    /**
     * The SIMs Telecom offers when no default SIM is set for calls ("Ask every
     * time"). Telecom lists them in the call's intent extras; fall back to all
     * call-capable accounts.
     */
    private fun simChoicesFor(call: Call, state: Int): List<SimOption> {
        if (state != Call.STATE_SELECT_PHONE_ACCOUNT) return emptyList()
        val offered = listOfNotNull(call.details.intentExtras, call.details.extras)
            .firstNotNullOfOrNull { extras ->
                extras.classLoader = PhoneAccountHandle::class.java.classLoader
                @Suppress("DEPRECATION")
                extras.getParcelableArrayList<PhoneAccountHandle>(Call.AVAILABLE_PHONE_ACCOUNTS)
                    ?.takeIf { it.isNotEmpty() }
            }
        return callCapableSims(this, offered)
    }

    /** Brings the chooser up when the call was started from another app. */
    private fun showSimChooser() {
        try {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't open the SIM chooser", e)
        }
    }

    /** Asynchronously resolve caller ID / spam status and merge it into [call]'s data. */
    private fun populateCallerInfo(call: Call, phoneNumber: String) {
        serviceScope.launch {
            val info = CallerIdRepository(applicationContext).lookupCallerInfoAsync(phoneNumber)
            updateCall(call) { current ->
                current.copy(
                    // Keep a network name that arrived while the lookup ran.
                    callerName = info.name ?: current.callerName,
                    nameFromNetwork = if (info.name != null) info.source == "network" else current.nameFromNetwork,
                    isSpam = info.isSpam,
                    spamCategory = info.spamCategory,
                    inContacts = info.inContacts,
                    lookupPending = false,
                    photoUri = info.photoUri?.toString(),
                    numberLabel = info.numberLabel
                )
            }
            // Refresh the incoming notification with the resolved name.
            if (call.state == Call.STATE_RINGING) {
                showIncomingUi(phoneNumber, callMap[call]?.callerName)
            }
        }
    }

    /**
     * The ringing call's UI outside the app: the call pill over other apps
     * when it can show (unlocked, another app in front, overlay allowed),
     * otherwise the heads-up notification.
     */
    private fun showIncomingUi(number: String, name: String?) {
        val id = ringingCall()?.let { callMap[it]?.callId }
        if (id != null && CallPill.show(this, id, number)) {
            cancelIncomingCallNotification()
            return
        }
        showIncomingCallNotification(number, name, quiet = id != null && CallPill.wantsQuietNotification(id))
    }

    /** Nothing rings any more: drop the pill and the incoming notification. */
    private fun endIncomingUi() {
        cancelIncomingCallNotification()
        CallPill.dismiss()
    }

    /**
     * Heads-up call notification with Answer / Decline. Its full-screen intent
     * opens the incoming-call screen when the device is locked or the app is in
     * the background. The ringtone itself is played by Telecom, so the channel
     * is silent. [quiet]: the pill was swiped away (or the app took over), so
     * it only waits in the shade, on a low-importance channel with no heads-up.
     */
    private fun showIncomingCallNotification(number: String, name: String?, quiet: Boolean = false) {
        // Quiet hours silenced this call: no heads-up or full-screen wake-up either.
        val silent = quiet || QuietHoursGate.isSilenced(number)
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    INCOMING_CHANNEL_ID,
                    getString(R.string.incoming_calls_channel),
                    NotificationManager.IMPORTANCE_HIGH
                ).apply { setSound(null, null) }
            )
            if (silent) manager.createNotificationChannel(
                NotificationChannel(
                    INCOMING_QUIET_CHANNEL_ID,
                    getString(R.string.incoming_calls_channel),
                    NotificationManager.IMPORTANCE_LOW
                ).apply { setSound(null, null) }
            )
        }

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val openIntent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val fullScreen = PendingIntent.getActivity(this, 0, openIntent, flags)
        val answer = PendingIntent.getActivity(
            this, 1,
            Intent(openIntent).setAction(MainActivity.ACTION_ANSWER_CALL),
            flags
        )
        val decline = PendingIntent.getBroadcast(
            this, 2,
            Intent(this, CallActionReceiver::class.java).setAction(CallActionReceiver.ACTION_DECLINE),
            flags
        )

        val waiting = CallStateLogic.isCallWaiting(slots())
        val title = name ?: number
        val caller = Person.Builder().setName(title).setImportant(true).build()
        val notification = NotificationCompat.Builder(this, if (silent) INCOMING_QUIET_CHANNEL_ID else INCOMING_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(getString(if (waiting) R.string.call_waiting else R.string.incoming_call))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(if (silent) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setContentIntent(fullScreen)
            // Android requires a full-screen intent on a CallStyle notification that isn't
            // from a foreground service; on the low-importance channel it never launches.
            .setFullScreenIntent(fullScreen, true)
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(caller, decline, answer))
            .build()

        try {
            manager.notify(INCOMING_NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Notification permission missing; incoming-call UI needs the app open", e)
        }
    }

    private fun accountLabelFor(call: Call): String? = try {
        val telecom = getSystemService(TelecomManager::class.java)
        call.details.accountHandle?.let { telecom.getPhoneAccount(it)?.label?.toString() }
    } catch (e: Exception) {
        null
    }

    private fun cancelIncomingCallNotification() {
        getSystemService(NotificationManager::class.java).cancel(INCOMING_NOTIFICATION_ID)
    }

    private fun checkAndStartAutoRecording(call: Call) {
        val repo = RecordingRepository(applicationContext)
        if (repo.isAutoRecordEnabled()) {
            val handle = call.details.handle?.schemeSpecificPart ?: getString(R.string.unknown)
            // Never hold the microphone during an emergency call.
            if (CallPlacer.isEmergency(applicationContext, handle)) return
            startCallRecording(handle)
        }
    }

    /** One recording covers the whole session, however many calls come and go. */
    private fun startCallRecording(phoneNumber: String) {
        if (recordingOn) return
        val intent = Intent(this, CallRecordingService::class.java).apply {
            action = CallRecordingService.ACTION_START_RECORDING
            putExtra(CallRecordingService.EXTRA_PHONE_NUMBER, phoneNumber)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        recordingOn = true
        publish()
    }

    private fun stopCallRecording() {
        val intent = Intent(this, CallRecordingService::class.java).apply {
            action = CallRecordingService.ACTION_STOP_RECORDING
        }
        startService(intent)
        recordingOn = false
        publish()
    }

    override fun onDestroy() {
        flipToSilence.stop()
        if (instance == this) instance = null
        CallPill.onFallback = null
        CallPill.dismiss()
        OngoingCallNotification.stop(this)
        GlyphController.onCallServiceGone(this)
        audioRoutes = emptyList()
        routeTargets = emptyMap()
        proximityLock?.takeIf { it.isHeld }?.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY)
        serviceScope.cancel()
        super.onDestroy()
    }
}
