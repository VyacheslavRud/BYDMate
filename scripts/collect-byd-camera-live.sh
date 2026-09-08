#!/usr/bin/env bash
#
# Read-only live AVM/camera capture for BYD/DiLink diagnostics.
#
# The script never clears logs, changes Android settings, starts or stops apps,
# sends Binder transactions, installs packages, requests root, or reboots the car.
# The operator switches the stock camera views while the script records logcat,
# screenshots, and read-only service snapshots.

set -uo pipefail

umask 077

SCRIPT_VERSION="1"
ADB_BIN="${ADB:-adb}"
SERIAL=""
OUTPUT_DIR=""
ASSUME_YES=false
CREATE_ARCHIVE=true
OBSERVE_SECONDS="10"
ROOT_DIR="$(cd -P "$(dirname "$0")/.." && pwd)"

LOGCAT_PID=""
BACKGROUND_STOPPED=false
LOGCAT_FAILURE_RECORDED=false
CAPTURE_SUCCEEDED=0
CAPTURE_FAILED=0

RELEVANT_REGEX='CamX|AVMCamera|AutoVideo|AVM|pano_h|panoram|SENSORLOCK|CameraDiagMask|MAX96712|SerDes|camera-provider|cameraserver|bydcamera|video.?signal|sensor.?lock|first.?frame|cam_power'
PROPERTY_REGEX='camera|camx|avm|pano|serdes|96712|video'
PRIVATE_PROPERTY_REGEX='serial|vin|imei|meid|mac|iccid|imsi|android[_-]?id|wifi|bluetooth'

usage() {
    cat <<'EOF'
Usage:
  ./scripts/collect-byd-camera-live.sh [options]

Options:
  --serial SERIAL       Use this exact ADB device serial/address.
  --output DIRECTORY    Save the capture to this new directory (parent must exist).
  --observe-seconds N   Observe each camera state for N seconds (default: 10).
  --yes                 Skip the initial LIVE confirmation.
  --no-archive          Do not create the final .tar.gz archive.
  -h, --help            Show this help.

The default output is:
  ~/Desktop/BYD-Camera-Live-YYYYMMDD_HHMMSS

Keep the vehicle stationary and parked in P. At each prompt, press Enter and
immediately select the requested stock camera view on the centre screen.

The collector is read-only. It does not restart camera services or alter the car.
EOF
}

log() {
    printf '[BYD camera live] %s\n' "$*"
}

warn() {
    printf '[BYD camera live] warning: %s\n' "$*" >&2
}

die() {
    printf '[BYD camera live] error: %s\n' "$*" >&2
    exit 1
}

while [ "$#" -gt 0 ]; do
    case "$1" in
        --serial)
            [ "$#" -ge 2 ] || die "--serial requires a value"
            SERIAL="$2"
            shift 2
            ;;
        --output)
            [ "$#" -ge 2 ] || die "--output requires a value"
            OUTPUT_DIR="$2"
            shift 2
            ;;
        --observe-seconds)
            [ "$#" -ge 2 ] || die "--observe-seconds requires a value"
            OBSERVE_SECONDS="$2"
            shift 2
            ;;
        --yes)
            ASSUME_YES=true
            shift
            ;;
        --no-archive)
            CREATE_ARCHIVE=false
            shift
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            die "unknown option: $1"
            ;;
    esac
done

case "$OBSERVE_SECONDS" in
    ''|*[!0-9]*)
        die "--observe-seconds must be a whole number from 0 to 60"
        ;;
esac
[ "$OBSERVE_SECONDS" -le 60 ] \
    || die "--observe-seconds must be a whole number from 0 to 60"

command -v "$ADB_BIN" >/dev/null 2>&1 || die "adb is not installed or not in PATH"
command -v tar >/dev/null 2>&1 || die "tar is not installed"

"$ADB_BIN" start-server </dev/null >/dev/null 2>&1 || die "could not start the ADB server"

if [ -z "$SERIAL" ]; then
    ONLINE_DEVICES="$("$ADB_BIN" devices </dev/null | awk 'NR > 1 && $2 == "device" { print $1 }')"
    ONLINE_COUNT="$(printf '%s\n' "$ONLINE_DEVICES" | awk 'NF { count++ } END { print count + 0 }')"
    case "$ONLINE_COUNT" in
        0)
            die "no online ADB device found; connect the car and run 'adb devices -l'"
            ;;
        1)
            SERIAL="$(printf '%s\n' "$ONLINE_DEVICES" | awk 'NF { print; exit }')"
            ;;
        *)
            printf '%s\n' "$ONLINE_DEVICES" >&2
            die "more than one online device found; rerun with --serial SERIAL"
            ;;
    esac
