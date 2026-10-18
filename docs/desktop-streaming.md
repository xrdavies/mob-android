# Live desktop over WebRTC: Android plan

Status: **draft**, branch `feat/webrtc-stream`. Design and protocol: [mobd desktop streaming](https://github.com/xrdavies/mobd/blob/feat/webrtc-stream/docs/desktop-streaming.md).

## Why

The live view is a Kotlin RFB client (`vnc/`) receiving the office Mac's full 4K framebuffer over
mobd's `/vnc`. mobd now streams H.264 at the size the client shows, over WebRTC (Pion) with a
WebSocket fallback. VNC is removed; mobd and the apps are released together.

## What changes here

| Area | Today | After |
| --- | --- | --- |
| Live view | `vnc/VncSession.kt`, `Rfb.kt`, `Framebuffer`, `Ard.kt`, `VncAuth.kt` | `stream/StreamSession.kt` with the native WebRTC SDK (libwebrtc build); `SurfaceViewRenderer` |
| Control | RFB pointer/key messages | data channels `input` / `pointer` / `control`; same gestures and keys bar (`ui/desktop/Desktop.kt`) |
| Fallback | none | `/stream/ws` through the OkHttp client (auth interceptor), decoded with MediaCodec |
| Snapshot | 1600 px every 2 s | view width × density, quality ≈ 65, `ETag` |
| Settings | Mac user name + password (SecretStore) | removed; **Live view quality: Auto / High / Low** |
| Data | `Machine.vncUsername`, secret `<id>.vncPassword` | removed on load |

Open choice in A2: which WebRTC artifact (a maintained libwebrtc build on Maven Central); check APK
size per ABI and license notices.

## Steps

| Step | Content | Verified by |
| --- | --- | --- |
| A1 | **Done.** Snapshot at the view width in pixels (rounded up to 64), quality 65, `If-None-Match` so an unchanged screen costs a 304; the label shows mobd's reason (locked, asleep, no Screen Recording) | emulator |
| A2 | **Done.** `stream/StreamSession.kt` with `io.github.webrtc-sdk:android` 150.7871.01: recvonly H.264 (`DefaultVideoDecoderFactory`, software fallback), non-trickle `POST /stream`, `control`/`input`/`pointer` channels, a TextureView renderer per view; VNC stays for mobd without `stream`; mobd's `ended`/errors shown. While no frame has come out of the decoder, a `keyframe` is requested every 0.7 s (see below) | `StreamProtocolTest`, `LiveStreamTest` (emulator vs office mobd), app on the emulator |
| A3 | **Done.** Tap = click, long-press = right-click, one-finger drag, two-finger scroll in desktop pixels (or pinch to zoom); keys bar with latched modifiers and the keyboard field; points normalized inside the letterbox; held input released on View and when the app stops | `LiveStreamTest` pointer moves; clicks and typing by hand on a phone |
| A4 | **Done.** Auto / High / Low in the live view (menu) and Settings, both applying at once and reconnecting with control kept; `resize` with mobd's `streamResize`; readout (size, fps, kB/s, transport, network) with signal bars | `LiveStreamTest`; app on the emulator; throttled link still to do |
| A5 | WebSocket fallback (MediaCodec) | Funnel address |
| A6 | Remove `vnc/` code and tests, Mac sign-in fields | build |
| A7 | Tests: signaling, message encoding, transport choice, migration | `./gradlew testDebugUnitTest` |

Unblocked: the desktop app confirmed the protocol (D2) and built steps 3–5. A5 uses the WebSocket
protocol as revised in mobd M7b (540p start, `resize`, try the WebSocket first where it was needed).
A6 happens together with mobd M8 and desktop D6. Order across repos: see mobd's plan, "Order".

Follow the desktop design where it applies: the inline view only watches and the large view controls,
with a View/Control switch; quality switched from the view as well as Settings; a readout of size, fps,
kB/s, transport and network with signal bars; WebRTC gets a 5 s head start before the WebSocket.

### A1–A4 check (2026-10-19)

`LiveStreamTest` (instrumented, opt-in) on the emulator (Medium Phone, API 37) against the office
mobd (`7a06a64`), with a temporary client revoked afterwards. It only moves the office pointer. Run it
without Gradle's `connectedAndroidTest`, which uninstalls the app and its pairing:
`./gradlew installDebug installDebugAndroidTest`, then
`adb shell am instrument -w -e mobdUrl … -e mobdToken … -e class com.xrdavies.mob.stream.LiveStreamTest com.xrdavies.mob.test/androidx.test.runner.AndroidJUnitRunner`.

| Check | Result |
| --- | --- |
| First picture (LAN, WebRTC) | 13.5 s at first; 1.6–2.6 s with the keyframe requests (3 runs) |
| Move while in View | not sent |
| Control, move to (0.25, 0.25) then (0.75, 0.6) | mobd's `cursor` reports both exactly |
| `resize` to 640 px | 640×360 without reconnecting, within 5 s (about 8 s before the fix) |
| The app | drawer and full-screen view live; quality menu, View/Control, readout `1024×576 · … · WebRTC · LAN` |

The emulator's hardware decoder (`c2.goldfish.h264`) fails and the software one (`c2.android.avc`)
holds each picture until the next input arrives; an idle desktop sends one keyframe and nothing more,
so the picture stayed black for seconds after connecting or resizing. Some phones' decoders behave the
same. Hence the keyframe requests while no frame has come out.

libwebrtc per ABI in the APK (uncompressed): arm64-v8a 11.7 MB, armeabi-v7a 6.5 MB, x86_64 15.4 MB,
x86 12.2 MB; release builds keep the two ARM ABIs.

Not yet: a real phone (clicks, typing, gestures by hand, hardware decoders), a throttled link.

