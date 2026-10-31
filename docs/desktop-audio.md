# Remote desktop audio

Status: draft, 2026-11-01. Not implemented.

The [mobd audio contract](https://github.com/xrdavies/mobd/blob/main/docs/desktop-audio.md)
defines capability/negotiation, Opus format, WebSocket frames and acknowledgements,
clocks, queue limits and acceptance targets. Implement both transports for the
existing Android phone/tablet support range.

## Current state and change boundaries

`StreamSession` uses the existing libwebrtc dependency with a video-only
receive transceiver. `WsPlayer` decodes H.264 with `MediaCodec` into the existing
renderers. Neither route currently plays office audio.

| Area | Planned change |
| --- | --- |
| `data/` stream API/request/response models | Optional audio request/result and `streamAudio` discovery |
| `stream/StreamSession.kt` | Receive-only audio, audio-device setup, transport winner and output lifecycle |
| `stream/WsPlayer.kt`, `StreamProtocol.kt` | Audio frame parsing/ack, decoder, PCM playback and A/V mapping |
| `ui/desktop/` live-view controls | Sound/mute, unavailable/retry state and accessibility |
| `AndroidManifest.xml`, `app/build.gradle.kts` | Review playback requirements and decoder packaging only if needed |
| `StreamProtocolTest`, `WsPlayerTest`, instrumented `LiveStreamTest` | Protocol, lifecycle and audible synchronization coverage |

Paths above are under `app/src/main/java/com/xrdavies/mob/` unless explicitly
named otherwise; tests use the existing test/androidTest source sets.

## Negotiation and WebRTC

New live views start with sound off. Explicit Sound checks `streamAudio` and
reopens a video-only session with `audio:true`, preserving geometry and releasing
held remote input. Add `MEDIA_TYPE_AUDIO` with `RECV_ONLY` to the offer. Enable
the received track only for the winning transport and the user's playback state.
Do not create a local audio source/track or request `RECORD_AUDIO`.

Use the existing libwebrtc decoder. Inspect/configure its audio device for media
playback, stereo output where supported, and no microphone initialization. Do
not assume receive-only SDP guarantees the native device's recording state, or
that default voice-call routing preserves game sound. Confirm behavior on the
shipped ABIs and library version before advertising support.

Keep `audio.status` distinct from video connectivity. Unavailable capture or
`about:audio` failure stops sound and offers retry while video/input continue.
Silence is valid. Keep only one audio owner when thumbnail/full-screen video
renderers coexist; the losing WebRTC/WebSocket path must not play.

## Audio focus and output

Treat this as media/game playback, not a phone call. Request audio focus when
enabling playback, handle denial/interruption, and release focus when playback
ends. Use media/game audio attributes and respect system volume and the selected
output route. Do not force a communication mode, earpiece, SCO route or speaker
override. Verify that WebRTC output and fallback output obey the same policy.

Local mute stops audible output, not necessarily remote capture/transmission;
continue consuming current data rather than retaining sound for unmute. Handle
headset disconnect, Bluetooth changes and focus loss without replaying buffered
audio. Start/resume only while the live view is foreground and still enabled;
do not repurpose the notification watcher service as an audio service.

## WebSocket decode and timing

Demultiplex video type `1` and raw Opus type `2` on the same paired OkHttp socket.
Validate bounds and negotiated format before decode. Keep video `ack` unchanged
and emit `audioAck` for received audio independently of rendering/mute. A bad
audio packet must not be fed to the H.264 decoder or crash the live view.

First evaluate the platform Opus `MediaCodec` decoder, including the required
codec-specific initialization (Opus header, delay and seek-preroll data). Android
format support does not mean arbitrary raw packets work without configuration.
If supported devices fail the fixture, bundle a pinned libopus JNI decoder for
that gap; do not add another full WebRTC SDK or write an audio codec. Verify
both release ABIs and debug emulator architectures before choosing packaging.

Feed stereo PCM to streaming `AudioTrack` on an audio worker, separate from the
video decoder/main thread. Bound pending samples to the shared 60 ms target and
200 ms maximum. Flush/reset on discontinuity or stale backlog, accounting for
Opus priming. Neither mute nor a slow output device may grow memory indefinitely.

Map the audio playback-head/timestamp to host capture PTS and schedule decoded
video against that clock. Current immediate video rendering needs this mapping
when sound is enabled. Audio continues through a static image; reconnect resets
timing to live data. Handle output sample-rate changes and drift without letting
video backlog stall the audio worker.

## Lifecycle and verification

Closing, backgrounding, changing machine/conversation, stream replacement and
credential revocation stop and release `AudioTrack`/decoder/focus ownership.
Ignore callbacks from previous sessions. A transient reconnect in the same view
can preserve sound enabled but flushes old samples. A newly opened view starts
off. Provide TalkBack labels and clear unavailable/retry states.

Extend unit tests for request/result compatibility, malformed frames, audio
acknowledgement, bounded buffering, timestamp/discontinuity logic and transport
ownership. Run `./gradlew :app:testDebugUnitTest` when implementing; use the
existing instrumented `LiveStreamTest` fixture path for both transports.

On real phones/tablets, verify no microphone prompt/indicator, stereo channels,
system volume, focus denial/loss, calls, wired/Bluetooth routing, background
return, switching views and 30-minute flash/tone synchronization. Include a
supported low-end device, minimum Android level and current target behavior.
Force UDP failure and test the real gateway; measure audible latency/skew against
the mobd targets. Decoder output and emulator playback alone are insufficient.

## References

- [Android supported media formats](https://developer.android.com/media/platform/supported-formats): platform Opus support; raw decoder setup still needs verification.
- [Android audio focus](https://developer.android.com/media/optimize/audio-focus): foreground/focus behavior for playback.
- [libopus](https://opus-codec.org/docs/): optional native decoder when platform validation exposes a gap.
