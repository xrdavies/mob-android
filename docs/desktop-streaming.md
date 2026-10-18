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
| A1 | Snapshot size | emulator |
| A2 | View-only live view; "Update mobd" when `/health` lacks `stream` | emulator + phone |
| A3 | Control over data channels, including the keyboard field | phone |
| A4 | Quality setting and readout | throttled link |
| A5 | WebSocket fallback (MediaCodec) | Funnel address |
| A6 | Remove `vnc/` code and tests, Mac sign-in fields | build |
| A7 | Tests: signaling, message encoding, transport choice, migration | `./gradlew testDebugUnitTest` |

Unblocked: the desktop app confirmed the protocol (D2) and built steps 3–5. A5 uses the WebSocket
protocol as revised in mobd M7b (540p start, `resize`, try the WebSocket first where it was needed).
A6 happens together with mobd M8 and desktop D6. Order across repos: see mobd's plan, "Order".

Follow the desktop design where it applies: the inline view only watches and the large view controls,
with a View/Control switch; quality switched from the view as well as Settings; a readout of size, fps,
kB/s, transport and network with signal bars; WebRTC gets a 5 s head start before the WebSocket.
