# Play Console declarations: draft text

All of these are under **Play Console → Policy and programs → App content**. Fix blockers #2 and #3 in `../play-store-readiness.md` first, or the Call Log declaration is likely to be rejected.

---

## 1. Call Log permissions (READ_CALL_LOG, WRITE_CALL_LOG)

**Core functionality:** Default Phone handler (dialer)

**Is the app the default handler?** Yes. The app asks the user to make it the default Phone app through `RoleManager.ROLE_DIALER` (Android 10+) or `ACTION_CHANGE_DEFAULT_DIALER` (Android 8–9), and implements `InCallService` with the in-call UI.

**Description of core functionality (paste):**

> NoDialer is a full replacement for the Phone app. Once the user makes it the default Phone app, it places and answers calls (InCallService), shows the incoming-call and in-call screens, and screens and blocks unwanted calls (CallScreeningService). READ_CALL_LOG is used to show the call history in the Recents tab (grouped by day, filtered by missed/incoming/outgoing/blocked), to suggest recent numbers on the keypad, to show recent calls in the home-screen widget, and to show and clear missed-call notifications, which Android hands to the default dialer. WRITE_CALL_LOG is used to mark missed calls as read and to delete calls the user chooses to delete. Call log data never leaves the device and is not used for ads or analytics. The permissions are only requested after the user has made NoDialer the default Phone app, and the call history isn't read while it isn't the default.

**Video (optional for this form, but helps):** screen recording: install → "Set as default" → Recents shows calls → expand a call → delete it.

---

## 2. Foreground service types

### `microphone` (CallRecordingService)

- **Feature:** Automatic call recording (optional, off by default).
- **Description (paste):** "When the user turns on Auto call recording in Settings, NoDialer records phone calls with the device microphone. Recording runs in a foreground service with an ongoing notification while a call is active and stops when the call ends. The recording is saved on the device."
- **User impact if deferred or interrupted:** "The call would not be recorded, or the recording would be cut off. Calls can't be paused or resumed later, so the audio would be lost."
- **Video:** Settings → turn on Auto call recording → make a call → show the "Recording call…" notification and the REC badge → end the call → open Settings → Call recordings → play it.

### `phoneCall` (CallService and CallRecordingService)

- **Description (paste):** "NoDialer is the device's default Phone app (ROLE_DIALER). While a cellular call is active, its InCallService runs as a foreground service that shows the ongoing-call notification (CallStyle, the status-bar call chip), so the user can return to the call, mute or hang up from anywhere. If the user has turned on call recording, the recording service shares this notification and stops when the call ends. The service runs only while a call is active."
- **User impact if deferred or interrupted:** "The user would lose the ongoing-call controls during a live call, and Android could stop the call screen in the background. Calls are real-time and can't be delayed."
- **Video:** place or answer a call → press Home → show the status-bar call chip and the notification → tap it to return to the call → hang up.

---

## 3. Full-screen intent (USE_FULL_SCREEN_INTENT)

- **Does your app's core functionality include:** ✅ **Receiving calls** (phone or video calls).
- Note: NoDialer uses the full-screen intent only for the incoming-call notification, so the ringing screen shows when the device is locked or the screen is off.

---

## 4. Other App content answers

| Item | Answer |
|---|---|
| Privacy policy | Public URL of `privacy-policy.html` (**the user decides where to host it**) |
| Ads | **No**, the app contains no ads |
| App access | "All or some functionality is restricted". Instructions: "No login. To use the app, open it and tap **Set as default**, then choose NoDialer as the default Phone app. Call recording is off by default (Settings → Auto call recording). Online number lookup needs an HTTPS endpoint and is optional, so leave it off when testing." |
| Content rating | See `content-rating-and-audience.md` |
| Target audience | 18+ (see that file) |
| News app | No |
| COVID-19 contact tracing | No |
| Data safety | See `data-safety.md` |
| Government app | No |
| Financial features | None |
| Health | None |
| Advertising ID | **No**, the app doesn't use the advertising ID |

---

## Prominent disclosure for call recording

Show this in a dialog when the user turns on **Auto call recording**, before asking for RECORD_AUDIO (code change: readiness fix #3). Add hi/mr strings in the usual `strings.xml` files.

> **Record your calls?**
> NoDialer will use the microphone to record connected calls and save them on this phone only. Recordings are never uploaded. On most phones only your side is recorded unless you use the speaker.
> Recording laws differ by place: you may need to tell the other person or get their consent.
> [Not now] [Allow microphone]

The online number lookup already explains itself in its setting ("Sends unknown numbers to a lookup endpoint you configure"). Before the first lookup is enabled, also show a one-time confirmation:

> **Send unknown numbers online?**
> When a number that isn't in your contacts calls you, NoDialer will send that number to the address you enter, to look up a name or spam warning. Nothing else is sent. You can turn this off at any time.
> [Cancel] [Turn on]
