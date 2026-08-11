#!/usr/bin/env bash
#
# Publishes each channel of a multichannel capture interface as its own mono
# PipeWire source, so that OBS - which offers a capture device as a whole and
# has no way to reach inside one - can add a single interpreter channel as an
# ordinary "Audio Input Capture (PulseAudio)" source, one per language.
#
# Nothing here touches the interface itself. Each channel gets a pw-loopback
# process that takes one channel off the device and offers it again under its
# own name; the sound server shares the device between all of them and KFS's
# own capture, so this can run while KFS streams.
#
# The reasoning, and every pitfall it steers around, is written up in
# docs/debugging-linux.md under "Per-channel sources for OBS".
#
#   ./obs-channel-sources.sh start [-n 14] [-d Qu-5] [-l English,Spanish,...]
#   ./obs-channel-sources.sh watch [same options]
#   ./obs-channel-sources.sh stop
#   ./obs-channel-sources.sh status
#
set -uo pipefail

# Every node this script creates carries this prefix, which is how "stop" and
# "status" tell our loopbacks from anybody else's.
PREFIX=obsch

usage() {
    cat <<'EOF'
Usage: obs-channel-sources.sh <start|watch|stop|status> [options]

  start   publish one mono source per channel (restarts them if already up)
  watch   the same, then republish them whenever the interface is unplugged,
          reset or otherwise pulls the channels away. Run this for a session.
  stop    remove them again
  status  list the channels, and say which are really attached to the interface

Options for start and watch:
  -n N            how many channels to publish, counting from 1 (default 14)
  -d DEVICE       which interface, matched against the source name, the
                  description or the nickname, case-insensitively.
                  Default: the input with the most channels.
  -l A,B,C        name the channels instead of numbering them, in order;
                  channels past the end of the list stay numbered.
EOF
}

# The chosen device as four tab-separated fields: source name, nickname,
# description and the device's own channel labels. Empty when no input matches.
#
# The labels matter more than they look: the sound server routes a stream's
# channels to a device's by label, not by position, so a channel is picked by
# asking for the label the device itself uses (AUX0, AUX1, ... on both the Qu-5
# and the UMC1820). This is the same routing rule that KFS's own capture obeys
# through pw-record's --channel-map; see PulseAudioDevices.channelMap().
device_fields() {
    local selector=$1
    pw-dump 2>/dev/null | python3 -c '
import json, sys

selector = sys.argv[1].lower()
best = None
for obj in json.load(sys.stdin):
    props = (obj.get("info") or {}).get("props") or {}
    if props.get("media.class") != "Audio/Source":
        continue
    channels = props.get("audio.channels") or 0
    position = props.get("audio.position")
    if not channels or not position:
        continue
    if isinstance(position, str):
        # pw-dump writes the labels either as a JSON array or as one SPA string,
        # "[ AUX0, AUX1, ... ]" - brackets and spaces included
        position = position.strip().lstrip("[").rstrip("]").split(",")
    position = [p.strip() for p in position]
    name = props.get("node.name", "")
    nick = props.get("node.nick") or ""
    description = props.get("node.description") or name
    if selector:
        haystack = " ".join((name, nick, description)).lower()
        if selector not in haystack:
            continue
    elif channels <= 2:
        # With no selector, an interpreter interface is what is wanted, never
        # the webcam microphone that happens to be listed first
        continue
    if best is None or channels > best[0]:
        best = (channels, name, nick or description, description, position)

if best:
    print("\t".join((best[1], best[2], best[3], ",".join(best[4]))))
' "$selector"
}

# The pids of the loopbacks this script owns. Anchoring on the command name
# keeps the pattern from matching the shell that is running this very script.
our_pids() {
    pgrep -f "^pw-loopback .*${PREFIX}_ch" 2>/dev/null
}

