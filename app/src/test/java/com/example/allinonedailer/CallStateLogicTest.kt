package com.example.allinonedailer

import android.telecom.Call
import com.example.allinonedailer.service.AudioRouteType
import com.example.allinonedailer.service.CallSlot
import com.example.allinonedailer.service.CallStateLogic
import com.example.allinonedailer.service.MergeAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallStateLogicTest {

    private fun slot(call: String, state: Int, topLevel: Boolean = true) = CallSlot(call, state, topLevel)

    // ---- One call: DIALING -> ACTIVE -> DISCONNECTED ----

    @Test
    fun outgoingCallStaysForegroundThroughItsLife() {
        for (state in listOf(Call.STATE_SELECT_PHONE_ACCOUNT, Call.STATE_CONNECTING, Call.STATE_DIALING, Call.STATE_ACTIVE)) {
            assertEquals("state $state", "A", CallStateLogic.foreground(listOf(slot("A", state))))
        }
        assertNull(CallStateLogic.foreground(listOf(slot("A", Call.STATE_DISCONNECTED))))
        assertNull(CallStateLogic.foreground(emptyList<CallSlot<String>>()))
    }

    @Test
    fun endingCallStillShowsUntilDisconnected() {
        assertEquals("A", CallStateLogic.foreground(listOf(slot("A", Call.STATE_DISCONNECTING))))
    }

    @Test
    fun foregroundRankOrder() {
        val order = listOf(
            Call.STATE_ACTIVE, Call.STATE_DIALING, Call.STATE_RINGING,
            Call.STATE_HOLDING, Call.STATE_DISCONNECTING, Call.STATE_DISCONNECTED
        ).map(CallStateLogic::foregroundRank)
        assertEquals(order.sortedDescending(), order)
        assertEquals(order.size, order.distinct().size)
    }

    // ---- Two calls ----

    @Test
    fun secondCallRingingDuringActiveCall() {
        val calls = listOf(slot("A", Call.STATE_ACTIVE), slot("B", Call.STATE_RINGING))
        assertEquals("A", CallStateLogic.foreground(calls))
        assertEquals("B", CallStateLogic.ringing(calls))
        assertTrue(CallStateLogic.isCallWaiting(calls))
    }

    @Test
    fun loneRingingCallIsNotCallWaiting() {
        val calls = listOf(slot("B", Call.STATE_RINGING))
        assertEquals("B", CallStateLogic.foreground(calls))
        assertFalse(CallStateLogic.isCallWaiting(calls))
        // A call that already ended doesn't make it "waiting" either.
        assertFalse(CallStateLogic.isCallWaiting(calls + slot("A", Call.STATE_DISCONNECTED)))
    }

    @Test
    fun ringingWhileDialingIsCallWaiting() {
        val calls = listOf(slot("A", Call.STATE_DIALING), slot("B", Call.STATE_RINGING))
        assertEquals("A", CallStateLogic.foreground(calls))
        assertTrue(CallStateLogic.isCallWaiting(calls))
    }

    @Test
    fun answeringWaitingCallPutsFirstOnHold() {
        val calls = listOf(slot("A", Call.STATE_HOLDING), slot("B", Call.STATE_ACTIVE))
        assertEquals("B", CallStateLogic.foreground(calls))
        assertNull(CallStateLogic.ringing(calls))
        assertEquals("A", CallStateLogic.swapTarget(calls, "B"))
    }

    @Test
    fun swapFlipsForegroundAndTarget() {
        val afterSwap = listOf(slot("A", Call.STATE_ACTIVE), slot("B", Call.STATE_HOLDING))
        assertEquals("A", CallStateLogic.foreground(afterSwap))
        assertEquals("B", CallStateLogic.swapTarget(afterSwap, "A"))
    }

    @Test
    fun swapNeedsAnotherHeldCall() {
        assertNull(CallStateLogic.swapTarget(listOf(slot("A", Call.STATE_ACTIVE)), "A"))
        // The current call being held itself isn't a target.
        assertNull(CallStateLogic.swapTarget(listOf(slot("A", Call.STATE_HOLDING)), "A"))
        // A held conference member isn't a top-level call to swap to.
        val withMember = listOf(slot("A", Call.STATE_ACTIVE), slot("m", Call.STATE_HOLDING, topLevel = false))
        assertNull(CallStateLogic.swapTarget(withMember, "A"))
    }

    @Test
    fun endingActiveOfTwoLeavesHeldCallOnScreen() {
        val calls = listOf(slot("A", Call.STATE_HOLDING), slot("B", Call.STATE_DISCONNECTED))
        assertEquals("A", CallStateLogic.foreground(calls))
        assertNull(CallStateLogic.ringing(calls))
    }

    @Test
    fun remoteEndingHeldCallKeepsActiveCall() {
        val calls = listOf(slot("A", Call.STATE_DISCONNECTED), slot("B", Call.STATE_ACTIVE))
        assertEquals("B", CallStateLogic.foreground(calls))
        assertNull(CallStateLogic.swapTarget(calls, "B"))
        assertEquals(listOf("B"), CallStateLogic.live(calls).map { it.call })
    }

    @Test
    fun twoHeldCallsShowTheEarlierOne() {
        val calls = listOf(slot("A", Call.STATE_HOLDING), slot("B", Call.STATE_HOLDING))
        assertEquals("A", CallStateLogic.foreground(calls))
        assertEquals("B", CallStateLogic.swapTarget(calls, "A"))
    }

    @Test
    fun conferenceMembersNeverTakeTheScreen() {
        val calls = listOf(
            slot("conf", Call.STATE_HOLDING),
            slot("m1", Call.STATE_ACTIVE, topLevel = false),
            slot("m2", Call.STATE_RINGING, topLevel = false)
        )
        assertEquals("conf", CallStateLogic.foreground(calls))
        assertNull(CallStateLogic.ringing(calls))
    }

    // ---- Add call ----

    @Test
    fun cancellingAddCallResumesTheOnlyHeldCall() {
        assertEquals("A", CallStateLogic.callToResumeAfterAddCall(listOf(slot("A", Call.STATE_HOLDING))))
        // An ended call next to it doesn't count.
        assertEquals(
            "A",
            CallStateLogic.callToResumeAfterAddCall(listOf(slot("A", Call.STATE_HOLDING), slot("B", Call.STATE_DISCONNECTED)))
        )
    }

    @Test
    fun cancellingAddCallLeavesTwoCallsAlone() {
        assertNull(CallStateLogic.callToResumeAfterAddCall(listOf(slot("A", Call.STATE_HOLDING), slot("B", Call.STATE_DIALING))))
        assertNull(CallStateLogic.callToResumeAfterAddCall(listOf(slot("A", Call.STATE_ACTIVE))))
        assertNull(CallStateLogic.callToResumeAfterAddCall(emptyList<CallSlot<String>>()))
    }

    // ---- Merge / conference capability gating ----

    @Test
    fun mergeOfferedOnlyWhenNetworkAllows() {
        assertTrue(CallStateLogic.canMerge(hasConferenceableCalls = true, canMergeConference = false))
        assertTrue(CallStateLogic.canMerge(hasConferenceableCalls = false, canMergeConference = true))
        assertFalse(CallStateLogic.canMerge(hasConferenceableCalls = false, canMergeConference = false))
    }

    @Test
    fun mergeTwoPlainCallsConferencesWithPartner() {
        assertEquals(
            MergeAction.CONFERENCE_WITH_PARTNER,
            CallStateLogic.mergeAction(isConference = false, canMergeConference = false, hasPartner = true)
        )
        // A partner wins over MERGE_CONFERENCE on a call that isn't a conference yet.
        assertEquals(
            MergeAction.CONFERENCE_WITH_PARTNER,
            CallStateLogic.mergeAction(isConference = false, canMergeConference = true, hasPartner = true)
        )
    }

    @Test
    fun existingConferencePullsTheOtherCallIn() {
        assertEquals(
            MergeAction.MERGE_CONFERENCE,
            CallStateLogic.mergeAction(isConference = true, canMergeConference = true, hasPartner = true)
        )
        assertEquals(
            MergeAction.CONFERENCE_WITH_PARTNER,
            CallStateLogic.mergeAction(isConference = true, canMergeConference = false, hasPartner = true)
        )
    }

    @Test
    fun mergeWithoutCapabilityOrPartnerDoesNothing() {
        assertEquals(MergeAction.NONE, CallStateLogic.mergeAction(isConference = false, canMergeConference = false, hasPartner = false))
        assertEquals(MergeAction.NONE, CallStateLogic.mergeAction(isConference = true, canMergeConference = false, hasPartner = false))
        assertEquals(
            MergeAction.MERGE_CONFERENCE,
            CallStateLogic.mergeAction(isConference = false, canMergeConference = true, hasPartner = false)
        )
    }

    @Test
    fun memberControlsNeedAManageableConference() {
        val unmanaged = CallStateLogic.conferenceControls(manageable = false, canDisconnect = true, canSeparate = true)
        assertFalse(unmanaged.canEnd)
        assertFalse(unmanaged.canSplit)
        val managed = CallStateLogic.conferenceControls(manageable = true, canDisconnect = true, canSeparate = false)
        assertTrue(managed.canEnd)
        assertFalse(managed.canSplit)
    }

    // ---- Proximity lock ----

    @Test
    fun proximityLockOnlyForEarpieceCallsInProgress() {
        assertTrue(CallStateLogic.wantsProximityLock(listOf(Call.STATE_DIALING), anyVideo = false, earpiece = true))
        assertTrue(CallStateLogic.wantsProximityLock(listOf(Call.STATE_ACTIVE), anyVideo = false, earpiece = true))
        assertTrue(CallStateLogic.wantsProximityLock(listOf(Call.STATE_HOLDING), anyVideo = false, earpiece = true))
        assertFalse(CallStateLogic.wantsProximityLock(listOf(Call.STATE_ACTIVE), anyVideo = false, earpiece = false))
        assertFalse(CallStateLogic.wantsProximityLock(listOf(Call.STATE_ACTIVE), anyVideo = true, earpiece = true))
        assertFalse(CallStateLogic.wantsProximityLock(listOf(Call.STATE_DISCONNECTED), anyVideo = false, earpiece = true))
        assertFalse(CallStateLogic.wantsProximityLock(emptyList(), anyVideo = false, earpiece = true))
    }

    @Test
    fun noProximityLockWhileACallWaits() {
        // The answer control must stay reachable while a second call rings.
        assertFalse(
            CallStateLogic.wantsProximityLock(listOf(Call.STATE_ACTIVE, Call.STATE_RINGING), anyVideo = false, earpiece = true)
        )
        assertFalse(CallStateLogic.wantsProximityLock(listOf(Call.STATE_RINGING), anyVideo = false, earpiece = true))
    }

    // ---- CNAP network names ----

    @Test
    fun networkNameAccepted() {
        assertEquals("RAVI KUMAR", CallStateLogic.acceptNetworkName(true, "  RAVI KUMAR ", "+919876543210"))
        assertEquals("रवि कुमार", CallStateLogic.acceptNetworkName(true, "रवि कुमार", "+919876543210"))
        // Digits in a real name are fine.
        assertEquals("Shop 24x7", CallStateLogic.acceptNetworkName(true, "Shop 24x7", "+919876543210"))
    }

    @Test
    fun networkNameRejectedWhenNotAllowedOrBlank() {
        assertNull(CallStateLogic.acceptNetworkName(false, "RAVI KUMAR", "+919876543210"))
        assertNull(CallStateLogic.acceptNetworkName(true, "   ", "+919876543210"))
        assertNull(CallStateLogic.acceptNetworkName(true, null, "+919876543210"))
        assertNull(CallStateLogic.acceptNetworkName(true, "RAVI KUMAR", null))
    }

    @Test
    fun networkNameThatIsJustTheNumberRejected() {
        assertNull(CallStateLogic.acceptNetworkName(true, "+919876543210", "+919876543210"))
        assertNull(CallStateLogic.acceptNetworkName(true, "98765 43210", "9876543210"))
        // The network drops or adds +91 between the two fields.
        assertNull(CallStateLogic.acceptNetworkName(true, "9876543210", "+919876543210"))
        assertNull(CallStateLogic.acceptNetworkName(true, "+91-98765-43210", "09876543210"))
        // "Tel" around the number still isn't a name.
        assertNull(CallStateLogic.acceptNetworkName(true, "Tel 9876543210", "+919876543210"))
    }

    @Test
    fun networkNameWithoutLettersRejected() {
        assertNull(CallStateLogic.acceptNetworkName(true, "121", "121"))
        assertNull(CallStateLogic.acceptNetworkName(true, "0000", "+919876543210"))
        assertNull(CallStateLogic.acceptNetworkName(true, "+-()", "+919876543210"))
    }

    @Test
    fun networkNameForNumberWithoutDigits() {
        // A SIP-style handle has no digits; a real name must still show.
        assertEquals("Alice", CallStateLogic.acceptNetworkName(true, "Alice", "alice@example.com"))
    }

    // ---- Audio routes ----

    @Test
    fun routeNames() {
        assertEquals("Phone", CallStateLogic.routeName(AudioRouteType.EARPIECE, "ignored"))
        assertEquals("Speaker", CallStateLogic.routeName(AudioRouteType.SPEAKER, null))
        assertEquals("Wired headset", CallStateLogic.routeName(AudioRouteType.WIRED_HEADSET, null))
        assertEquals("Galaxy Buds2", CallStateLogic.routeName(AudioRouteType.BLUETOOTH, " Galaxy Buds2 "))
        assertEquals("Bluetooth", CallStateLogic.routeName(AudioRouteType.BLUETOOTH, "  "))
        assertEquals("Bluetooth", CallStateLogic.routeName(AudioRouteType.BLUETOOTH, null))
    }
}
