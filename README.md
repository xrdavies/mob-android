# mob-android

Native Android app (Kotlin, Jetpack Compose) for [mob](https://github.com/xrdavies/mob): dispatch AI agent tasks to your office Mac and monitor the desktop from your Android phone or tablet.

## Requirements

- Android 8.0+ (API level 26+)
- Android Studio with an SDK for API 37 (builds with AGP 9.4, Gradle 9.6 wrapper, Kotlin 2.4, JDK 17+)
- Office Mac running [mobd](https://github.com/xrdavies/mob/tree/main/cmd/mobd) (API v3) with Screen Sharing enabled

## Features

- **Chat with your office agent** (Codex or Claude Code)
- **Task timeline** with live status updates
- **Question cards** when the agent needs your input
- **Live desktop view**: a thumbnail drawer under the Chat top bar (hidden by default, toggled with the monitor button; always view-only) and a full-screen view with a **View / Control** switch (tap = click, long-press = right-click, drag = drag, on-screen keyboard). Uses mobd's `/vnc` relay and signs in to Screen Sharing as the Mac user from Settings → Desktop sign-in; without a sign-in it shows `/screen` snapshots
- **Push notifications** for questions and completed tasks
- **No login required**: paste your office Mac's mobd URL and connect

## Setup

1. Clone: `git clone git@github.com:xrdavies/mob-android.git`
2. Open in Android Studio
3. Sync Gradle and build
4. Run on emulator or device
5. In Settings, enter your office Mac's mobd URL (e.g. `http://192.168.1.100:8080` or via Tailscale; `http://` is added if omitted)

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
- **MVVM**: `ApiClient` → `ChatViewModel` / `TaskViewModel` → Composables
- **Navigation Compose** for bottom nav + drawer

## License

MIT (same as [mob](https://github.com/xrdavies/mob))

---

Part of the **mob** family:
- [mob](https://github.com/xrdavies/mob) — office daemon + desktop app
- [mob-ios](https://github.com/xrdavies/mob-ios) — iOS app
- [mob-android](https://github.com/xrdavies/mob-android) — this repo
