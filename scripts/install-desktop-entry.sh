#!/usr/bin/env bash
#
# Gives KFS its place on a GNOME desktop: three launchers over one jar, each
# carrying its own icon and its own settings folder, in the app grid and on the
# Desktop.
#
#   KFS Livestreaming -> <param root>/Livestreaming
#   KFS Recording     -> <param root>/Recording
#   KFS Testing       -> <param root>/Testing
#
# One machine can run several configurations because Host.userDataDir() reads
# -Dkfs.dataDir, so each launcher passes its own folder and that copy keeps its
# settings.ini, and unpacks its noise models, in there alone.
#
# A fourth entry, kfs.desktop, is installed hidden. GNOME matches a running
# window to its launcher through the window's WM_CLASS, which for a JavaFX app
# is the name of the Application subclass - the JavaFX launcher stamps it before
# start() runs and nothing can override it - and one WM_CLASS can only belong to
# one entry: three visible launchers all claiming it would leave the shell to
# pick a winner, and the dock would name the wrong configuration two times in
# three. So the hidden entry owns the match, and the dock says only that KFS is
# running. Renaming or moving StreamingGUI silently breaks the match; re-run
# this script with the constant below updated if that ever happens.
#
# Everything is installed per-user (no sudo): the icons into the hicolor theme,
# the launchers into ~/.local/share/applications, copies onto the Desktop, and
# a ~/.local/bin/kfs wrapper that always starts the newest jar, so a rebuild -
# whose jar name changes with every commit - needs no reinstall.
#
#   ./install-desktop-entry.sh
#
# The settings root defaults to the folder the festival machine uses; set
# KFS_PARAM_ROOT to install the same three launchers somewhere else.
#
set -euo pipefail

WM_CLASS=org.kadampa.festivalstreaming.StreamingGUI
APP_NAME="Kadampa Festival Streaming"

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
REPO_DIR=$(dirname "$SCRIPT_DIR")

# id | name in the grid | settings folder | source icon | comment
# The names stay short because GNOME's desktop icons are set small here and a
# long one is truncated to noise.
PROFILES=(
    "livestreaming|KFS Livestreaming|Livestreaming|src/main/resources/live-streaming.png|Livestream the session, recording alongside it"
    "recording|KFS Recording|Recording|src/main/resources/recording-idle.png|Record the session to a file"
    "testing|KFS Testing|Testing|scripts/kfs-testing.png|Try settings out without touching the other two"
)

# The icon behind the hidden entry, and so the one the dock shows.
DOCK_ICON="$REPO_DIR/src/main/resources/live-streaming.png"

PARAM_ROOT="${KFS_PARAM_ROOT:-$HOME/Documents/KFS/Parameters}"

# A space here would reach an ffmpeg filter string by way of the rnnoise model
# paths under the data dir, and the failure would surface at Start, in front of
# an audience, rather than at launch. Host.userDataDir()'s javadoc says the same.
case "$PARAM_ROOT" in
    *" "*)
        echo "The settings root must not contain a space: $PARAM_ROOT" >&2
        echo "Set KFS_PARAM_ROOT to a path without one." >&2
        exit 1
        ;;
esac

for entry in "${PROFILES[@]}"; do
    IFS='|' read -r _ _ _ icon _ <<<"$entry"
    if [[ ! -f "$REPO_DIR/$icon" ]]; then
        echo "Cannot find $REPO_DIR/$icon - is the repository intact?" >&2
        exit 1
    fi
done

# A GUI session's PATH is not the shell's, so the wrapper bakes in whichever
# java is usable now instead of hoping the dock will find one later. The same
# reasoning Host.java applies to ffmpeg.
JAVA_BIN=$(command -v java || true)
if [[ -z "$JAVA_BIN" ]]; then
    echo "No java on the PATH - install a JDK 17 first." >&2
    exit 1
fi

# --- Icons: each logo centred on a transparent square of its own longest ---
# --- side, rendered at the sizes the hicolor theme expects               ---
# The square is measured rather than given: the three sources are 860x1048,
# 1000x1080 and 1024x1024, and a constant that suits one crops another.
render() {
    local src=$1 size=$2 out=$3
    if command -v magick >/dev/null 2>&1 || command -v convert >/dev/null 2>&1; then
        local im square
        im=$(command -v magick || command -v convert)
        square=$("$im" "$src" -format '%[fx:max(w,h)]' info:)
        "$im" "$src" -background none -gravity center \
              -extent "${square}x${square}" -resize "${size}x${size}" "$out"
    else
        # ffmpeg is a hard dependency of the app itself, so it is always here
        ffmpeg -loglevel error -y -i "$src" \
            -vf "format=rgba,pad=w=max(iw\\,ih):h=max(iw\\,ih):x=(ow-iw)/2:y=(oh-ih)/2:color=black@0,scale=${size}:${size}" \
            "$out"
    fi
}

