# mob-android

Native Android app (Kotlin, Jetpack Compose) for [mob](https://github.com/xrdavies/mob): dispatch AI agent tasks to your office Mac and monitor the desktop from your Android phone or tablet.

## Requirements

- Android 8.0+ (API level 26+)
- Android Studio with an SDK for API 37 (builds with AGP 9.4, Gradle 9.6 wrapper, Kotlin 2.4, JDK 17+)
- Office Mac running [mobd](https://github.com/xrdavies/mob/tree/main/cmd/mobd) (API v3) with Screen Sharing enabled

## Features

- **Several office Macs**: add, edit and switch machines (Settings → Machines, or the switcher at the top of the chat list); each has its own chats and desktop sign-in
- **Chats**: chat list drawer with New chat, Pinned / Recent / Archived, and rename, pin, archive, delete per chat
- **Chat with your office agent** (Codex or Claude Code)
- **Composer**: attach up to 10 images (re-encoded to JPEG when the phone's format is not accepted), pick the agent for a new chat and its model / reasoning effort (remembered per machine and agent), **Stop** a running turn, add a message to a running Codex turn (steer), queue position while waiting for the desktop
- **Timeline** like the desktop app: Markdown replies (copyable), agent steps (commands, tools, reasoning, plans, file changes, screenshots) folded into groups and expandable for details, per-turn footer with duration, tokens, cost, failure and Retry
- **Question and approval cards** in the chat when the agent needs your input; settled ones show the answer
- **Live desktop view**: a thumbnail drawer under the Chat top bar (hidden by default, toggled with the monitor button; always view-only) and a full-screen view with a **View / Control** switch (tap = click, long-press = right-click, drag = drag, on-screen keyboard). Uses mobd's `/vnc` relay and signs in to Screen Sharing as the Mac user from Settings → Desktop sign-in; without a sign-in it shows `/screen` snapshots
- **Live updates**: each machine's `/events` stream keeps the chat list current, and the open chat follows its running turn over `/tasks/{id}/events` (polling only as a fallback)
- **Notifications** when the agent asks something or a task finishes or fails, for every machine in the list; tap to open the chat. Optional background watching (Settings → Notifications) keeps the streams open with a silent foreground-service notification, since there is no push service on an intranet
- **Setup warnings**: mobd's readiness checks (Accessibility, Screen Recording, Screen Sharing, agent CLIs) show in the chat header and Settings
- **No login required**: paste your office Mac's mobd URL and connect

## Setup

1. Clone: `git clone git@github.com:xrdavies/mob-android.git`
2. Open in Android Studio
3. Sync Gradle and build
4. Run on emulator or device
5. In Settings → Machines, add your office Mac's mobd URL (e.g. `http://192.168.1.100:8080` or via Tailscale; `http://` is added if omitted)

From the command line (Android Studio's bundled JDK works):

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
~/Library/Android/sdk/emulator/emulator -avd <avd-name> &   # list with: emulator -list-avds
./gradlew installDebug
adb shell am start -n com.xrdavies.mob/.MainActivity
```

## Architecture

- **Jetpack Compose** for all UI
- **Kotlin Coroutines** + **Flow** for async/reactive state
- **Retrofit** + **OkHttp** for HTTP + Server-Sent Events
- **Native VNC client** (`vnc/`): RFB 3.8 over an OkHttp WebSocket to `/vnc`, Apple Remote Desktop sign-in (type 30), ZRLE / CopyRect / Raw; ported from [svnc](https://github.com/xrdavies/svnc)
- **State**: `AppState` (machines, health, live view) owns one `ChatStore` (chat list and the open conversation) for the active machine; both live in `MobApplication`
- **Navigation Compose** for the bottom bar; a modal drawer for the chat list

## License

MIT (same as [mob](https://github.com/xrdavies/mob))

---

Part of the **mob** family:
- [mob](https://github.com/xrdavies/mob) — office daemon + desktop app
- [mob-ios](https://github.com/xrdavies/mob-ios) — iOS app
- [mob-android](https://github.com/xrdavies/mob-android) — this repo
