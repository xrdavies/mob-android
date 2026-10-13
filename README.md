# mob-android

Native Android app (Kotlin, Jetpack Compose) for [mob](https://github.com/xrdavies/mob): dispatch AI agent tasks to your office Mac and monitor the desktop from your Android phone or tablet.

## Requirements

- Android 8.0+ (API level 26+)
- Android Studio Hedgehog or later
- Office Mac running [mobd](https://github.com/xrdavies/mob/tree/main/cmd/mobd) (API v3) with Screen Sharing enabled

## Features

- **Chat with your office agent** (Codex or Claude Code)
- **Task timeline** with live status updates
- **Question cards** when the agent needs your input
- **Live desktop view** (optional drawer; VNC over WebSocket)
- **Push notifications** for questions and completed tasks
- **No login required**: paste your office Mac's mobd URL and connect

## Setup

1. Clone: `git clone git@github.com:xrdavies/mob-android.git`
2. Open in Android Studio
3. Sync Gradle and build
4. Run on emulator or device
5. In Settings, enter your office Mac's mobd URL (e.g. `http://192.168.1.100:8080` or via Tailscale)

## Architecture

- **Jetpack Compose** for all UI
- **Kotlin Coroutines** + **Flow** for async/reactive state
- **Retrofit** + **OkHttp** for HTTP + Server-Sent Events
- **OkHttp WebSocket** for `/vnc` connection
- **MVVM**: `ApiClient` → `ChatViewModel` / `TaskViewModel` → Composables
- **Navigation Compose** for bottom nav + drawer

## License

MIT (same as [mob](https://github.com/xrdavies/mob))

---

Part of the **mob** family:
- [mob](https://github.com/xrdavies/mob) — office daemon + desktop app
- [mob-ios](https://github.com/xrdavies/mob-ios) — iOS app
- [mob-android](https://github.com/xrdavies/mob-android) — this repo
