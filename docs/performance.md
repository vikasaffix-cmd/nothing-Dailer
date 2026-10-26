# Performance and size

Measured 26 Oct 2026 on the Nothing A069P (Android 17), with about 9,200 contacts
(9,237 numbers) and 1,016 call-log entries, on release builds of NoDialer
(`com.maihuvikas.dialer`).

## Before / after

There is no clean "before" run on the phone: the build that would have been
the baseline crash-looped (a missing WorkManager/Room keep rule) and had to be
replaced right away. The "before" column therefore uses the figures recorded
earlier the same day (Recents notes) and the APK sizes. The "after" build (14:58)
also has other sessions' new features in it.

| Metric | Before | After | How |
|---|---|---|---|
| APK size | 4,841,778 B (14:12 build) | 4,919,916 B (14:58 build, more features) | file size |
| ↳ libphonenumber place names | 1,535 KB | 1,083 KB | compressed entries |
| ↳ libphonenumber carriers + time zones | 108 KB | 90 KB | compressed entries |
| ↳ resources.arsc | 700 KB (90 locales) | 556 KB (en, hi, mr) | compressed entries |
| ↳ classes.dex | 1,561 KB | 1,870 KB (new features) | compressed entries |
| Full call log load (Recents, keypad) | 800–1,200 ms | 613–640 ms | `CallLogRepository` log line |
| Contacts load (9,237 numbers) | read again by each screen and on every resume | 134–150 ms, once, then from memory | `ContactsRepository` log line |
| Keypad: call log + contacts | one after the other | side by side | code |
| Cold start | not measured | 201–357 ms (median 253) | `am start -W`, 5 runs, TotalTime |
| Warm start | not measured | 46–90 ms (median 65) | `am start -W` after HOME, 5 runs |
| Memory | not measured | 45 MB PSS (Java 11, native 14, graphics 8) | `dumpsys meminfo` after the start runs |
| Incoming call: ringing → our call notification | not measured | 115 ms (15:00:33.620 → .735) | Telecom / `notification_enqueue` in logcat |
| Incoming call: spam screening | not measured | 22 ms | Telecom SCREENING_BOUND → COMPLETED |

The trims took about 614 KB out of the APK. New features added at the same time
(Glance widget layouts, backup, emergency calling, etc.) put more than that back
in, which is why the total still went up.

Not measured: dropped frames while scrolling Contacts and Recents (`gfxinfo`),
and time to first content per screen. Right after the start runs, a real call
came in, and afterwards the phone was locked, and these runs need an unlocked
screen. To do them later:

    adb shell dumpsys gfxinfo com.maihuvikas.dialer reset
    (fling the list for ~10 s with adb shell input swipe 630 2200 630 600 150)
    adb shell dumpsys gfxinfo com.maihuvikas.dialer | grep -E "Total frames|Janky|percentile"

Unit tests on the 14:58 build: 163 tests in 16 classes, 0 failures; assembleRelease OK.

## What changed

Behaviour is the same unless noted.

- **One contacts cache** (`ContactsRepository`): every screen (keypad, Contacts,
  speed dial, widget) shares one in-memory copy, dropped by a `ContentObserver`
  as soon as anything in the contacts provider changes. Contacts opens from it on
  the first frame instead of an empty list, and the reload on resume is instant
  when nothing changed.
- **Recents lookups**: unknown numbers are checked against that cache
  (`ContactsRepository.cachedByKey()`) instead of one `PhoneLookup` query each.
  Numbers shorter than 10 digits still use the query. The phone's country
  (`NumberInfo.defaultRegion`) is read once a minute instead of twice per
  unknown number.
- **Recents recomposition**: the grouped list's `initialValue` was rebuilt
  (all 1,016 calls) on the main thread at every recomposition; it is now built
  once.
- **Keypad**: call log and contacts load in parallel; the T9 index is kept
  for as long as the contacts list is current; T9 search runs on
  `Dispatchers.Default`; "is this a saved number" is a set lookup instead of a
  scan of 9,200 numbers on every recomposition; suggestion rows are keyed.
- **Contacts search**: filtering and grouping run off the main thread.
- **Photos** (`rememberContactPhoto`): decoded with `inSampleSize` at the shown
  size and kept in an LRU cache (1/16 of the heap), so photos scrolled back
  into view show on the first frame. The incoming-call backdrop asks for 480 dp,
  so it keeps full resolution. The cache is cleared on any contacts change.
- **Display font** (`DotMatrixText`): the custom-font file is checked once
  per import instead of once per text on screen, and the fitted text size is
  remembered instead of measured again on each recomposition.
- **Recordings**: the saved JSON list is parsed once per change instead of on
  every call-log load.
- **Baseline profile**: `app/src/main/baseline-prof.txt` lists the app's own
  code. `profileinstaller` is already included through Compose, and the
  library profiles are merged in (`assets/dexopt/baseline.prof` went from
  7.5 KB to 9.2 KB).
- **Size** (`app/build.gradle.kts`):
  - `androidResources.localeFilters` = en, hi, mr. *Visible change:* on a phone
    set to a language other than these three, the few library strings show in
    English instead of that language.
  - The non-English libphonenumber geocoder and carrier tables are excluded.
    `NumberInfo` always asks for English, so they were never read. The unused
    time-zone data and `DebugProbesKt.bin` are excluded too. All
    `PhoneNumberMetadataProto_*` files and every `*_en` table, including 91_en,
    are kept.

## Further options (not done)

- Dropping the English place-name tables for other countries (86_en 379 KB,
  61_en 184 KB, 55_en 77 KB …) would save about 1 MB more, but calls from those
  countries would then show only the country name instead of the city.
- The widget reloads the full call log (with lookups) for its few recent rows
  each time it refreshes. It does this on purpose, so Recents opens instantly.
- An .aab upload to Play would split the ABIs (37 KB of native libs) and
  densities for each device.
