# Debugging KFS on Linux

Every fault below was hit in production (Summer Festival 2026, MSI Katana 17, Ubuntu,
PipeWire, OBS + v4l2loopback virtual camera, Behringer UMC1820) and diagnosed live on
2026-08-05. Symptom first — start from what you see.

## Symptom → root cause → fix

| Symptom | Root cause | Fix | Code / notes |
|---|---|---|---|
| Whole stream choppy (video AND audio), OBS also struggling, `speed=` sagging | GNOME **power-saver profile** clamps all cores to 600–900 MHz. Persists across reboots; easy to hit in quick settings. | `powerprofilesctl set performance` (effective mid-stream in seconds) | First thing to check, always: `powerprofilesctl get` |
| Video a slideshow, `dup=` climbing ~30/s from the very start, **everything else healthy** (OBS fine, CPU idle, kernel quiet) | **v4l2loopback ≤ 0.15.3 attach race**: the capture session is poisoned at attach time and never recovers; typically the first attach after a reboot. Every re-attach is healthy. | Upgrade the module to **0.15.4+** (race fixes in buffer mapping/locking). Emergency workaround: capture+discard a few frames right before the real capture — a session sacrifice. | Full story in `V4l2Devices.java` class javadoc. Check version: `modinfo v4l2loopback`. **The GUI now says this itself** — see the row below |
| Console shows **`More than N frames duplicated`** (10, then 100, then 1000…) and the picture is choppy for the rest of the session | Same v4l2loopback attach race as above: this is ffmpeg's standalone announcement of the climbing `dup=`, escalating ×10 each time | Stop and start again — a fresh attach is normally healthy; the poisoned session never recovers on its own. Then check the module version | `FfmpegMessages`: red from N ≥ 100, with the remedy printed under the line, and the status bar held orange for the session rather than breathing back to green. `StreamHealth` sees the same fault ~5 s earlier off the `dup=` counter |
| Second KFS window opens fine but **Start fails at once**: `Error opening input: Device or resource busy`, `Error opening input file /dev/videoN`, exit code 240 | A v4l2loopback device admits **exactly one capture client**. The second is refused at `VIDIOC_REQBUFS` — *"only exclusive ownership for each stream"* — and the `read()` fallback is refused in the same place, so no ffmpeg option gets around it. `max_openers=10` governs `open()`, not the stream, which is why OBS **plus one** reader is fine | Close the other window (`fuser -v /dev/videoN` names it), or give each instance a camera of its own with `scripts/vcam-fanout.sh` — see "Two cameras from one OBS" below | `FfmpegMessages` prints the remedy under the line. Verified here: two readers on one device, the second always fails; on separate devices both run |
| Stream takes ~4–5 s to start (console sits after the `Input #0` line) | ffmpeg **probes 5 MB** of the raw s16le audio pipe = ~4.5 s of realtime 10-channel audio. The video device itself opens in ~30 ms. | `-probesize 32 -analyzeduration 0` on the raw pipe input — the format is fully declared, there is nothing to probe | `StreamRecorderRunnable.addAudioInput()` (Linux branch) |
| Audio ahead of video; delay setting needs seconds instead of ~500 ms | Audio recorded during ffmpeg's start-up was **lost in the 64 KB pipe** (68 ms of 10-ch audio), so the first audio is newer than the first video frame by the start-up duration — which varies with machine speed | `AudioRelay` buffers pw-record's output in a 16 MB ring until ffmpeg reads — lossless hand-off, delay stays ~500 ms on any machine | `AudioRelay.java`; it logs "first audio with N ms buffered" on every start |
| Level meters freeze / show "Device unavailable" after a USB hiccup and never recover | Capture thread used to give up on the first exception; a wedged `pw-record` read can also block forever | Auto-recovery: retry loop + 3 s stall watchdog that force-kills and reopens | `AudioCaptureManager.captureOnce()` / `startStallWatchdog()` |
| Languages on wrong channels (e.g. channel 1 arriving as 13) | PipeWire routes stream channels to device channels **by label**; without the device's own labels the map shifts. Also: without `--raw`, pw-record prepends a 24-byte AU header that shifts every channel by 12. | `pw-record --raw --channel-map <device's own labels>` | `PulseAudioDevices.channelMap()`, used by both the meters and the stream |
| SRT stream drops but ffmpeg keeps running and the VOD file keeps growing | ffmpeg 8's tee keeps going when a slave without an explicit `onfail` dies ("continuing with 1/2 slaves"), whatever its documentation says about an `abort` default | `onfail=abort` on the SRT slave — the loss then kills the whole process in ~1 s (peer closed) to ~6 s (network gone), recordings included, alarm + dock attention raised, Start re-enabled | `StreamRecorderRunnable.initialiseFFMpegCommand()`, tee spec; GUI side in the `isAliveProperty` listener |
| Generic gears icon on the desktop shortcut and in the dock; nothing signals from the taskbar while streaming | No `.desktop` entry matches the window: GNOME matches by **WM_CLASS** (for JavaFX, the Application subclass FQCN — not overridable) and the dock ignores the stage icons the Windows blink trick swaps | Run `scripts/install-desktop-entry.sh`; install `wmctrl` for the streaming-time attention state | Section below |

## Per-channel sources for OBS

OBS offers a capture *device*, never a channel inside one. The interpreter interface
arrives as a single multichannel input — the Qu-5 as 32 channels at 96 kHz — and "Audio
Input Capture (PulseAudio)" takes it whole. To give OBS one interpreter at a time, for a
language scene, a monitor mix or a separate recording, each channel has to already be a
device of its own by the time OBS looks at the list.

`scripts/obs-channel-sources.sh` makes them, one `pw-loopback` per channel: each takes a
single channel off the interface and offers it again as a mono source named "Qu-5 Ch 01"
and so on, which is what OBS then lists. The hardware is untouched — the sound server
shares it, so this runs happily while KFS streams from the same interface.

```bash
./scripts/obs-channel-sources.sh start -n 14
```

`-d Qu-5` picks the interface when more than one multichannel input is present (otherwise
the one with the most channels wins), `-l English,Spanish,...` names the channels instead
of numbering them, and `stop` / `status` do what they say. The sources live only until the
machine reboots, so this belongs in the start-of-day routine, before OBS is opened; OBS
re-reads the device list every time a source's properties dialog opens, so it does not
need restarting afterwards.

### Unplugging the interface silently empties every channel

**Symptom:** OBS still lists and still shows every language, and every one of them is
silent. Nothing has crashed, `status` used to report a cheerful fourteen out of fourteen,
and the interface itself is back and healthy.

**Cause:** the loopback processes outlive the interface, but their capture streams do not.
Unplugging tears the stream down, `node.dont-reconnect` stops it coming back — that is the
property earning its keep, since the alternative is it reattaching to the default input and
putting the HDMI capture on air — and what is left is a source node fed by nothing. The
node still exists, so OBS has no reason to complain.

**Fix:** re-run `start`; the sources keep their names, so OBS reattaches on its own within a
second or two. Better, run the session under `watch`, which polls the link count and
republishes whenever the channels are pulled away — an unplug, a USB reset, a sound-server
restart look the same to it, and it waits patiently if the interface is not back yet:

```bash
./scripts/obs-channel-sources.sh watch -n 14
```

**Existing is not working**, and `status` now says which: it counts links into the
interface, not source nodes, and marks any channel that is merely listed as
`** not attached to the interface **`.

### The properties that matter, and what they prevent

| Property | Without it |
|---|---|
| `audio.position=[AUXn]` on the capture side | The channel is picked **by the device's own label**, never by position — the same rule `PulseAudioDevices.channelMap()` obeys for the stream itself. Read the labels from `pw-dump`; do not assume `AUX0..n`. |
| `stream.dont-remix=true` | The server folds all 32 channels down into the one mono channel that was asked for, so every source carries the same mix of everything. |
| `node.dont-reconnect=true` | **The dangerous one.** A loopback whose target interface is absent does not fail — it attaches to the *default* input instead. Measured here: with the Qu-5 unplugged, a source still called "Qu-5 Ch 01" quietly carried the Magewell HDMI capture. With the property set, the loopback exits and the source simply never appears, which is the failure an operator can see. |
| `node.passive=true` | The interface is held open forever. Passive lets it suspend when nobody is listening and resume when OBS attaches (verified: `SUSPENDED` → `RUNNING` on attach). |
| ~0.1 s between launches | Fired off in one burst, the odd loopback loses the race to attach and — being told never to reconnect elsewhere — exits rather than retry. The script staggers them and re-tries whatever is still missing. |

### Verifying the mapping

The link graph is the proof, and it only exists while something is listening:

```bash
parec --device=obsch_ch07 --raw > /dev/null & sleep 2
pw-link -l | grep -A1 'obsch_ch07_capture:input'
kill %1
```

Healthy is exactly one incoming link:

```
obsch_ch07_capture:input_AUX6
  |<- alsa_input.usb-Allen_Heath_Ltd_Qu-5-00.multichannel-input:capture_AUX6
