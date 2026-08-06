# KFS — Kadampa Festival Streaming

A JavaFX desktop application that drives ffmpeg to broadcast multilingual festival live
streams: one video input (usually OBS Virtual Camera) and a multichannel audio interface
carrying the interpreters, mixed into a single SRT stream with a named audio track per
language. The GUI provides device selection, per-language noise reduction, live level
meters and audio alarms.

Runs on Linux, macOS and Windows, each with its own capture path.

## Build & run

```bash
mvn clean package
java -jar target/KFS-*.jar
```

Requires Java 17+, Maven, and ffmpeg on the PATH (or configured in the app settings).

## Documentation

- [CLAUDE.md](CLAUDE.md) — architecture overview, platform capture matrix, and how to
  read the stream's health line
- [docs/debugging-linux.md](docs/debugging-linux.md) — Linux problems and solutions
- [docs/debugging-macos.md](docs/debugging-macos.md) — macOS problems and solutions
- [docs/debugging-windows.md](docs/debugging-windows.md) — Windows problems and solutions