fi

DEVICE_STATE="$("$ADB_BIN" -s "$SERIAL" get-state </dev/null 2>/dev/null || true)"
[ "$DEVICE_STATE" = "device" ] \
    || die "ADB target '$SERIAL' is not online (state=${DEVICE_STATE:-unknown})"

adb_prop() {
    local property="$1"
    "$ADB_BIN" -s "$SERIAL" shell getprop "$property" </dev/null 2>/dev/null \
        | tr -d '\r' \
        | head -n 1
}

MANUFACTURER="$(adb_prop ro.product.manufacturer)"
MODEL="$(adb_prop ro.product.model)"
PRODUCT="$(adb_prop ro.product.name)"
FINGERPRINT="$(adb_prop ro.build.fingerprint)"
SDK="$(adb_prop ro.build.version.sdk)"
QEMU="$(adb_prop ro.kernel.qemu)"
IDENTITY_UPPER="$(printf '%s %s %s %s' "$MANUFACTURER" "$MODEL" "$PRODUCT" "$FINGERPRINT" \
    | tr '[:lower:]' '[:upper:]')"

case "$SERIAL:$QEMU" in
    emulator-*:*|*:1)
        die "refusing to collect from an emulator; connect the BYD head unit"
        ;;
esac

case "$IDENTITY_UPPER" in
    *BYD*|*DILINK*)
        ;;
    *)
        die "target does not identify itself as BYD/DiLink: manufacturer='$MANUFACTURER' model='$MODEL'"
        ;;
esac

printf '\nTarget confirmed by read-only properties:\n'
printf '  ADB serial:   %s\n' "$SERIAL"
printf '  Manufacturer: %s\n' "${MANUFACTURER:-unknown}"
printf '  Model:        %s\n' "${MODEL:-unknown}"
printf '  Product:      %s\n' "${PRODUCT:-unknown}"
printf '  Android SDK:  %s\n' "${SDK:-unknown}"
printf '  Fingerprint:  %s\n\n' "${FINGERPRINT:-unknown}"

if [ "$ASSUME_YES" != true ]; then
    [ -t 0 ] || die "interactive confirmation is unavailable; rerun manually or use --yes"
    printf 'The collector only READS runtime state. Keep the vehicle stationary and in P.\n'
    printf 'Type LIVE to continue: '
    read -r CONFIRMATION
    [ "$CONFIRMATION" = "LIVE" ] || die "cancelled"
fi

if [ -z "$OUTPUT_DIR" ]; then
    OUTPUT_DIR="$HOME/Desktop/BYD-Camera-Live-$(date +%Y%m%d_%H%M%S)"
fi