for size in 48 64 128 256 512; do
    dir="$HOME/.local/share/icons/hicolor/${size}x${size}/apps"
    mkdir -p "$dir"
    render "$DOCK_ICON" "$size" "$dir/kfs.png"
    for entry in "${PROFILES[@]}"; do
        IFS='|' read -r id _ _ icon _ <<<"$entry"
        render "$REPO_DIR/$icon" "$size" "$dir/kfs-${id}.png"
    done
done
echo "Icons installed into ~/.local/share/icons/hicolor (48-512 px)."

# --- The settings folders, left empty: each configuration writes its own -----
# --- settings.ini the first time it is saved                            -----
for entry in "${PROFILES[@]}"; do
    IFS='|' read -r _ _ leaf _ _ <<<"$entry"
    mkdir -p "$PARAM_ROOT/$leaf"
done
echo "Settings folders ready under $PARAM_ROOT."

# --- Wrapper: one jar, one configuration per argument ------------------------
mkdir -p "$HOME/.local/bin"
cat > "$HOME/.local/bin/kfs" <<EOF
#!/usr/bin/env bash
# Starts KFS in one of its configurations; written by
# scripts/install-desktop-entry.sh.
#
#   kfs                 the app's own settings folder, as it always was
#   kfs livestreaming   -Dkfs.dataDir=$PARAM_ROOT/Livestreaming
#   kfs recording       ... /Recording
#   kfs testing         ... /Testing
#
PARAM_ROOT="$PARAM_ROOT"

# A double-clicked icon has nowhere to show stderr, so say it where it shows.
fail() {
    echo "\$1" >&2
    command -v notify-send >/dev/null 2>&1 && notify-send -u critical "KFS" "\$1"
    exit 1
}

case "\${1:-}" in
    livestreaming) DATA_DIR="\$PARAM_ROOT/Livestreaming" ;;
    recording)     DATA_DIR="\$PARAM_ROOT/Recording" ;;
    testing)       DATA_DIR="\$PARAM_ROOT/Testing" ;;
    "")            DATA_DIR="" ;;
    *)             fail "Unknown configuration '\$1' - livestreaming, recording or testing." ;;
esac

# KFS.jar is refreshed by every "mvn package" on unix. The glob stays behind it
# as the fallback, for a link that is absent or dangling while a jar is not: a
# build with the profile switched off, a jar from before the link existed. It
# cannot catch the shade plugin's original-*.jar.
JAR="$REPO_DIR/KFS.jar"
if [[ ! -f "\$JAR" ]]; then
    JAR=\$(ls -t "$REPO_DIR"/target/KFS-*.jar 2>/dev/null | head -n 1)
fi
if [[ -z "\$JAR" ]]; then
    fail "No jar under $REPO_DIR - run: mvn clean package"
fi

if [[ -z "\$DATA_DIR" ]]; then
    exec "$JAVA_BIN" -jar "\$JAR"
fi

[[ -d "\$DATA_DIR" ]] || fail "\$DATA_DIR does not exist - re-run scripts/install-desktop-entry.sh"
# SettingsUtil falls back to a settings.ini in the working directory when the
# data dir has none yet, so start in the data dir: a stray file in \$HOME must
# not quietly become all three configurations' settings.
cd "\$DATA_DIR"
# -D before -jar. After it the JVM hands the word to the application, and
# nothing in KFS reads its arguments, so a misplaced property is ignored in
# silence and the configuration shares the default folder after all.
exec "$JAVA_BIN" -Dkfs.dataDir="\$DATA_DIR" -jar "\$JAR"
EOF
chmod +x "$HOME/.local/bin/kfs"
echo "Launcher written to ~/.local/bin/kfs."

# --- The .desktop entries, in the app grid and copied onto the Desktop -------
# StartupNotify stays off because JavaFX never completes startup notification,
# which would otherwise leave a spinning cursor for half a minute.
APPS_DIR="$HOME/.local/share/applications"
DESKTOP_DIR=$(xdg-user-dir DESKTOP 2>/dev/null || echo "$HOME/Desktop")
mkdir -p "$APPS_DIR"

