#!/usr/bin/env bash
#
# Copies the OBS Virtual Camera into two further virtual cameras, so that two
# KFS instances - one streaming, one recording a backup - can each have a camera
# of their own.
#
# They cannot share one. A v4l2loopback device admits exactly one capture
# client: the second is refused at VIDIOC_REQBUFS with "Device or resource busy"
# ("only exclusive ownership for each stream", v4l2loopback.c), and the read()
# path is refused in the same place, so no ffmpeg option gets around it.
# max_openers=10 governs open(), not the stream, which is why OBS plus one
# reader is fine and two readers is not.
#
# So this process is the vcam's single reader, and it feeds the other two. That
# is the one thing that changed with v4l2loopback 0.15: before the stream-
# activation rework a copier could read the vcam alongside KFS, and the copy was
# needed only for the second instance. Now it is in the path of both.
#
# The reasoning, the version trap behind it and what breaks when this dies are
# written up in docs/debugging-linux.md under "Two cameras from one OBS".
#
#   ./vcam-fanout.sh start [-i /dev/video10] [-o /dev/video11,/dev/video12]
#   ./vcam-fanout.sh watch [same options]
#   ./vcam-fanout.sh stop
#   ./vcam-fanout.sh status
#
set -uo pipefail

# The devices as the modprobe.d entry lays them out; -i and -o override.
DEFAULT_IN=/dev/video10
DEFAULT_OUT=/dev/video11,/dev/video12

# What the copies are published as. NV12 is the layout the encoder wants
# (V4l2Devices.PREFERRED_FORMATS), and it is 1.5 bytes a pixel against YUYV's 2
# - converting once here is cheaper than both KFS instances resampling chroma on
# the way in, and it halves nothing if left to them.
DEFAULT_PIXFMT=nv12

RUNDIR=${XDG_RUNTIME_DIR:-/tmp}
PIDFILE="$RUNDIR/kfs-vcam-fanout.pid"
LOGFILE="$RUNDIR/kfs-vcam-fanout.log"

usage() {
    cat <<'EOF'
Usage: vcam-fanout.sh <start|watch|stop|status> [options]

  start   copy the OBS Virtual Camera into the two KFS cameras
  watch   the same, then restart the copy whenever it stops - which it does
          every time OBS's virtual camera is stopped. Run this for a session.
  stop    stop copying
  status  show every camera, whether it is being fed, and who is reading it

Options for start and watch:
  -i DEVICE       the camera to copy from      (default /dev/video10)
  -o A,B[,C...]   the cameras to copy into     (default /dev/video11,/dev/video12)
  -p PIXFMT       the layout to publish        (default nv12)

Start OBS's virtual camera first: a loopback carries no format until something
feeds it, so there is nothing to copy until then.
EOF
}

# The sysfs directory of a /dev/videoN, or empty when it is not a loopback.
# Only v4l2loopback devices carry a "state" attribute, which is what tells a
# virtual camera from the real one somebody may have typed by mistake.
sysfs_of() {
    local dir="/sys/class/video4linux/${1##*/}"
    [ -r "$dir/state" ] && echo "$dir"
}

# "capture" once a producer is streaming into the device, "output" while it is
# waiting for one. This is the only honest liveness signal: the node exists from
# the moment the module loads, whether or not anything is behind it.
device_state() {
    local dir
    dir=$(sysfs_of "$1") || return 1
    [ -n "$dir" ] && cat "$dir/state" 2>/dev/null
}

# "FOURCC:WxH@fps" while the device has an owner, and empty otherwise - the
# module returns nothing rather than a stale format, so empty here means "no
# format yet", not "no such device".
device_format() {
    local dir
    dir=$(sysfs_of "$1") || return 1
    [ -n "$dir" ] && tr -d ' ' <"$dir/format" 2>/dev/null
}

# The card label, which is what KFS shows in its picker and therefore the name
# an operator has to match.
device_label() {
    local dir
    dir=$(sysfs_of "$1") || return 1
    [ -n "$dir" ] && cat "$dir/name" 2>/dev/null
}

# The fourcc as ffmpeg spells it. Anything not listed is passed through
# lowercased, which is right often enough and wrong visibly rather than
# silently.
ffmpeg_format() {
    case "${1^^}" in
        YUYV) echo yuyv422 ;;
        UYVY) echo uyvy422 ;;
        NV12) echo nv12 ;;
        YU12) echo yuv420p ;;
        YV12) echo yuv420p ;;
        MJPG) echo mjpeg ;;
        RGB3) echo rgb24 ;;
        BGR3) echo bgr24 ;;
        *) echo "${1,,}" ;;
    esac
}

