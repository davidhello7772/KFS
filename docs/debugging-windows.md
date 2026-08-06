# Debugging KFS on Windows

## Why Windows needs no audio feeder

On Windows ffmpeg opens the audio device **itself** (`-f dshow -i audio=...`).
DirectShow starts capturing at open time and **timestamps** every buffered sample, so
audio queued while ffmpeg finishes initialising is neither lost nor mis-dated. This is
why Windows never showed the audio-early desync that Linux had before `AudioRelay`
existed — and why `AudioRelay`/`AudioPipe` must NOT be ported to Windows: there is
nothing to fix, and a raw stdin feed would *lose* dshow's timestamps.

| Platform | Who opens the audio device | Start-up backlog |
|---|---|---|
| Windows | ffmpeg (dshow) | kept, timestamped — safe |
| macOS | KFS (Java Sound → `AudioPipe` → stdin) | small loss possible |
| Linux | KFS (`pw-record` → `AudioRelay` → stdin) | kept by `AudioRelay` |

## Symptom → root cause → fix

| Symptom | Root cause | Fix / notes |
|---|---|---|
| Low frame rate from a webcam/capture device | dshow negotiates a low mode when none is pinned | Pass explicit `-video_size` and `-framerate` (KFS builds these from the selected mode) |
| Stereo cable channel conventions | On the Windows machine's stereo feeds, Left/Right are channels 0/1 per language pair | See `pickChannel()` in `StreamRecorderRunnable` |

## Delay setting

≈500 ms — genuine chain latency only (camera + OBS + encoder). See the delay model in
[../CLAUDE.md](../CLAUDE.md).
