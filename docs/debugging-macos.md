# Debugging KFS on macOS

## Why the audio goes through Java (`AudioPipe`)

ffmpeg's **avfoundation** audio input loses a large and varying share of the audio of a
multi-channel device — unusable for the interpreter mixer. So on macOS KFS captures the
audio itself with Java Sound and feeds the samples to ffmpeg over its standard input
(`AudioPipe.java`); ffmpeg only opens the *video* side of avfoundation. Do not "simplify"
this back to a direct `-f avfoundation` audio input without re-testing a multi-channel
device end to end.

## Symptom → root cause → fix

| Symptom | Root cause | Fix / notes |
|---|---|---|
| Some languages silent or crackling with a direct avfoundation audio input | avfoundation multichannel loss (above) | Use the `AudioPipe` path — that is why it exists |
| Video device refuses to open | avfoundation requires an explicit `-framerate` and `-video_size` it has advertised; it also refuses `yuv420p` from cameras that don't offer it (OBS Virtual Camera among them) | `AvFoundationDevices` enumerates real modes; it lists devices by asking for an **impossible framerate** so ffmpeg prints the mode list instead of capturing |
| Audio slightly early after start | The Java Sound line buffer (~150 ms) plus stdin pipe can drop a little start-up audio while ffmpeg initialises | Small compared to Linux's historical loss; the ~500 ms delay setting absorbs it |

## Delay setting

≈500 ms — genuine chain latency only. See the delay model in [../CLAUDE.md](../CLAUDE.md);
the same reasoning applies on every platform.
