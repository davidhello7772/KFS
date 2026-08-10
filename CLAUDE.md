# CLAUDE.md

Guidance for Claude Code when working in this repository.

## Project Overview

**KFS** (Kadampa Festival Streaming) is a JavaFX desktop app that drives **ffmpeg** to
broadcast multilingual festival live streams: one video input (usually OBS Virtual Camera)
plus a multichannel audio interface carrying interpreters, mixed into one mpegts/SRT stream
with a named audio track per language, with live level meters and alarms in the GUI.

- Java 17, JavaFX, single Maven module, main class `org.kadampa.festivalstreaming.GUIStarter`
- Sources under `src/main/java/org/kadampa/festivalstreaming/`, split by platform:
  - root package — the cross-platform core (GUI, settings, recorder, capture manager, meters)
  - `linux/` — `PulseAudioDevices`, `V4l2Devices`, `AudioRelay`, `WindowAttention`
  - `macos/` — `AvFoundationDevices`, `AudioPipe`
  - Windows has no dedicated classes: dshow lets ffmpeg open the devices itself, so its
    handling lives inline in the core behind `Host.isLinux()/isMac()` checks
- **JPMS**: `module-info.java` declares the module; the `opens org.kadampa.festivalstreaming
  to com.google.gson` line is load-bearing (Settings reflection) — do not remove it. The
  platform subpackages need no module-info entries: nothing reflects into them
- No test suite: verify by compiling and by running against real devices

## Build & Run

```bash
mvn clean package        # shaded jar: target/KFS-1.0.<commit-count>-<hash>.jar
java -jar target/KFS-*.jar
```

The jar name embeds the git commit count and hash (git-commit-id plugin) — the build needs
the `.git` directory present.

## Platform capture matrix

| | Video in | Audio in | Start-up loss risk |
|---|---|---|---|
| **Linux** | v4l2 (`/dev/videoN`, OBS vcam = v4l2loopback) | `pw-record` → `AudioRelay` → ffmpeg stdin | none — `AudioRelay` buffers the backlog |
| **macOS** | avfoundation | Java Sound → `AudioPipe` → ffmpeg stdin | small (Java line buffer) |
| **Windows** | dshow | dshow (ffmpeg opens the device itself) | none — dshow timestamps its backlog |

Why the audio paths differ, and every platform-specific pitfall found in production, is
documented per platform — **read the matching file before debugging**:

- Linux problems → [docs/debugging-linux.md](docs/debugging-linux.md)
- macOS problems → [docs/debugging-macos.md](docs/debugging-macos.md)
- Windows problems → [docs/debugging-windows.md](docs/debugging-windows.md)

`scripts/` holds operator tools that sit beside the app rather than in it.
`obs-channel-sources.sh` (Linux) publishes each channel of the interpreter interface
as its own mono PipeWire source so that OBS — which can only take a capture device whole —
can add one language at a time. Its channel routing follows the same by-label rule as
`PulseAudioDevices.channelMap()`, and the wrong-device fallback it exists to prevent is
written up under "Per-channel sources for OBS" in the Linux doc.
`install-desktop-entry.sh` (Linux) gives the app its GNOME launcher, icon and dock
identity — the how and why live under "Desktop launcher and dock attention" in the same
doc.

## Output model: one process, up to two encodes, up to three sinks

