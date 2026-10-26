package com.example.allinonedailer.service

import android.telecom.Call
import com.example.allinonedailer.data.PhoneNumbers

/**
 * What the call-handling decisions need to know about one call. [call] is the
 * Telecom [Call] in the service and any stand-in in JVM tests; [topLevel] is
 * false for a conference member (a call with a parent).
 */
data class CallSlot<T>(val call: T, val state: Int, val topLevel: Boolean = true)

/** What Merge does, given the current call's conference state and capabilities. */
enum class MergeAction { MERGE_CONFERENCE, CONFERENCE_WITH_PARTNER, NONE }

/** Per-member conference controls, from the conference's and the member's capabilities. */
data class ConferenceControls(val canEnd: Boolean, val canSplit: Boolean)

/**
 * Call-handling decisions, kept free of Telecom objects so they run in JVM
 * tests. Only the Call.STATE_ constants are used, which the compiler inlines.
 */
object CallStateLogic {

    /** Higher wins the screen: connected, then dialing, ringing, held, ending. */
    fun foregroundRank(state: Int): Int = when (state) {
        Call.STATE_ACTIVE -> 5
        Call.STATE_DIALING, Call.STATE_CONNECTING, Call.STATE_PULLING_CALL,
        Call.STATE_SELECT_PHONE_ACCOUNT, Call.STATE_NEW -> 4
        Call.STATE_RINGING, Call.STATE_SIMULATED_RINGING -> 3
        Call.STATE_HOLDING -> 2
        Call.STATE_DISCONNECTING -> 1
        else -> 0
    }

    /** Top-level calls that haven't disconnected, in arrival order. */
    fun <T> live(calls: List<CallSlot<T>>): List<CallSlot<T>> =
        calls.filter { it.topLevel && it.state != Call.STATE_DISCONNECTED }

    /** The call the screen is about; the earliest one wins a tie. */
    fun <T> foreground(calls: List<CallSlot<T>>): T? =
        live(calls).maxByOrNull { foregroundRank(it.state) }?.call

    /** The top-level ringing call (a waiting call during another call), if any. */
    fun <T> ringing(calls: List<CallSlot<T>>): T? =
        calls.firstOrNull { it.topLevel && it.state == Call.STATE_RINGING }?.call

    /** Swap without CAPABILITY_SWAP_CONFERENCE: the held call to resume instead of [current]. */
    fun <T> swapTarget(calls: List<CallSlot<T>>, current: T): T? =
        calls.firstOrNull { it.call != current && it.topLevel && it.state == Call.STATE_HOLDING }?.call

    /** Leaving the add-call dial pad: the call held for it, when it's the only call left. */
    fun <T> callToResumeAfterAddCall(calls: List<CallSlot<T>>): T? =
        live(calls).singleOrNull()?.takeIf { it.state == Call.STATE_HOLDING }?.call

    /** A ringing call is "call waiting" when another top-level call is up. */
    fun <T> isCallWaiting(calls: List<CallSlot<T>>): Boolean =
        calls.any { it.topLevel && it.state != Call.STATE_RINGING && it.state != Call.STATE_DISCONNECTED }

    /** Merge is offered when the network lists a partner or the call can pull others in. */
    fun canMerge(hasConferenceableCalls: Boolean, canMergeConference: Boolean): Boolean =
        hasConferenceableCalls || canMergeConference

    fun mergeAction(isConference: Boolean, canMergeConference: Boolean, hasPartner: Boolean): MergeAction = when {
        isConference && canMergeConference -> MergeAction.MERGE_CONFERENCE
        hasPartner -> MergeAction.CONFERENCE_WITH_PARTNER
        canMergeConference -> MergeAction.MERGE_CONFERENCE
        else -> MergeAction.NONE
    }

    /** A member can be dropped or split off only when the conference itself is manageable. */
    fun conferenceControls(manageable: Boolean, canDisconnect: Boolean, canSeparate: Boolean) =
        ConferenceControls(canEnd = manageable && canDisconnect, canSplit = manageable && canSeparate)

    /**
     * Screen-off-at-ear lock: wanted while a call is dialing, connected or held
     * with audio on the earpiece, but not while one rings or is on video.
     * [states] are every call's state, conference members included.
     */
    fun wantsProximityLock(states: List<Int>, anyVideo: Boolean, earpiece: Boolean): Boolean {
        val live = states.filter { it != Call.STATE_DISCONNECTED }
        val inProgress = live.any {
            it == Call.STATE_DIALING || it == Call.STATE_CONNECTING || it == Call.STATE_ACTIVE ||
                it == Call.STATE_HOLDING || it == Call.STATE_PULLING_CALL
        }
        val ringing = live.any { it == Call.STATE_RINGING || it == Call.STATE_SIMULATED_RINGING }
        return inProgress && !ringing && !anyVideo && earpiece
    }

    /**
     * The caller name the network sent (CNAP), or null when it can't be shown:
     * presentation not allowed, blank, or not a name at all. Some networks put
     * the number in the name field, with or without +91, spaces or dashes, so
     * a "name" without a single letter, or one that spells out the number, is
     * rejected.
     */
    fun acceptNetworkName(presentationAllowed: Boolean, rawName: String?, number: String?): String? {
        if (!presentationAllowed || number == null) return null
        val name = rawName?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (name.none(Char::isLetter)) return null
        if (PhoneNumbers.sameNumber(name, number)) return null
        return name
    }

    /** Label for an audio route; a Bluetooth device shows its own name when it has one. */
    fun routeName(type: AudioRouteType, deviceName: String?): String = when (type) {
        AudioRouteType.EARPIECE -> "Phone"
        AudioRouteType.SPEAKER -> "Speaker"
        AudioRouteType.WIRED_HEADSET -> "Wired headset"
        AudioRouteType.BLUETOOTH -> deviceName?.trim()?.takeIf { it.isNotEmpty() } ?: "Bluetooth"
    }
}
