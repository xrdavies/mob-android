# Live desktop on Android

Status: implemented (WebRTC and WebSocket fallback, with system audio)

A thumbnail drawer under the Chat top bar (hidden by default, toggled with the monitor button; always view-only) and a full-screen view with a **View / Control** switch: tap = click, long-press = right-click, drag = drag, two fingers = scroll, pinch = zoom; a keys bar adds ⌘ ⌥ ⌃ ⇧ (latched for the next key), Esc, Tab, arrows and typing the phone's clipboard. Control is released when the app stops.

## Transport

- **WebRTC**: `POST /stream` with a non-trickle offer; receive-only H.264 through libwebrtc, drawn in a `TextureView`; input on the `input` / `pointer` / `control` data channels, with points normalized to the desktop inside the letterbox. Protocol: [mobd desktop streaming](https://github.com/xrdavies/mobd/blob/main/docs/desktop-streaming.md).
- **WebSocket fallback**: if WebRTC has not connected after 15 s (or fails sooner), the same session continues on `/stream/ws` through the OkHttp client (Bearer header). `MediaCodec` decodes into the same renderers; a decoder that fails while running is skipped for the process (the emulator's hardware H.264 decoder does). Used where UDP cannot pass (Tailscale Funnel, HTTPS gateways). A machine that needed it remembers that (`liveWebSocket`): next time WebRTC gets 1 s, then the WebSocket races beside it and the first frame wins.
- **Keyframe requests**: some decoders (the emulator's software one, and some phones') hold a picture until more input arrives, and an idle desktop sends nothing after its keyframe. Until the first frame comes out of the decoder, the app asks mobd for a keyframe every 0.7 s.
- **Quality** Auto / High / Low per machine, from the view or Settings; applies at once (an open view reconnects). View size changes are sent as `resize` without reconnecting. A readout shows size, fps, kB/s, transport and network (LAN, Tailscale, Internet, relay) with signal bars.
- **Snapshots**: when the live view is off, `/screen` at the view width in pixels, quality 65, with `If-None-Match` so an unchanged screen costs a 304; mobd's reason is shown when the screen is unavailable.
- mobd without the `stream` feature shows "Update mobd".

libwebrtc adds about 6.5–15 MB per ABI; release builds ship only `arm64-v8a` and `armeabi-v7a`.

## Audio

You hear the office computer's sound in the live view. The protocol, stages and capture are in [mobd's desktop audio doc](https://github.com/xrdavies/mobd/blob/main/docs/desktop-audio.md): `streamAudio` in `/health`, `audio: true` on `POST /stream`, and `{"t":"audio","on":…}` on the control channel.

WebRTC's default audio device is set up for calls. `StreamSession` builds its own `JavaAudioDeviceModule` for the process:

- **Media, not a call:** `USAGE_MEDIA` / `CONTENT_TYPE_MUSIC`, so sound follows the media volume and the selected output and is not routed or processed like a call.
- **Stereo output,** and no hardware echo canceller or noise suppressor.
- **Never records:** `setAudioRecordEnabled(false)`. The app never asks for `RECORD_AUDIO`.
- **Muted until wanted:** speaker mute stays on until the user turns sound on.

Turning sound on takes audio focus (`AUDIOFOCUS_GAIN`, media attributes). Losing focus to a call or another app turns sound off; the user turns it on again. Turning sound off mutes, releases focus, and logs `live view sound: played N s, peak P%` (Settings → Log) from the playback samples callback (`PlaybackMeter`).

A new live view starts with sound off. Reconnects within the view (resize, quality) keep the choice. Closing the view or leaving the app (`onStop`) turns it off. Over the WebSocket, sound is unavailable by decision (mobd's doc: sound that stutters is worse than none).

## Acceptance gaps

Not yet checked on a real phone: clicks, typing, gestures by hand; hardware decoders; a throttled link; the real Funnel path and a phone off Wi-Fi; no microphone indicator; media volume; wired and Bluetooth output; calls; leaving the app; and audible delay.

## Verification

- `stream/SoundTest.kt`: the request, the answer, control messages, and the playback meter.
- `LiveStreamTest` (instrumented): opens a real live view and moves the office pointer. Skipped unless given a paired credential; see README Setup section.
- By hand on the emulator (`-no-audio`), against a mobd from the branch on this Mac and against the office Mac running a game: sound turned on and off from the app, and the meter logged real playback. The emulator's null audio output consumes at 8 kHz, so it reports less playing time than passed. A phone shows the real rate.
