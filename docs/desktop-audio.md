# Remote desktop audio (Android)

Status: implemented on `feat/remote-audio` (WebRTC). No sound over the WebSocket fallback, by decision.

You hear the office computer's sound in the live view on Android phones and tablets. The
protocol, stages and capture are in [mobd's desktop audio doc](https://github.com/xrdavies/mobd/blob/main/docs/desktop-audio.md):
`streamAudio` in `/health`, `audio: true` on `POST /stream`, and `{"t":"audio","on":…}` on the
control channel.

## Playback as media, never recording

WebRTC's default audio device is set up for calls. `StreamSession` builds its own
`JavaAudioDeviceModule` for the process:

- **Media, not a call:** `USAGE_MEDIA` / `CONTENT_TYPE_MUSIC`, so sound follows the media volume
  and the selected output and is not routed or processed like a call.
- **Stereo output,** and no hardware echo canceller or noise suppressor.
- **Never records:** `setAudioRecordEnabled(false)`. The app never asks for `RECORD_AUDIO`.
- **Muted until wanted:** speaker mute stays on until the user turns sound on.

Turning sound on takes audio focus (`AUDIOFOCUS_GAIN`, media attributes). Losing focus to a call
or another app turns sound off; the user turns it on again. Turning sound off mutes, releases
focus, and logs `live view sound: played N s, peak P%` (Settings → Log) from the playback samples
callback (`PlaybackMeter`).

## Session and UI

`StreamSession`:

- With `streamAudio`, the offer gets a receive-only audio transceiver and the request
  `audio: true`.
- The answer's `audio` sets `sound` (`LiveSound`): `Off`, or `Unavailable(reason)`.
- `setSound` sends the control message.
- mobd's `audio` messages move it to `On`, `Unavailable` or `Failed`.
- Over the WebSocket, sound is unavailable by decision (mobd's doc: sound that stutters is worse than none).

Turning sound on and off:

- A new live view starts with sound off.
- Reconnects within the view (resize, quality) keep the choice.
- Closing the view or leaving the app (`onStop`) turns it off.

`SoundButton` in `ui/desktop/Desktop.kt`: on the drawer thumbnail and in the full-screen toolbar.
A speaker icon, green while on, a spinner while starting; when unavailable it explains why.

## Verification

- `stream/SoundTest.kt`: the request, the answer, control messages, and the playback meter.
- By hand on the emulator (`-no-audio`), against a mobd from the branch on this Mac and against
  the office Mac running a game: sound turned on and off from the app, and the meter logged real
  playback.
- The emulator's null audio output consumes at 8 kHz, so it reports less playing time than
  passed. A phone shows the real rate.

Still to check on a phone: no microphone indicator, media volume, wired and Bluetooth output,
calls, leaving the app, and audible delay.