# Terminal defaults to false because the app itself is a window; the one entry
# that passes true is the camera fan-out, which has nothing but its output.
write_entry() {
    local file=$1 name=$2 comment=$3 exec_line=$4 icon=$5 extra=$6 terminal=${7:-false}
    cat > "$file" <<EOF
[Desktop Entry]
Type=Application
Name=$name
Comment=$comment
Exec=$exec_line
Icon=$icon
Terminal=$terminal
Categories=AudioVideo;Audio;Video;
Keywords=streaming;festival;ffmpeg;kadampa;
StartupNotify=false
$extra
EOF
}

# GNOME's desktop-icons extension only launches a shortcut that is both
# executable and marked trusted; either alone still shows a warning badge.
place_on_desktop() {
    local file=$1 name=$2 leaf
    [[ -d "$DESKTOP_DIR" ]] || return 0
    leaf=$(basename "$file")
    cp "$file" "$DESKTOP_DIR/$leaf"
    chmod +x "$DESKTOP_DIR/$leaf"
    gio set "$DESKTOP_DIR/$leaf" metadata::trusted true 2>/dev/null \
        || echo "Could not mark $name trusted (no session bus?); right-click it and choose 'Allow Launching'."
}

for entry in "${PROFILES[@]}"; do
    IFS='|' read -r id name leaf _ comment <<<"$entry"
    write_entry "$APPS_DIR/kfs-${id}.desktop" "$name" \
        "$comment - settings in $PARAM_ROOT/$leaf" \
        "$HOME/.local/bin/kfs $id" "kfs-${id}" ""
    place_on_desktop "$APPS_DIR/kfs-${id}.desktop" "$name"
done
echo "Three launchers installed, and placed on the Desktop."

# --- The camera fan-out, which two instances at once depend on ---------------
# The only entry here that opens a terminal, and the only one that is not the
# app: it runs until it is stopped, it says what it is doing, and Ctrl-C in that
# window is how an operator ends it. Deliberately in the foreground rather than
# a background service - a copy running unnoticed is a copy still holding the
# OBS camera when somebody wants it back. Why it exists at all is under "Two
# cameras from one OBS" in docs/debugging-linux.md.
# A stock icon name: camera-video lives only in the HighContrast theme here and
# would draw as gears under Adwaita, camera-web is in Adwaita itself.
if [[ -x "$REPO_DIR/scripts/vcam-fanout.sh" ]]; then
    write_entry "$APPS_DIR/kfs-cameras.desktop" "KFS Camera Fan-out" \
        "Copy the OBS Virtual Camera into one camera per KFS instance - start this before the launchers" \
        "$REPO_DIR/scripts/vcam-fanout.sh watch" "camera-web" "" true
    place_on_desktop "$APPS_DIR/kfs-cameras.desktop" "KFS Camera Fan-out"
    echo "Camera fan-out launcher installed (only needed when running two instances at once)."
else
    echo "Note: scripts/vcam-fanout.sh is missing or not executable - no fan-out launcher."
fi

# The entry nothing shows: it exists to own the WM_CLASS, so that the dock has
# an icon to draw and WindowAttention has a launcher to highlight.
write_entry "$APPS_DIR/kfs.desktop" "$APP_NAME" \
    "Livestream and record festival sessions" \
    "$HOME/.local/bin/kfs" "kfs" "NoDisplay=true
StartupWMClass=$WM_CLASS"

# The single shortcut this script used to install would now sit among the three
# as a fourth, launching none of the configurations. Remove it, but only where
# it is recognisably the one written here.
OLD_SHORTCUT="$DESKTOP_DIR/kfs.desktop"
if [[ -f "$OLD_SHORTCUT" ]] && grep -qxF "Exec=$HOME/.local/bin/kfs" "$OLD_SHORTCUT"; then
    rm -f "$OLD_SHORTCUT"
    echo "Removed the previous single shortcut from the Desktop."
fi

update-desktop-database "$APPS_DIR" 2>/dev/null || true
gtk-update-icon-cache -f -t "$HOME/.local/share/icons/hicolor" 2>/dev/null || true

# The in-app dock attention while streaming needs one of these; the app warns
# in its log too, but here is the moment the operator can act on it.
if ! command -v wmctrl >/dev/null 2>&1 && ! command -v xdotool >/dev/null 2>&1; then
    echo "Note: install wmctrl (sudo apt install wmctrl) so the dock icon can"
    echo "      signal attention while the stream is running."
fi

echo "Done. Four entries appear in the app grid: KFS Livestreaming, KFS Recording, KFS Testing,"
echo "and KFS Camera Fan-out - which only a two-instance session needs, started before them."
echo "A brand-new icon name occasionally draws as gears until the shell is restarted"
echo "(Alt+F2 then r on X11, or log out and back in on Wayland) - the install is fine."