# The pid of the copy we own, if it is still alive. A pidfile that outlived its
# process is the normal state of affairs after a crash, so the pid is checked
# against the process it claims to be rather than trusted.
running_pid() {
    local pid
    [ -r "$PIDFILE" ] || return 1
    pid=$(cat "$PIDFILE" 2>/dev/null)
    [ -n "$pid" ] || return 1
    [ "$(cat "/proc/$pid/comm" 2>/dev/null)" = "ffmpeg" ] || return 1
    echo "$pid"
}

# Who has the device open, as "name(pid)", ours marked. Answers the question
# status is really being asked: if a camera is busy, busy with what.
readers_of() {
    local device=$1 ours pid out=""
    ours=$(running_pid)
    for pid in $(fuser "$device" 2>/dev/null); do
        local name
        name=$(cat "/proc/$pid/comm" 2>/dev/null) || continue
        [ "$pid" = "${ours:-}" ] && name="$name/this copy"
        out="${out:+$out, }${name}($pid)"
    done
    echo "${out:--}"
}

stop_all() {
    local pid
    pid=$(running_pid) || { rm -f "$PIDFILE"; return 0; }
    kill "$pid" 2>/dev/null
    for _ in 1 2 3 4 5 6 7 8 9 10; do
        running_pid >/dev/null || break
        sleep 0.2
    done
    running_pid >/dev/null && kill -9 "$pid" 2>/dev/null
    rm -f "$PIDFILE"
    return 0
}

status() {
    local device state format label fed=0 total=0
    local input=${1-$DEFAULT_IN} outputs=${2-$DEFAULT_OUT}

    printf '  %-14s %-22s %-8s %-22s %s\n' DEVICE NAME STATE FORMAT "OPEN BY"
    for device in "$input" ${outputs//,/ }; do
        # Counted only once it is known to be a virtual camera, so that the
        # "not being fed" tally below stays about cameras that could be fed
        if [ ! -e "$device" ]; then
            printf '  %-14s ** no such device - is v4l2loopback loaded? **\n' "$device"
            continue
        fi
        if [ -z "$(sysfs_of "$device")" ]; then
            printf '  %-14s ** not a virtual camera **\n' "$device"
            continue
        fi
        total=$((total + 1))
        state=$(device_state "$device")
        format=$(device_format "$device")
        label=$(device_label "$device")
        [ "$state" = capture ] && fed=$((fed + 1))
        printf '  %-14s %-22s %-8s %-22s %s\n' \
            "$device" "$label" "$state" "${format:--}" "$(readers_of "$device")"
    done

    echo
    if running_pid >/dev/null; then
        echo "Copying (pid $(running_pid)); log: $LOGFILE"
    else
        echo "Not copying."
    fi
    # Existing is not the same as working. A camera in "output" state is listed,
    # is selectable, and hands whoever opens it nothing at all - which reaches
    # the operator as a frozen picture rather than as an error, so it is worth
    # saying plainly here.
    if [ "$fed" -lt "$total" ]; then
        echo "$((total - fed)) of $total camera(s) are not being fed - anything reading" >&2
        echo "them sees a frozen picture. Start OBS's virtual camera, then \"start\"." >&2
    fi
    return 0
}

# The same options as start, so "status -i ... -o ..." can look at a set of
# cameras other than the default one without the operator editing this file.
status_cmd() {
    local input=$DEFAULT_IN outputs=$DEFAULT_OUT
    local OPTIND opt
    while getopts ":i:o:p:" opt; do
        case $opt in
            i) input=$OPTARG ;;
            o) outputs=$OPTARG ;;
            *) ;;
        esac
    done
    status "$input" "$outputs"
}

