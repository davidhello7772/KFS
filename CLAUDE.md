# CLAUDE.md

Guidance for Claude Code when working in this repository.

## Project Overview

**KFS** (Kadampa Festival Streaming) is a JavaFX desktop app that drives **ffmpeg** to
broadcast multilingual festival live streams: one video input (usually OBS Virtual Camera)
plus a multichannel audio interface carrying interpreters, mixed into one mpegts/SRT stream
with a named audio track per language, with live level meters and alarms in the GUI.

- Java 17, JavaFX, single Maven module, main class `org.kadampa.festivalstreaming.GUIStarter`
- All sources in one package: `src/main/java/org/kadampa/festivalstreaming/`
- **JPMS**: `module-info.java` declares the module; the `opens org.kadampa.festivalstreaming
  to com.google.gson` line is load-bearing (Settings reflection) — do not remove it
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

## Key rules

1. Prose-style javadoc/comments that explain *why*; match the existing voice.
2. Anything platform-specific belongs behind `Host.isLinux()/isMac()` checks and, for
   lessons learned, in the matching `docs/debugging-<platform>.md`.
3. When a live-stream fault is diagnosed, record it in the platform doc (symptom → root
   cause → fix → code pointer) — the next incident is usually debugged under time pressure.
