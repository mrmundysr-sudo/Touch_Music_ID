# Lyrics Lab: synchronized-lyrics prototype (DEBUG ONLY)

A separate screen in the **debug** APK only (second launcher icon, "Lyrics Lab (debug)"). It is not in release builds and shares no
UI with the main app. Purpose: prove, with measurements on a real phone, whether ShazamKit can drive synchronized lyrics, before
any redesign. **LRCLIB lyrics are copyrighted and unlicensed for commercial use. This prototype is for personal testing, not distribution.**

## What it does
1. Listens through the microphone (foreground only, released when you leave the screen) with a ShazamKit streaming session.
2. Records every raw match: `matchOffsetInMs`, `predictedCurrentMatchOffset`, `frequencySkew`, signature length.
3. Computes the song position three ways (estimators **A**, **B**, **C**, below) and logs them side by side.
4. Looks up duration/album (iTunes by Apple Music ID, MusicBrainz by ISRC), then LRCLIB (title, artist, album, duration).
5. Shows synchronized lyrics with a highlighted, auto-scrolling line, or a plain scrollable sheet when synchronization is missing,
   rejected, or unreliable. Manual nudge (±100/±250 ms), Re-sync, Mark, Follow on/off, text size.

## Timestamp finding (verified in the SDK bytecode)
`predictedCurrentMatchOffset = matchOffset + (System.currentTimeMillis() - firstChunkTimestamp)`, where `firstChunkTimestamp` is the
timestamp passed with the first audio chunk. ShazamKit therefore expects **wall-clock epoch milliseconds** per chunk. The production
engine passes stream-relative timestamps (0, 250, ...), which is fine for identification but makes the predicted value meaningless
(about 1.8e12 ms). The Lab stamps each chunk with the epoch time of its first frame, taken from `AudioRecord.getTimestamp()`.
Production is untouched.

| Estimator | Meaning | Weakness |
|---|---|---|
| A | The SDK's `predictedCurrentMatchOffset` | Needs correct epoch timestamps |
| B | `matchOffset + signatureDuration + (read time - last chunk fed)` | Overestimates by the network round trip |
| C | `matchOffset + (read time - capture start)` | Assumes the signature began at the first chunk |

## Install and run
1. Download the `Touch-Music-ID-debug-N` artifact from the CI run, unzip, install the APK. Allow microphone access when asked.
2. Open **Lyrics Lab (debug)** (not the main Touch Music ID icon). Keep the screen on (it does this itself).
3. Play a song on another device or app. It can be halfway through. Tap **Start**.
4. Watch the status, the big position timer, and the diagnostic lines at the bottom.

## Calibration protocol: measure the actual error
Goal: the difference between the phone's estimated position and the song's true position, per estimator.
1. Play a song on a laptop in a player with a visible millisecond timecode (for example mpv with `--osd-level=3`, or VLC with the
   time shown, or Audacity). Put the **phone's screen and the laptop's timecode in one camera frame** (a second phone filming both).
2. Start the Lab, wait until it says FOLLOWING and the big timer runs.
3. Record 20 seconds of video. In the video, step frame by frame (about 33 ms per frame) and note the laptop timecode and the phone
   timer at the same frame. Error = phone timer - laptop timecode (positive means the phone is ahead).
4. Do it for estimators A, B and C (tap Est A/B/C; it re-anchors at once from the last fix), at three positions in the song
   (start, about a minute in, near the end), once with the phone speaker/laptop speakers and once with Bluetooth if you use it.
5. Tap **Mark** whenever you like to write the current estimates into the log with a timestamp.
6. Pull the log: `adb pull /sdcard/Android/data/com.mundymorning.touchmusicid.debug/files/lyricslab/` (the path is also shown on
   screen), or use **Copy diag** and paste the text. The log contains no audio.

## Test matrix (see the report for pass criteria)
Song from the start; identified halfway; pause and resume the external player; seek forward; seek backward; restart; repeated
choruses; a live recording or remix; a recording with no synchronized lyrics; a song with no LRCLIB match.

## Attribution and licences
- Correction policy (ignore small differences, halve medium ones, require a second consistent measurement for large jumps, hold
  the clock during silence and re-sync on resume), LRC parsing details, and the LRCLIB candidate scoring follow the ideas in
  [LiveLyrics](https://github.com/Nurzhigit-dev/LiveLyrics) (MIT). This is an independent Kotlin implementation, not a copy.
- LRCLIB server code is MIT. Its **lyrics data has no licence statement we could find**; do not ship it commercially without one.
- MusicBrainz and iTunes lookups are free, identified by User-Agent, rate-limited (1.1 s and 3.2 s between calls) and used only in debug.
