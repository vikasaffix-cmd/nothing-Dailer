package com.example.allinonedailer.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.telecom.VideoProfile
import android.view.Surface
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.telecom.TelecomManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import com.example.allinonedailer.R
import com.example.allinonedailer.data.CallerIdRepository
import com.example.allinonedailer.data.RecordingRepository
import com.example.allinonedailer.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
    val nameFromNetwork: Boolean = false
)

class CallService : InCallService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    companion object {
        private const val TAG = "CallService"
        private const val INCOMING_CHANNEL_ID = "incoming_calls"
        private const val INCOMING_NOTIFICATION_ID = 2001

        /** Live reference to the bound service so UI actions can reach the Telecom APIs. */
        private var instance: CallService? = null

        private val _currentCall = MutableStateFlow<Call?>(null)
        val currentCall: StateFlow<Call?> = _currentCall.asStateFlow()

        private val _callData = MutableStateFlow<ActiveCallData?>(null)
        val callData: StateFlow<ActiveCallData?> = _callData.asStateFlow()

        fun answerCurrentCall() {
            val call = _currentCall.value ?: return
            // Accept with the offered video state, so incoming video calls answer as video.
            call.answer(call.details.videoState)
        }

        /* ---------------- Video ---------------- */

        private var pendingUpgrade: VideoProfile? = null

        /** Ask the network to switch the active call to two-way video, or back to audio. */
        fun requestVideo(on: Boolean) {
            val videoCall = _currentCall.value?.videoCall ?: return
            val state = if (on) VideoProfile.STATE_BIDIRECTIONAL else VideoProfile.STATE_AUDIO_ONLY
            videoCall.sendSessionModifyRequest(VideoProfile(state))
        }

        fun acceptVideoUpgrade() {
            val profile = pendingUpgrade ?: return
            _currentCall.value?.videoCall?.sendSessionModifyResponse(profile)
            pendingUpgrade = null
            _callData.update { it?.copy(videoUpgradeRequested = false) }
        }

        fun declineVideoUpgrade() {
            _currentCall.value?.videoCall?.sendSessionModifyResponse(
                VideoProfile(VideoProfile.STATE_AUDIO_ONLY)
            )
            pendingUpgrade = null
            _callData.update { it?.copy(videoUpgradeRequested = false) }
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

        internal fun onRemoteVideoRequest(profile: VideoProfile) {
            pendingUpgrade = profile
            _callData.update { it?.copy(videoUpgradeRequested = true) }
        }

        fun rejectCurrentCall() {
            val call = _currentCall.value ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                call.reject(Call.REJECT_REASON_DECLINED)
            } else {
                call.reject(false, null)
            }
        }

        /** Rejects the call; the network routes it to voicemail where one is set up. */
        fun sendToVoicemail() {
            _currentCall.value?.reject(false, null)
        }

        /** Rejects the call and texts [message] to the caller, if the network allows it. */
        fun rejectWithMessage(message: String) {
            _currentCall.value?.reject(true, message)
        }

        fun canReplyWithText(): Boolean =
            _currentCall.value?.details?.can(Call.Details.CAPABILITY_RESPOND_VIA_TEXT) == true