stop_all() {
    local pids
    pids=$(our_pids)
    if [ -z "$pids" ]; then
        return 0
    fi
    # shellcheck disable=SC2086
    kill $pids 2>/dev/null
    # Give the sound server a moment to retire the nodes, so that a "start"
    # right afterwards does not briefly see both generations
    for _ in 1 2 3 4 5 6 7 8 9 10; do
        [ -z "$(our_pids)" ] && break
        sleep 0.2
    done
    return 0
}

# The node names currently offered by the sound server, ours only.
published_nodes() {
    pactl list short sources 2>/dev/null | awk '{print $2}' | grep "^${PREFIX}_ch"
    return 0
}

# The channels that are genuinely fed by the interface right now.
#
# Existing is not the same as working, and the difference is the whole reason
# this function is here. Unplug the interface and the loopback processes stay
# up and their sources stay listed - OBS goes on showing the language and goes
# on hearing nothing, because the capture stream behind it was torn down and,
# being told never to reconnect, never came back. Only the link into the
# interface tells the truth, so that is what is counted.
linked_channels() {
    pw-link -l 2>/dev/null | awk -v p="$PREFIX" '
        $0 ~ "^"p"_ch[0-9]+_capture:input_" { node = $0; sub(/_capture:input_.*/, "", node); next }
        /\|<-/ && node != "" { print node; node = ""; next }
        { node = "" }' | sort -u
    return 0
}

# Each of our sources as "node<TAB>description", in channel order.
node_descriptions() {
    pactl list sources 2>/dev/null \
        | grep -E "^\s+(Name|Description):" | paste - - \
        | sed -E 's/^\s*Name:\s*//; s/\s*Description:\s*/\t/' \
        | grep "^${PREFIX}_ch" | sort
    return 0
}

status() {
    local -A linked=()
    local node desc orphaned=0 total=0
    while read -r node; do
        [ -n "$node" ] && linked[$node]=1
    done < <(linked_channels)

    while IFS=$'\t' read -r node desc; do
        total=$((total + 1))
        if [ -n "${linked[$node]:-}" ]; then
            printf '  %-12s ->  %s\n' "$node" "$desc"
        else
            orphaned=$((orphaned + 1))
            printf '  %-12s ->  %s   ** not attached to the interface **\n' "$node" "$desc"
        fi
    done < <(node_descriptions)

    echo "${total} channel source(s) published, $((total - orphaned)) attached to the interface"
    if [ "$orphaned" -gt 0 ]; then
        echo "Those channels are silent in OBS. Re-run \"start\" to reattach them," >&2
        echo "or use \"watch\" so that a replug repairs itself." >&2
    fi
    return 0
}

# Publish, then keep them published. A loopback that lost its interface never
# reattaches by itself - deliberately, since the alternative is it silently
# attaching to the default input instead - so something has to notice and
# rebuild them. Polling the link count is enough: it is one cheap call, and it
# sees an unplug, a USB reset and a sound-server restart alike.
watch_loop() {
    local expected=14 waiting=0
    local OPTIND opt
    while getopts ":n:d:l:" opt; do
        case $opt in
            n) expected=$OPTARG ;;
            *) ;;
        esac
    done
    OPTIND=1

    start "$@" || echo "Waiting for the interface..."
    echo "Watching ${expected} channel(s); Ctrl-C to stop (the sources stay up)."
    while true; do
        sleep 5
        [ "$(linked_channels | wc -l)" -ge "$expected" ] && { waiting=0; continue; }
        if start "$@" >/dev/null 2>&1; then
            printf '%s  channels were lost - republished %s\n' "$(date +%T)" "$expected"
            waiting=0
        elif [ "$waiting" -eq 0 ]; then
            printf '%s  channels lost and the interface is not there - waiting\n' "$(date +%T)"
            waiting=1
        fi
    done
}

