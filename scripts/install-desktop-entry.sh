#!/usr/bin/env bash
#
# Gives KFS a proper place on a GNOME desktop: an application-grid entry and a
# Desktop shortcut carrying the app's own icon, and a dock icon that is that
# same icon rather than the generic gears. GNOME matches a running window to
# its launcher through the window's WM_CLASS, which for a JavaFX app is the
# name of the Application subclass - the JavaFX launcher stamps it before
# start() runs and nothing can override it - so the .desktop entry written
# here declares exactly that. Renaming or moving StreamingGUI silently breaks
# the match; re-run this script with the constant below updated if that ever
# happens.
#
# Everything is installed per-user (no sudo): the icon into the hicolor theme,
# the launcher into ~/.local/share/applications, a copy onto the Desktop, and
# a ~/.local/bin/kfs wrapper that always starts the newest jar, so a rebuild -
# whose jar name changes with every commit - needs no reinstall.
#
#   ./install-desktop-entry.sh
#
set -euo pipefail

WM_CLASS=org.kadampa.festivalstreaming.StreamingGUI
APP_NAME="Kadampa Festival Streaming"

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
REPO_DIR=$(dirname "$SCRIPT_DIR")
SOURCE_ICON="$REPO_DIR/src/main/resources/live-streaming.png"

if [[ ! -f "$SOURCE_ICON" ]]; then
    echo "Cannot find $SOURCE_ICON - is the repository intact?" >&2
    exit 1
fi

# A GUI session's PATH is not the shell's, so the wrapper bakes in whichever
# java is usable now instead of hoping the dock will find one later. The same
# reasoning Host.java applies to ffmpeg.
JAVA_BIN=$(command -v java || true)
if [[ -z "$JAVA_BIN" ]]; then
    echo "No java on the PATH - install a JDK 17 first." >&2
    exit 1
fi

# --- Icon: the tall live-streaming logo, centred on a transparent square, ---
# --- rendered at the sizes the hicolor theme expects                      ---
render() {
    local size=$1 out=$2
    if command -v magick >/dev/null 2>&1 || command -v convert >/dev/null 2>&1; then
        local im; im=$(command -v magick || command -v convert)
        "$im" "$SOURCE_ICON" -background none -gravity center \
              -extent 1048x1048 -resize "${size}x${size}" "$out"
    else
        # ffmpeg is a hard dependency of the app itself, so it is always here
        ffmpeg -loglevel error -y -i "$SOURCE_ICON" \
            -vf "format=rgba,pad=1048:1048:(ow-iw)/2:(oh-ih)/2:color=black@0,scale=${size}:${size}" \
            "$out"
    fi
}

for size in 48 64 128 256 512; do
    dir="$HOME/.local/share/icons/hicolor/${size}x${size}/apps"
    mkdir -p "$dir"
    render "$size" "$dir/kfs.png"
done
echo "Icon installed into ~/.local/share/icons/hicolor (48-512 px)."

# --- Wrapper: always the newest jar, however the commit count renames it ----
mkdir -p "$HOME/.local/bin"
cat > "$HOME/.local/bin/kfs" <<EOF
#!/usr/bin/env bash
# Starts the newest KFS build; written by scripts/install-desktop-entry.sh.
# The jar name embeds the commit count, so the newest by time is the build
# just made - and the glob cannot catch the shade plugin's original-*.jar.
JAR=\$(ls -t "$REPO_DIR"/target/KFS-*.jar 2>/dev/null | head -n 1)
if [[ -z "\$JAR" ]]; then
    echo "No jar under $REPO_DIR/target - run: mvn clean package" >&2
    exit 1
fi
exec "$JAVA_BIN" -jar "\$JAR"
EOF
chmod +x "$HOME/.local/bin/kfs"
echo "Launcher written to ~/.local/bin/kfs."

# --- The .desktop entry, in the app grid and copied onto the Desktop --------
# StartupNotify stays off because JavaFX never completes startup notification,
# which would otherwise leave a spinning cursor for half a minute.
APPS_DIR="$HOME/.local/share/applications"
mkdir -p "$APPS_DIR"
cat > "$APPS_DIR/kfs.desktop" <<EOF
[Desktop Entry]
Type=Application
Name=$APP_NAME
Comment=Livestream and record festival sessions
Exec=$HOME/.local/bin/kfs
Icon=kfs
Terminal=false
Categories=AudioVideo;Audio;Video;
Keywords=streaming;festival;ffmpeg;kadampa;
StartupNotify=false
StartupWMClass=$WM_CLASS
EOF

DESKTOP_DIR=$(xdg-user-dir DESKTOP 2>/dev/null || echo "$HOME/Desktop")
if [[ -d "$DESKTOP_DIR" ]]; then
    cp "$APPS_DIR/kfs.desktop" "$DESKTOP_DIR/kfs.desktop"
    # GNOME's desktop-icons extension only launches a shortcut that is both
    # executable and marked trusted; either alone still shows a warning badge
    chmod +x "$DESKTOP_DIR/kfs.desktop"
    gio set "$DESKTOP_DIR/kfs.desktop" metadata::trusted true 2>/dev/null \
        || echo "Could not mark the Desktop shortcut trusted (no session bus?); right-click it and choose 'Allow Launching'."
    echo "Shortcut placed on the Desktop."
fi

update-desktop-database "$APPS_DIR" 2>/dev/null || true
gtk-update-icon-cache -f -t "$HOME/.local/share/icons/hicolor" 2>/dev/null || true

# The in-app dock attention while streaming needs one of these; the app warns
# in its log too, but here is the moment the operator can act on it.
if ! command -v wmctrl >/dev/null 2>&1 && ! command -v xdotool >/dev/null 2>&1; then
    echo "Note: install wmctrl (sudo apt install wmctrl) so the dock icon can"
    echo "      signal attention while the stream is running."
fi

echo "Done. The entry appears in the app grid as \"$APP_NAME\"."
