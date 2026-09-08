#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd -P "$(dirname "$0")/../.." && pwd)"
TEST_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/bydmate-camera-live-test.XXXXXX")"
trap 'rm -rf "$TEST_ROOT"' EXIT

FAKE_ADB="$TEST_ROOT/adb"

cat > "$FAKE_ADB" <<'ADB'
#!/usr/bin/env bash
set -u

if [ "${1:-}" = "start-server" ]; then exit 0; fi
if [ "${1:-}" = "devices" ]; then
    printf 'List of devices attached\nsea-lion-test\tdevice product:DiLink5.0 model:DiLink5_0_For_BYD_AUTO\n'
    exit 0
fi
if [ "${1:-}" = "-s" ]; then shift 2; fi
if [ "${1:-}" = "get-state" ]; then printf 'device\n'; exit 0; fi
if [ "${1:-}" = "logcat" ]; then
    printf '07-23 10:00:00.000 E/CamX: cameraid=3 SENSORLOCK customType=15\n'
    printf '07-23 10:00:00.100 I/AVMCamera: CameraDiagMask mask=0\n'
    printf '07-23 10:00:00.200 E/MAX96712: AVM video signal exception\n'
    while true; do sleep 1; done
fi
if [ "${1:-}" = "exec-out" ] && [ "${2:-}" = "screencap" ]; then
    printf '\211PNG\r\n\032\nmock-camera-screen'
    exit 0
fi
if [ "${1:-}" != "shell" ]; then exit 1; fi
shift
command="$*"

case "$command" in
    "getprop ro.product.manufacturer") printf '%s\n' "${FAKE_MANUFACTURER:-BYD}" ;;
    "getprop ro.product.model") printf '%s\n' "${FAKE_MODEL:-DiLink5.0 For BYD AUTO}" ;;
    "getprop ro.product.name") printf 'DiLink5.0\n' ;;
    "getprop ro.build.fingerprint")
        printf 'BYD-AUTO/DiLink5.0/test:12/mock/user/release-keys\n'
        ;;
    "getprop ro.build.version.sdk") printf '32\n' ;;
    "getprop ro.kernel.qemu") printf '%s\n' "${FAKE_QEMU:-0}" ;;
    "date +%s") printf '1784793600\n' ;;
    "cut -d' ' -f1 /proc/uptime") printf '123.45\n' ;;
    *"dumpsys media.camera"*) printf 'Camera ID 3 pano_h 7680x1300\n' ;;
    *"dumpsys bydcameramanager"*) printf 'BYD camera manager mock\n' ;;
    *"dumpsys bmmcameraserver"*) printf 'BMM camera server mock\n' ;;
    *"dumpsys diagnosticsrv"*) printf 'diagnostic service mock\n' ;;
    *"dumpsys SurfaceFlinger --list"*) printf 'com.byd.avc.AutoVideoActivity\n' ;;
    *"date; printf"*) printf 'mock device clock\nepoch=1784793600\nuptime=123.45\n' ;;
    *) printf 'mock: %s\n' "$command" ;;
esac
ADB
chmod +x "$FAKE_ADB"

OUTPUT_DIR="$TEST_ROOT/BYD-Camera-Live-complete"
printf '\n\n\n\n\n\n\n' | ADB="$FAKE_ADB" \
    "$ROOT_DIR/scripts/collect-byd-camera-live.sh" \
    --serial sea-lion-test \
    --output "$OUTPUT_DIR" \
    --observe-seconds 0 \
    --yes >/dev/null

grep -Fq 'Status: COMPLETE' "$OUTPUT_DIR/README.txt"
grep -Fq $'LEFT_VIEW_BEGIN\tSelect the left-camera view.' "$OUTPUT_DIR/events.tsv"
grep -Fq $'RIGHT_VIEW_OBSERVED\tSelect the right-camera view.' "$OUTPUT_DIR/events.tsv"
grep -Fq 'SENSORLOCK customType=15' "$OUTPUT_DIR/continuous/logcat-camera-relevant.txt"
grep -Fq 'MAX96712' "$OUTPUT_DIR/continuous/logcat-camera-relevant.txt"
grep -Fq 'Camera ID 3 pano_h 7680x1300' \
    "$OUTPUT_DIR/snapshots/05-left-view/dumpsys-media-camera.txt"
test -s "$OUTPUT_DIR/snapshots/01-baseline-camera-closed/centre-screen.png"
test -s "$OUTPUT_DIR/snapshots/05-left-view/centre-screen.png"
test -s "$OUTPUT_DIR/snapshots/07-avm-overview-final/centre-screen.png"
test -s "$OUTPUT_DIR.tar.gz"
tar -tzf "$OUTPUT_DIR.tar.gz" \
    | grep -Fq 'BYD-Camera-Live-complete/snapshots/05-left-view/centre-screen.png'

REPO_OUTPUT="$ROOT_DIR/.camera-live-guard-test-$$"
if printf '\n\n\n\n\n\n\n' | ADB="$FAKE_ADB" \
    "$ROOT_DIR/scripts/collect-byd-camera-live.sh" \
    --serial sea-lion-test \
    --output "$REPO_OUTPUT" \
    --observe-seconds 0 \
    --yes --no-archive >/dev/null 2>&1; then
    printf 'camera collector accepted an output path inside the repository\n' >&2
    exit 1
fi
test ! -e "$REPO_OUTPUT"

if printf '\n\n\n\n\n\n\n' | FAKE_QEMU=1 ADB="$FAKE_ADB" \
    "$ROOT_DIR/scripts/collect-byd-camera-live.sh" \
    --serial emulator-5554 \
    --output "$TEST_ROOT/emulator-output" \
    --observe-seconds 0 \
    --yes --no-archive >/dev/null 2>&1; then
    printf 'camera collector accepted an emulator\n' >&2
    exit 1
fi
test ! -e "$TEST_ROOT/emulator-output"

printf 'live camera collector regression suite: PASS\n'