start() {
    local input=$DEFAULT_IN outputs=$DEFAULT_OUT pixfmt=$DEFAULT_PIXFMT
    local OPTIND opt
    while getopts ":i:o:p:" opt; do
        case $opt in
            i) input=$OPTARG ;;
            o) outputs=$OPTARG ;;
            p) pixfmt=$OPTARG ;;
            *) usage; return 2 ;;
        esac
    done

    if ! command -v ffmpeg >/dev/null; then
        echo "ffmpeg is missing" >&2
        return 1
    fi
    if [ -z "$(sysfs_of "$input")" ]; then
        echo "$input is not a virtual camera. Loaded loopback devices:" >&2
        local d
        for d in /sys/class/video4linux/*; do
            [ -r "$d/state" ] && echo "  /dev/${d##*/}  $(cat "$d/name")" >&2
        done
        return 1
    fi

    # Nothing to copy until OBS is emitting, and the module reports no format
    # until then. Saying so is worth more than ffmpeg's own failure, which is a
    # bare EINVAL on a zero-sized frame.
    local format
    format=$(device_format "$input")
    if [ "$(device_state "$input")" != capture ] || [ -z "$format" ]; then
        echo "$input is not being fed - start OBS's virtual camera first." >&2
        return 1
    fi

    # "FOURCC:WxH@fps", where fps is either "30" or "30000/1001"; both are what
    # ffmpeg's -framerate takes, so the rate is passed through as it is written.
    local fourcc size rate
    fourcc=${format%%:*}
    size=${format#*:}; size=${size%@*}
    rate=${format#*@}
    if [ -z "$fourcc" ] || [ -z "$size" ] || [ -z "$rate" ]; then
        echo "Could not read the format of $input (got \"$format\")" >&2
        return 1
    fi

    local device
    for device in ${outputs//,/ }; do
        if [ -z "$(sysfs_of "$device")" ]; then
            echo "$device is not a virtual camera - check the -o list" >&2
            return 1
        fi
    done

    stop_all

    # One decode, one conversion, then a split: converting per output would do
    # the same chroma work twice for no reason. fps_mode passthrough keeps
    # ffmpeg from inventing or dropping frames to hit a rate of its own - the
    # copy must be frame for frame, or every dup= reading downstream is a lie.
    local -a command=(ffmpeg -hide_banner -loglevel warning -nostdin
        -f v4l2 -input_format "$(ffmpeg_format "$fourcc")"
        -video_size "$size" -framerate "$rate" -i "$input")
    # The labels are accumulated into one string - "split=2[o0][o1]" - rather than expanded
    # from an array, whose expansion would depend on IFS. ffmpeg does accept a space between
    # link labels (measured), so this is one less thing to depend on rather than a fix
    local labels="" count=0
    local i=0
    for device in ${outputs//,/ }; do
        labels+="[o$i]"
        count=$((count + 1))
        i=$((i + 1))
    done
    command+=(-filter_complex "[0:v]format=${pixfmt},split=${count}${labels}")
    i=0
    for device in ${outputs//,/ }; do
        command+=(-map "[o$i]" -fps_mode passthrough -f v4l2 "$device")
        i=$((i + 1))
    done

    : >"$LOGFILE"
    setsid "${command[@]}" >>"$LOGFILE" 2>&1 </dev/null &
    echo $! >"$PIDFILE"

    # ffmpeg exits within a second or so when an output will not open, and a
    # copier that died at birth must not be reported as started
    sleep 1.5
    if ! running_pid >/dev/null; then
        echo "The copy did not start:" >&2
        sed 's/^/  /' "$LOGFILE" >&2
        rm -f "$PIDFILE"
        return 1
    fi

    echo "Copying $input ($format) into ${outputs//,/, } as ${pixfmt}"
    echo
    status "$input" "$outputs"
    return 0
}

# Keep it copying. The copy cannot start before OBS's virtual camera does and
# ends when it stops, so on an operator's machine it is normal for this to spend
# its first minutes waiting - which is why waiting is quiet and only the
# transitions are printed.
watch_loop() {
    local input=$DEFAULT_IN outputs=$DEFAULT_OUT
    local OPTIND opt
    while getopts ":i:o:p:" opt; do
        case $opt in
            i) input=$OPTARG ;;
            o) outputs=$OPTARG ;;
            *) ;;
        esac
    done
    OPTIND=1

    local waiting=0
    start "$@" >/dev/null 2>&1 \
        && printf '%s  copying %s -> %s\n' "$(date +%T)" "$input" "${outputs//,/, }"
    echo "Watching; Ctrl-C to stop (the copy stops with it)."
    trap 'stop_all; exit 0' INT TERM
    while true; do
        sleep 3
        # A copy whose input stopped does NOT exit - ffmpeg sits blocked on a
        # device whose producer went away - so "still running" is not "still
        # working". Measured: stop the virtual camera in OBS and the copy stays
        # up, both KFS cameras stay in "capture", and every reader sees a frozen
        # picture for as long as nobody notices. Retire it here, and the restart
        # below picks OBS up when it comes back.
        if running_pid >/dev/null && [ "$(device_state "$input")" != capture ]; then
            stop_all
            printf '%s  OBS stopped its virtual camera - retired the stale copy\n' "$(date +%T)"
            waiting=1
            continue
        fi
        running_pid >/dev/null && { waiting=0; continue; }
        if start "$@" >/dev/null 2>&1; then
            printf '%s  the copy had stopped - restarted it\n' "$(date +%T)"
            waiting=0
        elif [ "$waiting" -eq 0 ]; then
            printf '%s  waiting for OBS to start its virtual camera\n' "$(date +%T)"
            waiting=1
        fi
    done
}

case "${1:-}" in
    start)  shift; start "$@" ;;
    watch)  shift; watch_loop "$@" ;;
    stop)   stop_all && echo "Stopped copying" ;;
    status) shift; status_cmd "$@" ;;
    ""|-h|--help) usage ;;
    *) usage; exit 2 ;;
esac