Two KFS instances can never run at once on Linux — v4l2loopback gives its streaming slot
to one reader. So everything is one ffmpeg: the livestream encode goes to SRT and/or the
VOD file (a `tee`: identical bits, no extra cost), and the optional **communication
recording** is a second, independent encode (own resolution/bitrates/directory — the
settings' "Comm." fields) appended as a further output. Hard-won ffmpeg facts baked into
`StreamRecorderRunnable`: output options reset at every sink and must be repeated per
output; a tee output silently discards command-line mpegts options (they must sit inside
the slave spec); a tee whose every real slave failed kills the whole process unless a
null co-slave (`| [f=null]-`) rides along — that null leg is why a full disk costs only
the communication file, never the stream; and `onfail` must be spelled out per slave —
despite the documented `abort` default, ffmpeg 8 shrugs a dead unmarked slave off with
"continuing with 1/2 slaves". The failure directions are deliberate and asymmetric: the
file slaves carry `onfail=ignore` (a full disk must not end the stream), the SRT slave
carries `onfail=abort` so a lost stream kills the whole process, recordings included, and
the operator relaunches at once (measured: the loss surfaces in ~1 s when the far end
closes, ~6 s — libsrt's peer-idle timeout — when the network just vanishes). Dual NVENC
sessions are fine on this hardware.

## The audio delay setting (all platforms)

The GUI's audio delay compensates the real capture-chain latency only: **≈500 ms is the
right ballpark on every platform**. It is NOT a per-OS constant. A historical value of
2500 ms on Linux was an artifact of two since-fixed faults (a power-saver CPU clamp plus
start-up audio loss before `AudioRelay` existed) — never restore it without measuring.
Formula: `delay ≈ genuine chain latency (~500) + any start-up audio loss (now ~0)`.

## Reading ffmpeg's status line (the GUI console)

`frame= fps= dup= drop= speed=` — the health of the stream in one line:

- **`dup=` climbing steadily** → real video frames are NOT arriving; ffmpeg repeats the
  last frame to hold the output rate. The stream plays as a slideshow. See the platform doc.
- `drop=` growing → frames arrive faster than declared — usually a rate mismatch.
- `speed=` well below 1.0x → the machine cannot keep up (check CPU clamp on Linux).
- **Exit code 255 = SIGTERM** — the normal result of pressing Stop, not a crash.

`StreamHealth` now reads that line so nobody has to watch it. It judges **rates**, not the
cumulative counters (a thousand duplicated frames over a festival is nothing; a thousand in a
minute is a dead picture), and a condition must hold before it is announced — the same 1s-sample,
hold-then-raise, clear-at-once shape `VolumeMonitor` uses for the audio alarms. First calibration,
from the incidents in the platform docs, and meant to be re-measured rather than believed:

| Condition | Orange after | Red after |
|---|---|---|
| `dup=` ≥ 25% of the configured fps | 5 s | 20 s, or at once above 80% |
| `drop=` > 1/s | 5 s | 20 s |
| `speed=` < 0.97x | 5 s | 10 s below 0.90x |
| `fps=` < 80% of the configured rate | 5 s | — |

A warning is silent: the console line turns orange and bold, marked `[!]`, and the status-bar
readout turns orange; nothing sounds. An error is red and bold, marked `[X]`, and adds `error.wav`
and the dock attention. A degraded picture holds the bar orange instead of breathing back to green
after three seconds, because unlike a passing error it does not clear by itself.

The console **follows the newest line** until the operator scrolls up to read something, and then
stays where they put it; a floating "↓ Jump to latest" appears while it is parked and is the way
back. Scrolling to the bottom resumes following on its own.

**The console is one row per line, monospace on a light panel** — colour and weight for severity,
plus a fixed-width `[X] ` / `[!] ` / `[i] ` gutter that survives being pasted somewhere else.
Selection is **whole lines**: press and drag down the log, shift-click to extend, ctrl-click to pick
out, Ctrl+A / Ctrl+C / right-click to copy. That is the compromise, and it is forced: a JavaFX text
control paints all of its text in one colour, and a `Text` node has no background to highlight and
paints nothing when its selection range is set outside a text control (both measured). The row is
the smallest thing that can show it is selected. Dragging uses JavaFX's own press-drag-release
gesture (`startFullDrag` + `MOUSE_DRAG_ENTERED` per row) rather than working out which row is under
the pointer - no coordinate arithmetic to get wrong, and no walk over a log thousands of lines long.

**The standalone messages matter as much as the counters**, and are matched on ffmpeg's real
strings in `FfmpegMessages` — generic words like "error" or "dropped" match nothing ffmpeg
actually prints for the faults this project hits, which is how a slideshow used to scroll past in
plain black while `err_detect` in the start-up banner sounded the alarm. The important one is
**`More than N frames duplicated`** (10 → 100 → 1000 → 10000; the escalation is the signal), the
standalone twin of a climbing `dup=`. Each rule carries the remedy, printed under the line — when
a new fault is diagnosed live it becomes one more rule there and one more row in the platform doc.

## Stranded layouts, and `repairWindowLayoutLater()`

Something in this window raises a layout request while an ancestor is already laying itself out, and
JavaFX drops it. The node stays marked `NEEDS_LAYOUT` and is never registered with the scene, and
because the walk that registers a dirty branch stops at the first ancestor already marked dirty,
**every branch underneath is stranded behind it for the life of the window**. The status bar
resizing is the one occasion pinned down; there may be others.

It has surfaced three times, each somewhere unrelated to the cause: the advanced options unfolding
onto nothing after an output-type change, the console drawing every line on top of the first, and
the health readout never appearing in the status bar at all. **Suspect it whenever something is
laid out once and then never moves again.**

`repairWindowLayoutLater()` is the workaround, named as one: ask on the following frame for the
outermost box to lay itself out, which takes every stranded branch under it along. It is called from
every writer into the status bar and from the console's own appends, and it coalesces, so a burst of
log lines costs one repair. **A new writer into the status bar belongs there too**, or the next
symptom appears in a fourth place.

## Confirming what cannot be taken back

Stop and closing the window both ask first, through `confirm(...)`, and **Cancel is the default
button** - these appear over a live broadcast, and a stray Enter or Space must not be what ends it
(the Stop button already eats the space bar for the same reason). The wording says what is about to
stop, from the output type. Closing asks once where it can: a running stream is the question worth
asking, and unsaved language edits bring their own Save/Discard/Cancel rather than a second yes/no.

## Compact view

During a session the window shares the screen with OBS and a browser and almost none of it is being
read, so "Compact view" shrinks it to the two things that are: the pulsing bar and the line ffmpeg
is writing right now. It sets **always-on-top**, which is the point of it, and puts that back on the
way out along with the window's previous size.

- The scene root never changes — the mode colour and its style classes live there, so only what
  sits inside the shell is swapped. The full interface is **detached, not rebuilt**, and the console
  goes on collecting every line while it is out of sight.
- The status bar is **moved** into the compact view rather than copied, so the breathing animation,
  the live dot and the health readout keep one code path. Its minimum width is relaxed while it is
  borrowed and restored afterwards.
- The wording is shortened to `LIVE + REC` / `REC` / `LIVE`; `refreshStatusText()` is the one place
  that decides, so the long and short forms cannot drift apart.
- ffmpeg's status line is shown with a **centre ellipsis**: `frame=` at the start and `speed=` at
  the end are what matter, so a narrow window loses the middle.
- **Anything raising an error puts the full window back by itself** (`raiseErrorAlarm`), because the
  console is where the fault is explained. Esc, the ⤢ button or a double click also return.
- Deliberately no Stop button: one mistaken click on a window floating above everything else would
  end a festival stream.

## The language list decides the PIDs (the Languages tab)

The order of `Settings.LANGUAGES` is not decoration: ffmpeg maps the languages that have an audio
source, in list order and only those, and the mpegts muxer hands out PIDs in that same order from
`mpegts_start_pid`. So moving a language, or switching one off, renumbers the PID and the track
number of everything below it. `SettingsUtil.audioTrackIndex` is the one place that rule lives —
the Information tab and the Languages tab both ask it, so they cannot disagree.

- Positions 1-3 (Prayers, English (for mix), English) are built in and fixed: the whole ffmpeg
  filter graph is positional on them (`i==1/2/3` in `initialiseFFMpegCommand`).
- **The video PID is the constant `StreamRecorderRunnable.VIDEO_PID` (37)** — no platform this
  project streams to asks for a particular one, so it stopped being a setting.
- The Languages tab **never mutates `Settings.LANGUAGES`**. Every per-language control in the
  window is a fixed-size array indexed in lock-step with it, so the tab edits its own list, parks
  it on `Settings.setPendingLanguages`, and the change takes effect at the next start. Both Save
  buttons go through `StreamingGUI.saveSettings`, which applies the tab's renames *after* the
  combo sweep — that sweep keys the per-language maps by the old names.

## Key rules

1. Prose-style javadoc/comments that explain *why*; match the existing voice.
2. Anything platform-specific belongs behind `Host.isLinux()/isMac()` checks and, for
   lessons learned, in the matching `docs/debugging-<platform>.md`.
3. When a live-stream fault is diagnosed, record it in the platform doc (symptom → root
   cause → fix → code pointer) — the next incident is usually debugged under time pressure.