case "$OUTPUT_DIR" in
    /*) ;;
    *) OUTPUT_DIR="$(pwd)/$OUTPUT_DIR" ;;
esac

OUTPUT_PARENT_INPUT="$(dirname "$OUTPUT_DIR")"
OUTPUT_BASENAME="$(basename "$OUTPUT_DIR")"
case "$OUTPUT_BASENAME" in
    ''|.|..)
        die "output must name a new directory, not '$OUTPUT_BASENAME'"
        ;;
esac
[ -d "$OUTPUT_PARENT_INPUT" ] \
    || die "output parent directory does not exist: $OUTPUT_PARENT_INPUT"
OUTPUT_PARENT="$(cd -P "$OUTPUT_PARENT_INPUT" 2>/dev/null && pwd)" \
    || die "could not resolve output parent: $OUTPUT_PARENT_INPUT"
OUTPUT_DIR="$OUTPUT_PARENT/$OUTPUT_BASENAME"

case "$OUTPUT_DIR" in
    "$ROOT_DIR"|"$ROOT_DIR"/*)
        die "output must be outside the Git working tree: $ROOT_DIR"
        ;;
esac

[ ! -e "$OUTPUT_DIR" ] || die "output already exists: $OUTPUT_DIR"
if [ "$CREATE_ARCHIVE" = true ] && [ -e "$OUTPUT_DIR.tar.gz" ]; then
    die "archive already exists: $OUTPUT_DIR.tar.gz"
fi

mkdir -p "$OUTPUT_DIR"/{continuous,snapshots,metadata} \
    || die "could not create output directory: $OUTPUT_DIR"

EVENTS_FILE="$OUTPUT_DIR/events.tsv"
ERROR_LOG="$OUTPUT_DIR/capture-errors.log"
printf 'host_time_utc\tdevice_epoch\tdevice_uptime_seconds\tevent\tdetails\n' > "$EVENTS_FILE"
printf 'BYD live camera collector v%s\n' "$SCRIPT_VERSION" > "$ERROR_LOG"

device_epoch() {
    "$ADB_BIN" -s "$SERIAL" shell "date +%s" </dev/null 2>/dev/null \
        | tr -d '\r' \
        | head -n 1
}

device_uptime() {
    "$ADB_BIN" -s "$SERIAL" shell "cut -d' ' -f1 /proc/uptime" </dev/null 2>/dev/null \
        | tr -d '\r' \
        | head -n 1
}

record_event() {
    local event="$1"
    local details="${2:-}"
    local host_time
    local epoch
    local uptime
    host_time="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    epoch="$(device_epoch)"
    uptime="$(device_uptime)"
    printf '%s\t%s\t%s\t%s\t%s\n' \
        "$host_time" "${epoch:-unknown}" "${uptime:-unknown}" "$event" "$details" \
        >> "$EVENTS_FILE"
}

capture_shell() {
    local label="$1"
    local destination="$2"
    local command="$3"
    local stderr_file="$destination.stderr"

    if "$ADB_BIN" -s "$SERIAL" shell "$command" </dev/null \
        > "$destination" 2> "$stderr_file"; then
        tr -d '\r' < "$destination" > "$destination.normalized"
        mv "$destination.normalized" "$destination"
        if [ ! -s "$stderr_file" ]; then
            rm -f "$stderr_file"
        fi
        CAPTURE_SUCCEEDED=$((CAPTURE_SUCCEEDED + 1))
        return 0
    fi

    printf 'FAILED snapshot command: %s\n' "$label" >> "$ERROR_LOG"
    if [ -s "$stderr_file" ]; then
        tr -d '\r' < "$stderr_file" >> "$ERROR_LOG"
    fi
    CAPTURE_FAILED=$((CAPTURE_FAILED + 1))
    return 1
}

capture_screenshot() {
    local destination="$1"
    local stderr_file="$destination.stderr"

    if "$ADB_BIN" -s "$SERIAL" exec-out screencap -p </dev/null \
        > "$destination" 2> "$stderr_file" \
        && [ -s "$destination" ]; then
        if [ ! -s "$stderr_file" ]; then
            rm -f "$stderr_file"
        fi
        CAPTURE_SUCCEEDED=$((CAPTURE_SUCCEEDED + 1))
        return 0
    fi

    printf 'FAILED snapshot command: centre-screen screenshot\n' >> "$ERROR_LOG"
    if [ -s "$stderr_file" ]; then
        tr -d '\r' < "$stderr_file" >> "$ERROR_LOG"
    fi
    CAPTURE_FAILED=$((CAPTURE_FAILED + 1))
    return 1
}

capture_snapshot() {
    local snapshot_name="$1"
    local snapshot_dir="$OUTPUT_DIR/snapshots/$snapshot_name"
    mkdir -p "$snapshot_dir"

    log "snapshot: $snapshot_name"
    record_event SNAPSHOT_BEGIN "$snapshot_name"

    capture_screenshot "$snapshot_dir/centre-screen.png" || true
    capture_shell "device clocks" "$snapshot_dir/device-clocks.txt" \
        "date; printf 'epoch='; date +%s; printf 'uptime='; cut -d' ' -f1 /proc/uptime" || true
    capture_shell "media.camera" "$snapshot_dir/dumpsys-media-camera.txt" \
        "dumpsys media.camera" || true
    capture_shell "media.camera.proxy" "$snapshot_dir/dumpsys-media-camera-proxy.txt" \
        "dumpsys media.camera.proxy" || true
    capture_shell "BYD camera manager" "$snapshot_dir/dumpsys-bydcameramanager.txt" \
        "dumpsys bydcameramanager" || true
    capture_shell "BMM camera server" "$snapshot_dir/dumpsys-bmmcameraserver.txt" \
        "dumpsys bmmcameraserver" || true
    capture_shell "BYD diagnostic service" "$snapshot_dir/dumpsys-diagnosticsrv.txt" \
        "dumpsys diagnosticsrv" || true
    capture_shell "focused activity" "$snapshot_dir/activity-top.txt" \
        "dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity|ResumedActivity|TaskRecord|ActivityRecord' || true" || true
    capture_shell "window focus" "$snapshot_dir/window-focus.txt" \
        "dumpsys window windows | grep -E 'mCurrentFocus|mFocusedApp|mObscuringWindow|Window #' || true" || true
    capture_shell "camera-related surfaces" "$snapshot_dir/surface-layers.txt" \
        "dumpsys SurfaceFlinger --list | grep -Ei '$RELEVANT_REGEX' || true" || true
    capture_shell "camera processes" "$snapshot_dir/processes.txt" \
        "ps -Aw -o PID,USER,NAME,ARGS 2>/dev/null | grep -Ei '$RELEVANT_REGEX' || ps -A | grep -Ei '$RELEVANT_REGEX' || true" || true
    capture_shell "camera-related properties" "$snapshot_dir/relevant-properties.txt" \
        "getprop | grep -Ei '$PROPERTY_REGEX' | grep -Evi '$PRIVATE_PROPERTY_REGEX' || true" || true
    capture_shell "camera power-supervisor permissions" "$snapshot_dir/cam-power-supervise.txt" \
        "ls -l /dev/cam_power_supervise 2>&1 || true" || true

    record_event SNAPSHOT_END "$snapshot_name"
}

stop_background() {
    [ "$BACKGROUND_STOPPED" = false ] || return 0
    BACKGROUND_STOPPED=true

    if [ -n "$LOGCAT_PID" ] && kill -0 "$LOGCAT_PID" 2>/dev/null; then
        kill "$LOGCAT_PID" 2>/dev/null || true
    fi
    if [ -n "$LOGCAT_PID" ]; then
        wait "$LOGCAT_PID" 2>/dev/null || true
    fi
}

cleanup() {
    stop_background
}

handle_signal() {
    warn "capture interrupted; partial files remain in $OUTPUT_DIR"
    exit 130
}

check_logcat() {
    if [ "$LOGCAT_FAILURE_RECORDED" = true ]; then
        return 1
    fi
    if [ -n "$LOGCAT_PID" ] && kill -0 "$LOGCAT_PID" 2>/dev/null; then
        return 0
    fi

    printf 'FAILED continuous channel: logcat stopped unexpectedly\n' >> "$ERROR_LOG"
    CAPTURE_FAILED=$((CAPTURE_FAILED + 1))
    LOGCAT_FAILURE_RECORDED=true
    return 1
}

wait_for_enter() {
    read -r _ || die "interactive input closed before the capture was complete"
}

observe_step() {
    local step_number="$1"
    local snapshot_name="$2"
    local event_name="$3"
    local instruction="$4"

    printf '\n[%s/7] %s\n' "$step_number" "$instruction"
    printf '      Press Enter, perform the action immediately, then leave that view open: '
    wait_for_enter
    record_event "${event_name}_BEGIN" "$instruction"
    printf '      Recording for %s seconds...\n' "$OBSERVE_SECONDS"
    sleep "$OBSERVE_SECONDS"
    check_logcat || true
    record_event "${event_name}_OBSERVED" "$instruction"
    capture_snapshot "$snapshot_name"
}

trap cleanup EXIT
trap handle_signal INT TERM

{
    printf 'collector_version=%s\n' "$SCRIPT_VERSION"
    printf 'collected_at=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    printf 'adb_serial=%s\n' "$SERIAL"
    printf 'ro.product.manufacturer=%s\n' "$MANUFACTURER"
    printf 'ro.product.model=%s\n' "$MODEL"
    printf 'ro.product.name=%s\n' "$PRODUCT"
    printf 'ro.build.version.sdk=%s\n' "$SDK"
    printf 'ro.build.fingerprint=%s\n' "$FINGERPRINT"
    printf 'observe_seconds=%s\n' "$OBSERVE_SECONDS"
} > "$OUTPUT_DIR/metadata/device.txt"

capture_shell "camera package paths" "$OUTPUT_DIR/metadata/relevant-packages.txt" \
    "pm list packages -f -U | grep -Ei '$RELEVANT_REGEX' || true" || true
capture_shell "camera Binder services" "$OUTPUT_DIR/metadata/relevant-binder-services.txt" \
    "service list | grep -Ei '$RELEVANT_REGEX' || true" || true
capture_shell "camera device nodes" "$OUTPUT_DIR/metadata/camera-device-nodes.txt" \
    "ls -l /dev/video* /dev/v4l* /dev/cam* 2>&1 || true" || true

log "starting continuous logcat"
record_event SESSION_START "continuous camera logcat starting"

"$ADB_BIN" -s "$SERIAL" logcat \
    -b main -b system -b crash \
    -v threadtime -T 1 </dev/null \
    > "$OUTPUT_DIR/continuous/logcat-full.txt" \
    2> "$OUTPUT_DIR/continuous/logcat-stderr.txt" &
LOGCAT_PID=$!

printf '\nContinuous recording is active. Do not disconnect ADB.\n'
printf 'After pressing Enter at a camera step, switch the stock camera view immediately.\n'
printf 'Do not touch the view until the script reports that its snapshot is complete.\n'

observe_step \
    "1" \
    "01-baseline-camera-closed" \
    "BASELINE" \
    "Close the stock camera/360 app and leave the ordinary DiLink screen visible."
observe_step \
    "2" \
    "02-avm-overview-initial" \
    "AVM_OVERVIEW_INITIAL" \
    "Open the stock 360/AVM overview."
observe_step \
    "3" \
    "03-front-view" \
    "FRONT_VIEW" \
    "Select the front-camera view."
observe_step \
    "4" \
    "04-rear-view" \
    "REAR_VIEW" \
    "Select the rear-camera view."
observe_step \
    "5" \
    "05-left-view" \
    "LEFT_VIEW" \
    "Select the left-camera view."
observe_step \
    "6" \
    "06-right-view" \
    "RIGHT_VIEW" \
    "Select the right-camera view."
observe_step \
    "7" \
    "07-avm-overview-final" \
    "AVM_OVERVIEW_FINAL" \
    "Return to the stock 360/AVM overview."

record_event SESSION_STOP "continuous camera logcat stopping"
stop_background

grep -Ei "$RELEVANT_REGEX" "$OUTPUT_DIR/continuous/logcat-full.txt" \
    > "$OUTPUT_DIR/continuous/logcat-camera-relevant.txt" || true

for stderr_file in "$OUTPUT_DIR"/continuous/*-stderr.txt; do
    [ -s "$stderr_file" ] || rm -f "$stderr_file"
done

if [ "$CAPTURE_FAILED" -eq 0 ]; then
    STATUS="COMPLETE"
else
    STATUS="PARTIAL"
fi

cat > "$OUTPUT_DIR/README.txt" <<EOF
BYD live AVM/camera capture

Collector version: $SCRIPT_VERSION
Status: $STATUS
ADB serial: $SERIAL
Vehicle: ${MANUFACTURER:-unknown} ${MODEL:-unknown}
Android SDK: ${SDK:-unknown}
Observation time per state: $OBSERVE_SECONDS seconds
Successful snapshot commands: $CAPTURE_SUCCEEDED
Failed snapshot commands: $CAPTURE_FAILED

The capture is read-only. Event markers are in events.tsv. The continuous logcat
and its camera-focused filter are under continuous/. Seven state snapshots,
including a centre-screen screenshot, are under snapshots/.

The screenshots show the rendered centre-screen output; this collector does not
extract raw pano_h buffers or directly read /dev/cam_power_supervise.

This output is private. It may contain surroundings, license plates, locations,
package names, foreground activities, or other runtime details. Do not publish it
or commit it to Git.
EOF

ARCHIVE_PATH="$OUTPUT_DIR.tar.gz"
ARCHIVE_CREATED=false
if [ "$CREATE_ARCHIVE" = true ]; then
    log "creating private archive"
    if tar -czf "$ARCHIVE_PATH" -C "$OUTPUT_PARENT" "$OUTPUT_BASENAME"; then
        ARCHIVE_CREATED=true
    else
        warn "archive creation failed; the capture folder is still complete"
    fi
fi

printf '\nCapture finished.\n'
printf '  Status:  %s\n' "$STATUS"
printf '  Folder:  %s\n' "$OUTPUT_DIR"
if [ "$ARCHIVE_CREATED" = true ]; then
    printf '  Archive: %s\n' "$ARCHIVE_PATH"
fi
printf '\nSend the .tar.gz archive privately for analysis. If archive creation failed,\n'
printf 'send README.txt, events.tsv, and capture-errors.log while keeping the folder intact.\n'