        fun disconnectCurrentCall() {
            _currentCall.value?.disconnect()
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
            val route = if (on) CallAudioState.ROUTE_SPEAKER else CallAudioState.ROUTE_WIRED_OR_EARPIECE
            instance?.setAudioRoute(route)
        }
    }

    private val callCallback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            super.onStateChanged(call, state)
            Log.d(TAG, "Call state changed to $state")
            updateCallState(call, state)
            if (state != Call.STATE_RINGING) cancelIncomingCallNotification()

            if (state == Call.STATE_ACTIVE) {
                checkAndStartAutoRecording(call)
            } else if (state == Call.STATE_DISCONNECTED) {
                stopCallRecording()
                _currentCall.value = null
                _callData.value = null
            }
        }

        override fun onDetailsChanged(call: Call, details: Call.Details) {
            super.onDetailsChanged(call, details)
            updateVideoState(call)
            applyNetworkName(call)
        }

        override fun onVideoCallChanged(call: Call, videoCall: InCallService.VideoCall?) {
            super.onVideoCallChanged(call, videoCall)
            videoCall?.registerCallback(videoCallback)
            updateVideoState(call)
        }
    }

    /** Receives the other side's video requests; everything else is logged. */
    private val videoCallback = object : InCallService.VideoCall.Callback() {
        override fun onSessionModifyRequestReceived(videoProfile: VideoProfile) {
            if (VideoProfile.isVideo(videoProfile.videoState)) onRemoteVideoRequest(videoProfile)
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

    /**
     * Uses the caller name the network sends with the call (CNAP; India
     * mandates it on 4G and newer networks from 1 Nov 2026) for callers not in
     * your contacts, and remembers it for Recents.
     */
    private fun applyNetworkName(call: Call) {
        val details = call.details
        if (details.callerDisplayNamePresentation != TelecomManager.PRESENTATION_ALLOWED) return
        val name = details.callerDisplayName?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val number = details.handle?.schemeSpecificPart ?: return
        // Some networks put the number itself in the name field; that's not a name.
        if (name.filter(Char::isDigit) == number.filter(Char::isDigit)) return
        val current = _callData.value
        if (current != null && current.nameFromNetwork && current.callerName == name) return

        serviceScope.launch(Dispatchers.IO) {
            CallerIdRepository(applicationContext).saveNetworkName(number, name)
        }
        _callData.update { current ->
            if (current == null || current.inContacts) current
            else current.copy(callerName = name, nameFromNetwork = true)
        }
        if (_callData.value?.state == Call.STATE_RINGING && _callData.value?.inContacts == false) {
            showIncomingCallNotification(number, name)
        }
    }

    private fun updateVideoState(call: Call) {
        val details = call.details
        _callData.update {
            it?.copy(
                videoCapable = details.can(Call.Details.CAPABILITY_SUPPORTS_VT_LOCAL_BIDIRECTIONAL) &&
                    details.can(Call.Details.CAPABILITY_SUPPORTS_VT_REMOTE_BIDIRECTIONAL),
                isVideo = VideoProfile.isVideo(details.videoState)
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        Log.d(TAG, "onCallAdded")
        instance = this
        _currentCall.value = call
        call.registerCallback(callCallback)

        val handle = call.details.handle?.schemeSpecificPart ?: "Unknown"
        _callData.value = ActiveCallData(
            phoneNumber = handle,
            callerName = null,
            isSpam = false,
            spamCategory = null,
            state = call.state,
            lookupPending = true,
            accountLabel = accountLabelFor(call)
        )
        applyNetworkName(call)
        populateCallerInfo(handle)
        call.videoCall?.registerCallback(videoCallback)
        updateVideoState(call)

        if (call.state == Call.STATE_RINGING) {
            showIncomingCallNotification(handle, null)
        }

        if (call.state == Call.STATE_ACTIVE) {
            checkAndStartAutoRecording(call)
        }
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        Log.d(TAG, "onCallRemoved")
        call.unregisterCallback(callCallback)
        cancelIncomingCallNotification()
        stopCallRecording()
        if (_currentCall.value == call) {
            _currentCall.value = null
            _callData.value = null
        }
    }

    @Suppress("DEPRECATION")
    override fun onCallAudioStateChanged(audioState: CallAudioState) {
        super.onCallAudioStateChanged(audioState)
        _callData.update {
            it?.copy(
                isMuted = audioState.isMuted,
                isSpeakerOn = audioState.route == CallAudioState.ROUTE_SPEAKER
            )
        }
    }

    private fun updateCallState(call: Call, state: Int) {
        val handle = call.details.handle?.schemeSpecificPart ?: "Unknown"
        _callData.update { current ->
            (current ?: ActiveCallData(
                phoneNumber = handle,
                callerName = null,
                isSpam = false,
                spamCategory = null,
                state = state,
                lookupPending = true
            )).copy(phoneNumber = handle, state = state)
        }
    }

    /** Asynchronously resolve caller ID / spam status and merge it into the live call data. */
    private fun populateCallerInfo(phoneNumber: String) {
        serviceScope.launch {
            val info = CallerIdRepository(applicationContext).lookupCallerInfoAsync(phoneNumber)
            _callData.update { current ->
                current?.copy(
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
            if (_callData.value?.state == Call.STATE_RINGING) {
                showIncomingCallNotification(phoneNumber, info.name)
            }
        }
    }

    /**
     * Heads-up call notification with Answer / Decline. Its full-screen intent
     * opens the incoming-call screen when the device is locked or the app is in
     * the background. The ringtone itself is played by Telecom, so the channel
     * is silent.
     */
    private fun showIncomingCallNotification(number: String, name: String?) {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    INCOMING_CHANNEL_ID,
                    "Incoming calls",
                    NotificationManager.IMPORTANCE_HIGH
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

        val title = name ?: number
        val caller = Person.Builder().setName(title).setImportant(true).build()
        val notification = NotificationCompat.Builder(this, INCOMING_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText("Incoming call")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setContentIntent(fullScreen)
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
            val handle = call.details.handle?.schemeSpecificPart ?: "Unknown"
            startCallRecording(handle)
        }
    }

    private fun startCallRecording(phoneNumber: String) {
        val intent = Intent(this, CallRecordingService::class.java).apply {
            action = CallRecordingService.ACTION_START_RECORDING
            putExtra(CallRecordingService.EXTRA_PHONE_NUMBER, phoneNumber)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        _callData.update { it?.copy(isRecording = true) }
    }

    private fun stopCallRecording() {
        val intent = Intent(this, CallRecordingService::class.java).apply {
            action = CallRecordingService.ACTION_STOP_RECORDING
        }
        startService(intent)
        _callData.update { it?.copy(isRecording = false) }
    }

    override fun onDestroy() {
        if (instance == this) instance = null
        serviceScope.cancel()
        super.onDestroy()
    }
}
