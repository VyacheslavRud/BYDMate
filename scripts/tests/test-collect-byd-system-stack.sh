#!/usr/bin/env bash

# Regression suite for collector v3. The fake adb deliberately drains stdin, emits a harmless
# successful-command warning, exposes split APK paths containing `~`, and can fail selected pulls.

set -euo pipefail

ROOT_DIR="$(cd -P "$(dirname "$0")/../.." && pwd)"
TEST_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/bydmate-collector-test.XXXXXX")"
trap 'rm -rf "$TEST_ROOT"' EXIT

FAKE_ADB="$TEST_ROOT/adb"
OUTPUT_DIR="$TEST_ROOT/BYD-System-Dump-complete"

cat > "$FAKE_ADB" <<'ADB'
#!/usr/bin/env bash
set -u

# A real adb client may read stdin. Draining it here makes the candidate-package loop regression
# deterministic: without </dev/null, the first package consumes every remaining package name.
while IFS= read -r _; do :; done

if [ "${1:-}" = "start-server" ]; then exit 0; fi
if [ "${1:-}" = "devices" ]; then
    printf 'List of devices attached\nsea-lion-test\tdevice product:DiLink5.0 model:DiLink5_0_For_BYD_AUTO\n'
    exit 0
fi
if [ "${1:-}" = "-s" ]; then shift 2; fi
if [ "${1:-}" = "get-state" ]; then printf 'device\n'; exit 0; fi

if [ "${1:-}" = "pull" ]; then
    remote="$2"
    destination="$3"
    if [ "${FAKE_FAIL_ALL_PULLS:-0}" = "1" ] || {
        [ -n "${FAKE_FAIL_REMOTE_CONTAINS:-}" ] &&
            printf '%s' "$remote" | grep -Fq "$FAKE_FAIL_REMOTE_CONTAINS"
    }; then
        printf 'adb: error: failed to copy %s\n' "$remote" >&2
        exit 1
    fi
    mkdir -p "$(dirname "$destination")"
    printf 'mock artifact: %s\n' "$remote" > "$destination"
    printf '%s: 1 file pulled\n' "$remote" >&2
    exit 0
fi

if [ "${1:-}" != "shell" ]; then exit 1; fi
shift
command="$*"

if [ -n "${FAKE_FAIL_INVENTORY_CONTAINS:-}" ] &&
    printf '%s' "$command" | grep -Fq "$FAKE_FAIL_INVENTORY_CONTAINS"; then
    printf 'Permission denied: %s\n' "$command" >&2
    exit 1
fi

case "$command" in
    "getprop ro.product.manufacturer") printf '%s\n' "${FAKE_MANUFACTURER:-BYD}" ;;
    "getprop ro.product.model") printf '%s\n' "${FAKE_MODEL:-DiLink5.0 For BYD AUTO}" ;;
    "getprop ro.product.name") printf '%s\n' "${FAKE_PRODUCT:-DiLink5.0}" ;;
    "getprop ro.build.fingerprint")
        printf '%s\n' "${FAKE_FINGERPRINT:-BYD-AUTO/DiLink5.0/test:12/mock/user/release-keys}"
        ;;
    "getprop ro.build.version.sdk") printf '32\n' ;;
    "getprop ro.kernel.qemu") printf '0\n' ;;
    getprop*) printf 'mock\n' ;;
    "pm list packages -s -f -U"|"pm list packages -f -U")
        for package in com.xdja.containerservice com.byd.launchermap com.byd.clusterdebug com.ts.car.someip.service com.byd.someipsystemservice; do
            printf 'package:/system/app/%s/base.apk=%s uid:1000\n' "$package" "$package"
        done
        ;;
    "pm list packages -s"|"pm list packages")
        for package in com.xdja.containerservice com.byd.launchermap com.byd.clusterdebug com.ts.car.someip.service com.byd.someipsystemservice; do
            printf 'package:%s\n' "$package"
        done
        ;;
    "pm path com.byd.launchermap")
        printf 'package:/data/app/~~mock/com.byd.launchermap-test/base.apk\n'
        printf 'package:/data/app/~~mock/com.byd.launchermap-test/split_config.arm64_v8a.apk\n'
        ;;
    "pm path "*)
        package="${command#pm path }"
        printf 'package:/system/app/%s/base.apk\n' "$package"
        ;;
    "service list")
        if [ "${FAKE_SUCCESS_STDERR:-0}" = "1" ]; then
            printf 'harmless service-list warning\n' >&2
        fi
        ;;
    "find '"*"' -maxdepth 4 -type f "*)
        remote_dir="$(printf '%s\n' "$command" | sed -n "s/^find '\([^']*\)'.*/\1/p")"
        printf '%s/oat/arm64/base.odex\n' "$remote_dir"
        ;;
    *) : ;;
