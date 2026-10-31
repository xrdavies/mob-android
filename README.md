# mob-android

Mob for Android (Kotlin, Jetpack Compose). **Mob** is mob programming with your AI agents: they work on your office computer (via [mobd](https://github.com/xrdavies/mobd)), you steer from your Android phone or tablet: send tasks, answer their questions, watch and control the live desktop. The name comes from [mob programming](https://en.wikipedia.org/wiki/Mob_programming), where a team works on one computer together: one at the keyboard, the others steering. In Mob the team is you and your agents.

## Requirements

- Android 8.0+ (API level 26+)
- Android Studio with an SDK for API 37 (builds with AGP 9.4, Gradle 9.6 wrapper, Kotlin 2.4, JDK 17+)
- An office computer (macOS or Linux) running [mobd](https://github.com/xrdavies/mobd) (API v3) with the `stream` feature

## Features

- **Files**: upload local files and download from the machine’s shared directory, with pause/continue, persistent partial transfers and SHA-256 verification. Choose local files and save locations with the system picker. [Transfer details](docs/file-transfers.md).
- **Several office computers**: add, edit and switch machines (Settings → Machines, or the switcher at the top of the chat list); each has its own chats, pairing and live view quality
- **Pairing**: scan or paste the link from `mobd link`; the device credential is stored encrypted with the Android Keystore and sent as `Authorization: Bearer`. When mobd no longer accepts it (revoked, auth reset) the machine shows **Pair again**
- **Chats**: chat list drawer with New chat, Pinned / Recent / Archived, and rename, pin, archive, delete per chat
- **Chat with your office agent** (Codex or Claude Code)
- **Composer**: attach up to 10 images (re-encoded to JPEG when the phone's format is not accepted), pick the agent for a new chat and its model / reasoning effort (remembered per machine and agent), **Stop** a running turn, add a message to a running Codex turn (steer), queue position while waiting for the desktop
- **Timeline** like the desktop app: Markdown replies (copyable), agent steps (commands, tools, reasoning, plans, file changes, screenshots) folded into groups and expandable for details, per-turn footer with duration, tokens, cost, failure and Retry
- **Question and approval cards** in the chat when the agent needs your input; settled ones show the answer
- **Live updates**: each machine's `/events` stream keeps the chat list current, and the open chat follows its running turn over `/tasks/{id}/events` (polling only as a fallback)
- **Notifications** when the agent asks something or a task finishes or fails, for every machine in the list; tap to open the chat. Optional background watching (Settings → Notifications) keeps the streams open with a silent foreground-service notification, since there is no push service on an intranet
- **Setup warnings**: mobd's readiness checks (Accessibility, Screen Recording, display, agent CLIs) show in the chat header and Settings
- **Live desktop**: see below

## Live desktop

System audio is not implemented. The [remote audio draft](docs/desktop-audio.md)
covers both transports, Android playback/focus and audio/video synchronization.

A thumbnail drawer under the Chat top bar (hidden by default, toggled with the monitor button; always view-only) and a full-screen view with a **View / Control** switch: tap = click, long-press = right-click, drag = drag, two fingers = scroll, pinch = zoom; a keys bar adds ⌘ ⌥ ⌃ ⇧ (latched for the next key), Esc, Tab, arrows and typing the phone's clipboard. Control is released when the app stops.

- **WebRTC**: `POST /stream` with a non-trickle offer; receive-only H.264 through libwebrtc, drawn in a `TextureView`; input on the `input` / `pointer` / `control` data channels, with points normalized to the desktop inside the letterbox. Protocol: [mobd desktop streaming](https://github.com/xrdavies/mobd/blob/main/docs/desktop-streaming.md).
- **WebSocket fallback**: if WebRTC has not connected after 5 s (or fails sooner), the same session continues on `/stream/ws` through the OkHttp client (Bearer header). `MediaCodec` decodes into the same renderers; a decoder that fails while running is skipped for the process (the emulator's hardware H.264 decoder does). Used where UDP cannot pass (Tailscale Funnel, HTTPS gateways). A machine that needed it remembers that (`liveWebSocket`): next time WebRTC gets 1 s, then the WebSocket races beside it and the first frame wins.
- **Keyframe requests**: some decoders (the emulator's software one, and some phones') hold a picture until more input arrives, and an idle desktop sends nothing after its keyframe. Until the first frame comes out of the decoder, the app asks mobd for a keyframe every 0.7 s.
- **Quality** Auto / High / Low per machine, from the view or Settings; applies at once (an open view reconnects). View size changes are sent as `resize` without reconnecting. A readout shows size, fps, kB/s, transport and network (LAN, Tailscale, Internet, relay) with signal bars.
- **Snapshots**: when the live view is off, `/screen` at the view width in pixels, quality 65, with `If-None-Match` so an unchanged screen costs a 304; mobd's reason is shown when the screen is unavailable.
- mobd without the `stream` feature shows "Update mobd".

libwebrtc adds about 6.5–15 MB per ABI; release builds ship only `arm64-v8a` and `armeabi-v7a`.

Not yet checked: a real phone by hand (clicks, typing, gestures, hardware decoders), a throttled link, the real Funnel path and a phone off Wi-Fi.

## Setup

1. Clone: `git clone git@github.com:xrdavies/mob-android.git`
2. Open in Android Studio, sync Gradle and build
3. Run on emulator or device
4. On the office computer run `mobd link`; in the app, Settings → Machines → Add machine, scan the QR code or paste the link, then Pair

From the command line (Android Studio's bundled JDK works):

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
~/Library/Android/sdk/emulator/emulator -avd <avd-name> &   # list with: emulator -list-avds
./gradlew installDebug
adb shell am start -n com.xrdavies.mob/.MainActivity
```

`make` lists the tasks: `make test` (unit tests), `make apk` (debug APK), `make version`, `make release-patch` (`-minor`, `-major`), `make release-test`.

`LiveStreamTest` (instrumented) opens a real live view and is skipped unless given a paired credential; it only moves the office pointer. Run it without `connectedAndroidTest`, which uninstalls the app and its pairing:

```bash
./gradlew installDebug installDebugAndroidTest
adb shell am instrument -w -e mobdUrl … -e mobdToken … \
  -e class com.xrdavies.mob.stream.LiveStreamTest com.xrdavies.mob.test/androidx.test.runner.AndroidJUnitRunner
```

## Releases

Signed APKs are published to `https://dl.mymob.dev/android/` and offered on the mob website.
Run `make release-patch` (or `release-minor`, `release-major`, or `make release VERSION=vX.Y.Z`): it bumps the version, asks for confirmation, commits it, tags that commit and pushes both; see [docs/releases.md](docs/releases.md) for the release runbook (keystore and CI setup, checklist, rollback, and Google Play, which is not set up yet).

## Architecture

- **Jetpack Compose** for all UI
- **Kotlin Coroutines** + **Flow** for async/reactive state
- **Retrofit** + **OkHttp** for HTTP + Server-Sent Events
- **Live desktop** (`stream/`): `StreamSession` over libwebrtc (`io.github.webrtc-sdk:android`, receive-only H.264 and data channels), `WsPlayer` (WebSocket fallback decoded by `MediaCodec` into the same renderers), and the pure protocol code in `StreamProtocol`
- **State**: `AppState` (machines, health, live view) owns one `ChatStore` (chat list and the open conversation) for the active machine; `MobApplication` holds the one `AppState`
- **Navigation Compose** for the bottom bar; a modal drawer for the chat list

## License

MIT (same as [mob](https://github.com/xrdavies/mob))

---

Part of the **mob** family:
- [mob](https://github.com/xrdavies/mob) — macOS desktop app
- [mobd](https://github.com/xrdavies/mobd) — office daemon
- [mob-ios](https://github.com/xrdavies/mob-ios) — iOS app
- [mob-android](https://github.com/xrdavies/mob-android) — this repo