# One channel, published as its own mono source. Reads the device and the
# channel labels from the locals that start() set up.
spawn_channel() {
    local i=$1 node desc
    printf -v node '%s_ch%02d' "$PREFIX" "$i"
    if [ -n "${label[i-1]:-}" ]; then
        desc="${nick} ${label[i-1]}"
    else
        printf -v desc '%s Ch %02d' "$nick" "$i"
    fi
    # stream.dont-remix stops the server folding the whole interface into this
    # one channel; node.dont-reconnect stops it silently attaching to whatever
    # the default input happens to be when the interface is absent - see the
    # docs, that fallback would put the HDMI capture on air under an
    # interpreter's name; node.passive lets the interface suspend again when
    # nobody is listening.
    setsid pw-loopback \
        -n "$node" -c 1 -m '[MONO]' \
        -C "$source" \
        --capture-props="stream.dont-remix=true node.dont-reconnect=true node.passive=true audio.position=[${position[i-1]}] node.name=${node}_capture" \
        --playback-props="media.class=Audio/Source node.name=${node} node.description=\"${desc}\" audio.position=[MONO]" \
        >/dev/null 2>&1 </dev/null &
}

start() {
    local count=14 selector="" names="" node
    local OPTIND opt
    while getopts ":n:d:l:" opt; do
        case $opt in
            n) count=$OPTARG ;;
            d) selector=$OPTARG ;;
            l) names=$OPTARG ;;
            *) usage; return 2 ;;
        esac
    done

    if ! command -v pw-loopback >/dev/null; then
        echo "pw-loopback is missing - install the PipeWire tools (pipewire-bin)" >&2
        return 1
    fi

    local fields
    fields=$(device_fields "$selector")
    if [ -z "$fields" ]; then
        echo "No multichannel capture device${selector:+ matching \"$selector\"} is present." >&2
        echo "Present inputs:" >&2
        pactl list short sources 2>/dev/null \
            | grep -v -e '\.monitor' -e "${PREFIX}_ch" | sed 's/^/  /' >&2
        return 1
    fi

    local source nick description labels
    IFS=$'\t' read -r source nick description labels <<<"$fields"
    local -a position
    IFS=',' read -r -a position <<<"$labels"
    local -a label
    IFS=',' read -r -a label <<<"$names"

    if [ "$count" -gt "${#position[@]}" ]; then
        echo "$description has only ${#position[@]} channels; asked for $count" >&2
        return 1
    fi

    stop_all
    echo "Publishing $count channel(s) of $description ($source)"

    local i
    for ((i = 1; i <= count; i++)); do
        spawn_channel "$i"
        # Launched in one burst, the odd loopback loses the race to attach and,
        # being told never to reconnect elsewhere, exits rather than retry.
        # A tenth of a second apart they all come up.
        sleep 0.1
    done

    # Whatever still slipped through gets one more attempt, and only then is
    # the interface declared the problem
    local -a missing
    local -A up
    local attempt n
    for attempt in 1 2 3; do
        sleep 1
        # Attached to the interface, not merely listed - a source that exists
        # but is fed by nothing is exactly the failure this is looking for
        up=()
        while read -r n; do
            [ -n "$n" ] && up[$n]=1
        done < <(linked_channels)
        missing=()
        for ((i = 1; i <= count; i++)); do
            printf -v node '%s_ch%02d' "$PREFIX" "$i"
            [ -n "${up[$node]:-}" ] || missing+=("$i")
        done
        [ ${#missing[@]} -eq 0 ] && break
        [ "$attempt" -eq 3 ] && break
        for i in "${missing[@]}"; do
            spawn_channel "$i"
            sleep 0.1
        done
    done
    if [ ${#missing[@]} -ne 0 ]; then
        echo "Channel(s) ${missing[*]} did not come up - is $description still connected?" >&2
        return 1
    fi
    status
    echo
    echo "In OBS: Sources -> + -> Audio Input Capture (PulseAudio) -> pick \"${nick} Ch 01\" and so on."
    return 0
}

case "${1:-}" in
    start)  shift; start "$@" ;;
    watch)  shift; watch_loop "$@" ;;
    stop)   stop_all && echo "Channel sources removed" ;;
    status) status ;;
    ""|-h|--help) usage ;;
    *) usage; exit 2 ;;
esac