esac
ADB
chmod +x "$FAKE_ADB"

run_collector() {
    local output="$1"
    shift
    ADB="$FAKE_ADB" "$ROOT_DIR/scripts/collect-byd-system-stack.sh" \
        --serial sea-lion-test \
        --output "$output" \
        --yes \
        "$@" >/dev/null
}

# COMPLETE: five packages survive stdin-draining adb, successful stderr is only a warning, split
# APK paths are safe, compiled artifacts stay out of the priority archive, and both manifests work.
(FAKE_SUCCESS_STDERR=1 run_collector "$OUTPUT_DIR")

grep -Fq 'Collection status: COMPLETE' "$OUTPUT_DIR/README.txt"
grep -Fq 'Collector version: 3' "$OUTPUT_DIR/README.txt"
grep -Fq 'Packages with APK pulled: 5' "$OUTPUT_DIR/README.txt"
grep -Fq 'Priority package APKs pulled: 5' "$OUTPUT_DIR/README.txt"
grep -Fq 'harmless service-list warning' "$OUTPUT_DIR/inventory-stderr.log"
test "$(awk 'NR > 1 && NF { count++ } END { print count + 0 }' "$OUTPUT_DIR/collection-errors.log")" = "0"

for package in \
    com.xdja.containerservice \
    com.byd.clusterdebug \
    com.ts.car.someip.service \
    com.byd.someipsystemservice; do
    test -f "$OUTPUT_DIR/packages/$package/apk/system/app/$package/base.apk"
    grep -Fq "$package PULLED" "$OUTPUT_DIR/inventory/priority-package-status.txt"
    tar -tzf "$OUTPUT_DIR-priority.tar.gz" | grep -Fq "packages/$package/apk/"
done

test -f "$OUTPUT_DIR/packages/com.byd.launchermap/apk/data/app/~~mock/com.byd.launchermap-test/base.apk"
test -f "$OUTPUT_DIR/packages/com.byd.launchermap/apk/data/app/~~mock/com.byd.launchermap-test/split_config.arm64_v8a.apk"
test -f "$OUTPUT_DIR/packages/com.byd.launchermap/compiled/data/app/~~mock/com.byd.launchermap-test/oat/arm64/base.odex"
grep -Fq 'com.byd.launchermap PULLED' "$OUTPUT_DIR/inventory/priority-package-status.txt"

grep -Fq '1 file pulled' "$OUTPUT_DIR/transfer.log"
test -s "$OUTPUT_DIR/SHA256SUMS.txt"
test -s "$OUTPUT_DIR/SHA256SUMS-priority.txt"
test -s "$OUTPUT_DIR.tar.gz"
tar -tzf "$OUTPUT_DIR-priority.tar.gz" | grep -Fq 'SHA256SUMS-priority.txt'
if tar -tzf "$OUTPUT_DIR-priority.tar.gz" | grep -Fq 'SHA256SUMS.txt'; then
    printf 'priority archive unexpectedly contains the full manifest\n' >&2
    exit 1
fi
if tar -tzf "$OUTPUT_DIR-priority.tar.gz" | grep -Fq '/compiled/'; then
    printf 'priority archive unexpectedly contains compiled package artifacts\n' >&2
    exit 1
