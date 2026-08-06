# Debugging KFS on Linux

Every fault below was hit in production (Summer Festival 2026, MSI Katana 17, Ubuntu,
PipeWire, OBS + v4l2loopback virtual camera, Behringer UMC1820) and diagnosed live on
2026-08-05. Symptom first — start from what you see.

## Symptom → root cause → fix

| Symptom | Root cause | Fix | Code / notes |
|---|---|---|---|
| Whole stream choppy (video AND audio), OBS also struggling, `speed=` sagging | GNOME **power-saver profile** clamps all cores to 600–900 MHz. Persists across reboots; easy to hit in quick settings. | `powerprofilesctl set performance` (effective mid-stream in seconds) | First thing to check, always: `powerprofilesctl get` |
| Video a slideshow, `dup=` climbing ~30/s from the very start, **everything else healthy** (OBS fine, CPU idle, kernel quiet) | **v4l2loopback ≤ 0.15.3 attach race**: the capture session is poisoned at attach time and never recovers; typically the first attach after a reboot. Every re-attach is healthy. | Upgrade the module to **0.15.4+** (race fixes in buffer mapping/locking). Emergency workaround: capture+discard a few frames right before the real capture — a session sacrifice. | Full story in `V4l2Devices.java` class javadoc. Check version: `modinfo v4l2loopback` |
| Stream takes ~4–5 s to start (console sits after the `Input #0` line) | ffmpeg **probes 5 MB** of the raw s16le audio pipe = ~4.5 s of realtime 10-channel audio. The video device itself opens in ~30 ms. | `-probesize 32 -analyzeduration 0` on the raw pipe input — the format is fully declared, there is nothing to probe | `StreamRecorderRunnable.addAudioInput()` (Linux branch) |
| Audio ahead of video; delay setting needs seconds instead of ~500 ms | Audio recorded during ffmpeg's start-up was **lost in the 64 KB pipe** (68 ms of 10-ch audio), so the first audio is newer than the first video frame by the start-up duration — which varies with machine speed | `AudioRelay` buffers pw-record's output in a 16 MB ring until ffmpeg reads — lossless hand-off, delay stays ~500 ms on any machine | `AudioRelay.java`; it logs "first audio with N ms buffered" on every start |
| Level meters freeze / show "Device unavailable" after a USB hiccup and never recover | Capture thread used to give up on the first exception; a wedged `pw-record` read can also block forever | Auto-recovery: retry loop + 3 s stall watchdog that force-kills and reopens | `AudioCaptureManager.captureOnce()` / `startStallWatchdog()` |
| Languages on wrong channels (e.g. channel 1 arriving as 13) | PipeWire routes stream channels to device channels **by label**; without the device's own labels the map shifts. Also: without `--raw`, pw-record prepends a 24-byte AU header that shifts every channel by 12. | `pw-record --raw --channel-map <device's own labels>` | `PulseAudioDevices.channelMap()`, used by both the meters and the stream |

## Diagnostic recipes

- **Power state (check FIRST)**: `powerprofilesctl get`; core clocks:
  `cat /sys/devices/system/cpu/cpu*/cpufreq/scaling_cur_freq | sort -n | sed -n '1p;$p'`
- **Kernel-side USB/video faults**: `journalctl -k | grep -iE "uvcvideo|xhci|overrun"`.
  A `buffer overrun` on the audio interface's controller is usually *another* symptom of
  the CPU clamp, not a hardware fault — the Magewell capture card was wrongly blamed once.
- **OBS logs**: `~/.config/obs-studio/logs/` — `select timed out` storms mean a camera
  source stopped delivering; `Max audio buffering reached` means OBS itself is starving.
- **Who holds the virtual camera**: `fuser -v /dev/video4` (writer = obs, reader = ffmpeg).
  Only one streaming reader can attach; a probe while KFS streams gets `Device busy`.
- **Measure the vcam raw**: `ffmpeg -f v4l2 -i /dev/video4 -t 15 -f null -` — healthy is
  a steady `fps=30`. Run it *before* starting KFS, never during.
- **Loopback vs real camera**: loopback nodes live under
  `/sys/devices/virtual/video4linux/`; real cameras sit on the USB/PCI tree.
- **ffmpeg exit 255 = SIGTERM** (the Stop button). Not a crash.

## Architecture reminder

Audio: Behringer → PipeWire → `pw-record` (spawned by KFS) → `AudioRelay` (Java ring
buffer) → ffmpeg `pipe:0` (raw s16le, timestamps by sample count). Video: OBS composites →
Virtual Camera (v4l2loopback) → ffmpeg v4l2 input (kernel timestamps). Both heads date
from process start, which is what keeps them in sync — protect that property.
