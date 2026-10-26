# Data safety form: draft answers

Play Console → App content → Data safety. These answers match the code as of 26 Oct 2026.

Google's definitions: data is **collected** when it is sent off the device by the app, including to a server the user configured. It is **shared** when it goes to a third party. Exempt from "collection": processing done only on the device, and data the user sends with a specific action they started (for example sharing a recording through the share sheet). Exempt from "sharing": transfers the user starts and expects.

## Section 1: Data collection and security

| Question | Answer | Why |
|---|---|---|
| Does your app collect or share any of the required user data types? | **Yes** | Only because of the optional online number lookup, which sends incoming phone numbers to an endpoint. If you'd rather answer "No", you would need to argue that an endpoint the user configures isn't the developer collecting data. Google doesn't clearly allow that, so the safe answer is Yes. |
| Is all user data collected by your app encrypted in transit? | **Yes** | Cleartext HTTP is blocked (targetSdk ≥ 28, no network security config), so Retrofit can only reach `https://` endpoints. |
| Do you provide a way for users to request that their data is deleted? | **Yes** | Everything is local: delete in the app or uninstall. The lookup endpoint is the user's. Explain this on the deletion page or in the policy. |

> Play also asks for an **account deletion URL** only if the app has accounts. NoDialer has no accounts, so that question doesn't apply.

## Section 2: Data types

Declare only:

**Calls and messages → Call logs**
- Collected: **Yes**. Shared: **No**.
- Processed ephemerally? **Yes**: the number is sent, a name or spam flag comes back, and the endpoint isn't ours. Tick "ephemeral" only if you're comfortable that the endpoint, as used by this app, doesn't store data. Since the user picks the endpoint, ticking it is defensible but not guaranteed. **Recommendation: leave it unticked.**
- Required or optional: **Optional** (the user turns online lookup on).
- Purposes: **App functionality** (caller identification and spam blocking). Also **Fraud prevention, security, and compliance** if you want to cover spam blocking.

Do **not** declare these: the data stays on the device or is never accessed.

| Type | Why not |
|---|---|
| Personal info (name, email, phone number of the user) | The user's own number is never sent. |
| Contacts | Read on the device only. |
| Audio → Voice or sound recordings | Recordings stay in app storage. Sharing is something the user starts (exempt). Voicemail transcription runs on the device (Android's on-device recogniser), so nothing is uploaded. |
| Photos and videos | The camera feed goes over the carrier IMS video call, not through the app's servers. |
| App activity, App info and performance (crash logs, diagnostics) | No analytics or crash SDK. |
| Device or other IDs | No advertising ID, no Firebase. |
| Location, Financial, Health, Messages, Files and docs, Calendar, Web browsing | Not accessed. |

## Section 3: Security practices shown on the listing

- Data is encrypted in transit ✔
- You can request that data be deleted ✔
- Independent security review: **No** (leave unticked)
- Committed to Play Families policy: not applicable (not a Families app)

## AI voicemail (checked 26 Oct)

Nothing to add. Transcription uses Android's on-device speech recogniser (Android 13+), and the summary is rule-based code in the app. No network calls, no API key, nothing stored on a server. Transcripts stay in private prefs. If this ever moves to a cloud model, add **Audio → Voice or sound recordings** (Collected, Optional, App functionality) and name the provider in the policy.