fi

PRIORITY_EXTRACT="$TEST_ROOT/priority-extract"
mkdir -p "$PRIORITY_EXTRACT"
tar -xzf "$OUTPUT_DIR-priority.tar.gz" -C "$PRIORITY_EXTRACT"
(cd "$PRIORITY_EXTRACT" && shasum -a 256 -c SHA256SUMS-priority.txt >/dev/null)
(cd "$OUTPUT_DIR" && shasum -a 256 -c SHA256SUMS.txt >/dev/null)

# Guard: proprietary output must never be created anywhere inside the Git worktree.
REPO_OUTPUT="$ROOT_DIR/.collector-guard-test-$$"
if run_collector "$REPO_OUTPUT" --no-archive 2>/dev/null; then
    printf 'collector accepted an output path inside the repository\n' >&2
    exit 1
fi
test ! -e "$REPO_OUTPUT"

# The same guard must hold through a symlink whose visible path is outside the repository.
REPO_LINK="$TEST_ROOT/repository-link"
SYMLINK_OUTPUT_NAME=".collector-symlink-guard-test-$$"
ln -s "$ROOT_DIR" "$REPO_LINK"
if run_collector "$REPO_LINK/$SYMLINK_OUTPUT_NAME" --no-archive 2>/dev/null; then
    printf 'collector accepted a symlinked output path inside the repository\n' >&2
    exit 1
fi
test ! -e "$ROOT_DIR/$SYMLINK_OUTPUT_NAME"

# Guard: a non-BYD target is rejected before the output directory is created.
NON_BYD_OUTPUT="$TEST_ROOT/non-byd-output"
if (FAKE_MANUFACTURER=ACME \
    FAKE_MODEL=Generic \
    FAKE_PRODUCT=Generic \
    FAKE_FINGERPRINT='ACME/generic/test:12/mock/user/release-keys' \
    run_collector "$NON_BYD_OUTPUT" --no-archive 2>/dev/null); then
    printf 'collector accepted a non-BYD target\n' >&2
    exit 1
fi
test ! -e "$NON_BYD_OUTPUT"

# One failed priority APK pull leaves useful evidence and must report PARTIAL.
PARTIAL_OUTPUT="$TEST_ROOT/BYD-System-Dump-partial"
(FAKE_FAIL_REMOTE_CONTAINS='com.byd.clusterdebug/base.apk' \
    run_collector "$PARTIAL_OUTPUT" --no-archive)
grep -Fq 'Collection status: PARTIAL' "$PARTIAL_OUTPUT/README.txt"
grep -Fq 'FAILED pull: /system/app/com.byd.clusterdebug/base.apk' \
    "$PARTIAL_OUTPUT/collection-errors.log"

# A real failed inventory command records its stderr as an error and also reports PARTIAL.
PERMISSION_OUTPUT="$TEST_ROOT/BYD-System-Dump-permission-denied"
(FAKE_FAIL_INVENTORY_CONTAINS='service list' \
    run_collector "$PERMISSION_OUTPUT" --no-archive)
grep -Fq 'Collection status: PARTIAL' "$PERMISSION_OUTPUT/README.txt"
grep -Fq 'Permission denied: service list' "$PERMISSION_OUTPUT/collection-errors.log"
grep -Fq 'FAILED inventory: Binder services' "$PERMISSION_OUTPUT/collection-errors.log"

# If every artifact pull fails, there is no usable APK corpus and status must be INSUFFICIENT.
INSUFFICIENT_OUTPUT="$TEST_ROOT/BYD-System-Dump-insufficient"
(FAKE_FAIL_ALL_PULLS=1 run_collector "$INSUFFICIENT_OUTPUT" --no-archive)
grep -Fq 'Collection status: INSUFFICIENT' "$INSUFFICIENT_OUTPUT/README.txt"

printf 'collector regression suite: PASS\n'