```

`AUX6` for mixer channel 7 — off by one because the labels count from zero. More than one
link means `stream.dont-remix` did not take; a link from another device means
`node.dont-reconnect` did not.

### When a channel sounds bad

The loopback is transparent, so before suspecting it, record the same channel both ways at
once and compare — anything wrong with the mixer feed shows up in both:

```bash
timeout 12 pw-record --raw --target=<device> --rate=48000 --format=s16 \
    --channel-map="$(pw-dump | grep -m1 -A0 audio.position)" direct.raw &
timeout 12 parec --device=obsch_ch05 --raw --format=s16le --rate=48000 --channels=1 > loop.raw
```

Then compare channel 5 of `direct.raw` (interleaved, stride = the device's channel count)
against `loop.raw`: same peak level, no runs of exact zeros, no sample-to-sample jumps.
Identical means the interruption came in on the wire and the mixer is where to look — that
is what it was the first time this was asked. A difference means the loopback, and
`pw-top` will show which node is taking the xruns (`ERR` column).

### What it changes elsewhere

- **KFS's own device picker lists them too** — they are ordinary PulseAudio sources, so
  `ffmpeg -sources pulse` reports all fourteen next to the real interface. Picking one for
  the stream would hand KFS a 1-channel device where it needs twelve. The real interface
  keeps its own name ("Qu-5 Multichannel"); the per-channel ones all read "Ch NN".
- **Sample rate and latency.** The mono sources come out at 48 kHz float regardless of the
  interface's 96 kHz; the server resamples per stream. The loopback adds its own small
  buffer, so an OBS source taken this way is a few milliseconds behind the same audio
  inside KFS — OBS's per-source sync offset covers it if it ever matters.
- **Nothing about the KFS stream changes.** KFS still opens the multichannel device
  through `pw-record` as before; these are additional clients of the same hardware.

## Desktop launchers and dock attention

`scripts/install-desktop-entry.sh` installs, per-user and without sudo: three icons into
the hicolor theme, three `.desktop` entries in the app grid, trusted copies of them on the
Desktop, and a `~/.local/bin/kfs` wrapper that starts the newest jar — so a rebuild, whose
jar name changes with every commit, needs no reinstall. Re-run it when an icon, the java
path, `StreamingGUI`'s name or the settings root changes.

**Three launchers, one jar, one settings folder each.** `Host.userDataDir()` reads
`-Dkfs.dataDir`, so a launcher can hand its copy a folder of its own:

| Grid entry | `kfs` argument | Settings folder |
|---|---|---|
| KFS Livestreaming | `livestreaming` | `<root>/Livestreaming` |
| KFS Recording | `recording` | `<root>/Recording` |
| KFS Testing | `testing` | `<root>/Testing` |

The root is `~/Documents/KFS/Parameters` unless `KFS_PARAM_ROOT` says otherwise, and each
folder holds that configuration's `settings.ini` and its own unpacked `rnmodel/`. Three
rules the script enforces so the operator never meets them the hard way:

- **No space anywhere in the root.** The rnnoise model paths under the data dir are inlined
  into an ffmpeg filter string, so a space would not fail at launch — it would fail at
  Start, in front of an audience. The installer refuses such a root outright.
- **`-D` before `-jar`.** After it the JVM hands the word to the application, and nothing in
  KFS reads its arguments, so a misplaced property is ignored in silence and that launcher
  quietly shares the default `~/.kfs` with the others.
- **The wrapper `cd`s into the data dir.** `SettingsUtil.settingsFile` falls back to a
  `settings.ini` *relative to the working directory* when the data dir has none yet, and a
  desktop launch starts in `$HOME`. Starting in the data dir means a stray file in `$HOME`
  cannot become all three configurations' settings at once.

Running `kfs` with no argument still behaves as it always did — no property, so `~/.kfs`.

**Nothing stops two configurations being launched at once**, and nothing warns: the second
window opens perfectly and fails only at Start, because a virtual camera gives its capture
slot to one reader. If a stream will not start, look for a second KFS window first — the
console now says so itself. Two windows *can* both stream, but only once each has a camera
of its own; that is what the next section is for.

Three GNOME facts drive the design, all verified here:

- **The dock icon is matched, not carried.** GNOME pairs a window with a launcher through
  the window's WM_CLASS, which for a JavaFX app is the FQCN of the `Application` subclass
  (`org.kadampa.festivalstreaming.StreamingGUI` — checked with `xprop WM_CLASS`). The
  JavaFX launcher stamps it before `start()` runs and glass's `setName` is set-once, so
  code cannot change it; the `.desktop` entry simply declares it as `StartupWMClass`.
  Renaming or moving the class silently breaks the match — update the script's constant.
- **One WM_CLASS belongs to one entry, so a fourth entry owns it.** All three launchers
  start the same class, and if all three declared it the shell would pick a winner and the
  dock would name the wrong configuration two times in three. So `kfs.desktop` is installed
  `NoDisplay=true` — invisible in the grid, the sole holder of `StartupWMClass`, carrying
  the neutral `kfs` icon — and the three visible entries declare none. The dock then says
  only that KFS is running, which is true whichever launcher was used. GLib still
  enumerates a hidden entry for the shell's WM_CLASS map (`Gio.AppInfo.get_all()` returns
  it with `should_show=False`), which is what makes this work; the shell's own
  `Introspect` and `Screenshot` methods are `AccessDenied` here, so **the dock itself can
  only be checked by looking at it**. If it ever shows generic gears, fall back in this
  order: drop `NoDisplay=true` from `kfs.desktop` (an extra, profile-less entry appears in
  the grid — ugly but deterministic), then move `StartupWMClass` onto
  `kfs-livestreaming.desktop` and delete `kfs.desktop`.
- **The dock ignores stage icons**, so the Windows taskbar-blink trick (`onPulse`
  swapping `stage.getIcons()`) is invisible on GNOME. The equivalent is the window
  manager's *demands-attention* state, which the shell renders as a highlighted dock icon
  until the window is focused. `WindowAttention` (linux package) toggles it by shelling
  out to `wmctrl` (or `xdotool`) — **one of them must be installed**:
  `sudo apt install wmctrl`. JavaFX runs on XWayland, so the X tools reach the window in
  a Wayland session too. While the stream runs the state is re-asserted every time the
  window loses focus; on a stream-lost crash it is raised and left standing.

## Diagnostic recipes

- **Power state (check FIRST)**: `powerprofilesctl get`; core clocks:
  `cat /sys/devices/system/cpu/cpu*/cpufreq/scaling_cur_freq | sort -n | sed -n '1p;$p'`
- **Kernel-side USB/video faults**: `journalctl -k | grep -iE "uvcvideo|xhci|overrun"`.
  A `buffer overrun` on the audio interface's controller is usually *another* symptom of
  the CPU clamp, not a hardware fault — the Magewell capture card was wrongly blamed once.
- **OBS logs**: `~/.config/obs-studio/logs/` — `select timed out` storms mean a camera
  source stopped delivering; `Max audio buffering reached` means OBS itself is starving.
- **Who holds the virtual camera**: `fuser -v /dev/video4` (writer = obs, reader = ffmpeg).
  Only one capture client can attach; a probe while KFS streams gets `Device busy`.
  `scripts/vcam-fanout.sh status` says the same thing for every camera at once, and adds
  whether each is actually being fed.
- **Is a virtual camera live?** `cat /sys/class/video4linux/videoN/state` — `capture` means
  a producer is streaming into it, `output` means it is waiting for one. `format` alongside
  it is empty until then, which is the difference between a camera that exists and one that
  works.
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
