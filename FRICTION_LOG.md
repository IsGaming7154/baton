# Friction Log — Baton

A running record of where the platform, the SDKs, the documentation, or the tooling got in the way while building Baton for Fire TV.

Kept from the first commit rather than reconstructed at the end. Each entry records what was attempted, what happened, and what the workaround was.

Environment: Windows 11, Fire TV Stick HD (Fire OS 7 / Android 9 / API 28), targeting the Amazon Developer Hackathon Fire TV track.

---

## 1. Fire OS has no hierarchy of auditory focus

**Severity:** high — this is the project's thesis
**Area:** Fire OS accessibility platform

**What was attempted.** Establish which component decides who speaks when audio description, spoken subtitles, and the VoiceView screen reader are all active over content that has its own dialogue.

**What happened.** Nothing decides. There is no arbitration layer and no API through which one service can learn that another is speaking. All of them speak simultaneously. For a blind viewer watching foreign-language content — the case where all three services are genuinely needed at once — the result is three overlapping voices plus the original dialogue.

**Workaround.** Amazon's own documented advice is to disable VoiceView. That restores intelligibility at the cost of the user's ability to operate the television. There is no workaround that preserves both.

**Feature request.** Fire OS should expose an **acoustic priority API**: a system-level service through which any component that intends to speak declares an utterance with a priority class, and receives back either a grant or a deferral. Minimum viable surface:

- Register an utterance with a class (`user_initiated`, `time_critical`, `ambient`) and an expected duration
- Receive a callback when the floor is granted, and when it is revoked by a higher-priority claim
- Observe whether the foreground media session's content is currently in dialogue
- A system-owned ducking policy so every participant attenuates content identically

Without this, every application that wants to be accessible must reimplement arbitration privately, and cannot arbitrate against VoiceView at all — because VoiceView is outside the app's process and exposes no such signal.

Baton implements this arbitration inside a single application as a reference for what the platform API should look like.

---

## 2. Third-party applications cannot access the microphone

**Severity:** high
**Area:** Fire TV input APIs

**What was attempted.** Add a spoken "ask about what is on screen" interaction, so a blind user could ask a question without navigating a menu.

**What happened.** Three separate dead ends, none of them documented together in one place:

- The microphone button on the Alexa Voice Remote routes directly to Alexa's cloud service. It is not delivered to the foreground application as an input event.
- Fire OS ships no Google Play Services, so the platform `SpeechRecognizer` API is unavailable. Code written against the standard Android speech APIs compiles and then fails at runtime.
- `AudioRecord` with `RECORD_AUDIO` granted appears to succeed and returns silent buffers, which is worse than failing, because it looks like a bug in your own code.

**Workaround.** Abandon voice input entirely and bind the interaction to a remote button. This turned out better for the use case — no wake word, no recognition latency, and it works with the television screen off — but arriving there took three failed approaches.

**Documentation gap.** The voice-enablement documentation describes what an app *can* do with Alexa integration. It does not state plainly that direct microphone capture is unavailable to third-party apps. A single sentence saying so, on the audio input page, would have saved the entire investigation.

---

## 3. Audio description becomes stuck on across all content

**Severity:** medium
**Area:** Fire OS accessibility settings

**What was observed.** A recurring user report: once audio description is enabled, it stays enabled for all content indefinitely, including content where the user never requested it. The support-recommended remedy is a factory reset.

**Why it matters.** The cost of enabling an accessibility feature should never be a factory reset. This defect teaches users not to try accessibility features at all, which is the opposite of the intent.

**How Baton avoids it.** Description enablement is stored per title, with the global default off. Enabling description on one title does not enable it anywhere else, and a title's description can be muted without disabling the feature globally.

---

## 4. Amazon's sample repositories are not under the organisation the docs imply

**Severity:** low, but costs time
**Area:** documentation and discovery

**What was attempted.** Fetch `react-native-multi-tv-app-sample`, referenced from the hackathon resources page.

**What happened.** `amzn/react-native-multi-tv-app-sample` returns "repository not found." The samples live under `AmazonAppDev`. Amazon publishes under several GitHub organisations — `amzn`, `AmazonAppDev`, `amazon-archives` — and the resources page links to repositories without making the owning organisation obvious at a glance.

**Workaround.** Search for the repository name rather than trusting a constructed path.

---

## 5. The Agent Skills ecosystem for these devices is nearly empty

**Severity:** low — this is an opportunity, not a defect
**Area:** developer tooling

**What was observed.** `AmazonAppDev/devices-agent-skills` currently contains two skills: React Native TV UI best practices, and Vega multi-TV migration. Nothing covers Fire TV accessibility, Fire OS platform constraints, or building for older API levels on memory-constrained devices.

For a hackathon that explicitly rewards open-source contribution, this is the most obviously useful place to contribute, and the contributing guide is already open.

---

## 6. Devpost blocks non-browser user agents

**Severity:** low
**Area:** hackathon tooling

**What was attempted.** Read the hackathon rules and resources pages programmatically.

**What happened.** Every path returned `HTTP 403 Forbidden` to a plain HTTP client, regardless of path.

**Workaround.** Drive a real browser and read the rendered text out of the DOM.

---

## 7. Windows toolchain: nothing needed for Fire TV development is present by default

**Severity:** medium
**Area:** getting started

**What was attempted.** Build an Android application for Fire TV on a clean Windows 11 machine.

**What happened.** A sequence of small obstacles, each individually trivial and collectively an afternoon:

- A JDK 21 installation was present and `JAVA_HOME` pointed at it, but `java` on `PATH` resolved to **Java 8**. The Android Gradle Plugin requires 17 or newer, and the resulting failure message does not point at the `PATH`/`JAVA_HOME` mismatch.
- No Android SDK, no `adb`, no `gradle`. `adb` is what you need to put anything on a Fire TV Stick at all, and it arrives only as part of the SDK's platform-tools.
- The command-line-tools download URL embeds a build number that changes with every release. Every build number found in documentation, blog posts, and older tutorials still returns HTTP 200 and installs an outdated toolchain, so there is no error to tell you that you have the wrong one. The only reliable source is scraping the current filename off `developer.android.com/studio`.

**Workaround.** Install command-line tools only rather than the full Android Studio — roughly 150 MB against roughly 1 GB, no GUI, and scriptable. Scrape the current build number rather than hardcoding one.

**Documentation gap.** Amazon's Fire TV getting-started material assumes Android Studio is already installed and configured. For a developer arriving from outside the Android ecosystem — which a hackathon will attract — there is no lean, scriptable path documented.

---

*Entries are appended as they are encountered. This log is a deliverable, not a postscript.*
