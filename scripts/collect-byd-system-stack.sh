#!/usr/bin/env bash
#
# Read-only DiLink system-stack collector for BYDMate compatibility research.
#
# The collector intentionally does NOT install packages, change settings, remount
# partitions, request root, reboot the unit, or issue Binder service transactions.
# It only runs inventory commands and pulls world-readable system artifacts.

set -uo pipefail

umask 077

SCRIPT_VERSION="3"
ADB_BIN="${ADB:-adb}"
SERIAL=""
OUTPUT_DIR=""
ASSUME_YES=false
CREATE_ARCHIVE=true

ROOT_DIR="$(cd -P "$(dirname "$0")/.." && pwd)"
DISCOVERY_REGEX='byd|xdja|fission|someip|cluster|instrument|meter|navi|navigation|hud|container|autocontainer|autoservice|automap|launchermap'
NATIVE_REGEX='byd|xdja|fission|someip|cluster|instrument|meter|navi|hud|container|autocontainer|autoservice|automap|launchermap'

INVENTORY_SUCCEEDED=0
INVENTORY_FAILED=0
PULL_SUCCEEDED=0
PULL_FAILED=0
PACKAGES_WITH_APK=0
PRIORITY_APKS_PULLED=0

PRIORITY_PACKAGES="
com.xdja.containerservice
com.byd.launchermap
com.byd.clusterdebug
com.ts.car.someip.service
com.byd.someipsystemservice
"

usage() {
    cat <<'EOF'
Usage:
  ./scripts/collect-byd-system-stack.sh [options]

Options:
  --serial SERIAL       Use this exact ADB device serial/address.
  --output DIRECTORY    Save the dump to this new directory (parent must exist).
  --yes                 Skip the interactive COLLECT confirmation.
  --no-archive          Do not create the final .tar.gz archive.
  -h, --help            Show this help.

The target must identify itself as a BYD/DiLink device. The default output is:
  ~/Desktop/BYD-System-Dump-YYYYMMDD_HHMMSS

All vehicle operations are read-only.
EOF
}

log() {
    printf '[BYD collector] %s\n' "$*"
}

warn() {
    printf '[BYD collector] warning: %s\n' "$*" >&2
}

die() {
    printf '[BYD collector] error: %s\n' "$*" >&2
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

command -v "$ADB_BIN" >/dev/null 2>&1 || die "adb is not installed or not in PATH"
command -v shasum >/dev/null 2>&1 || die "shasum is not installed"
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
[ "$DEVICE_STATE" = "device" ] || die "ADB target '$SERIAL' is not online (state=${DEVICE_STATE:-unknown})"

adb_prop() {
    local property="$1"
    "$ADB_BIN" -s "$SERIAL" shell getprop "$property" </dev/null 2>/dev/null | tr -d '\r' | head -n 1
}

MANUFACTURER="$(adb_prop ro.product.manufacturer)"
MODEL="$(adb_prop ro.product.model)"
PRODUCT="$(adb_prop ro.product.name)"
FINGERPRINT="$(adb_prop ro.build.fingerprint)"
SDK="$(adb_prop ro.build.version.sdk)"
QEMU="$(adb_prop ro.kernel.qemu)"
IDENTITY_UPPER="$(printf '%s %s %s %s' "$MANUFACTURER" "$MODEL" "$PRODUCT" "$FINGERPRINT" | tr '[:lower:]' '[:upper:]')"

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
    printf 'The collector will only READ inventories and pull system files.\n'
    printf 'Type COLLECT to continue: '
    read -r CONFIRMATION
    [ "$CONFIRMATION" = "COLLECT" ] || die "cancelled"
fi

if [ -z "$OUTPUT_DIR" ]; then
    OUTPUT_DIR="$HOME/Desktop/BYD-System-Dump-$(date +%Y%m%d_%H%M%S)"
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
[ -d "$OUTPUT_PARENT_INPUT" ] || die "output parent directory does not exist: $OUTPUT_PARENT_INPUT"
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
if [ "$CREATE_ARCHIVE" = true ] && [ -e "$OUTPUT_DIR-priority.tar.gz" ]; then
    die "priority archive already exists: $OUTPUT_DIR-priority.tar.gz"
fi
mkdir -p "$OUTPUT_DIR"/{inventory,packages,framework,native,permissions,init,selinux,vintf} \
    || die "could not create output directory: $OUTPUT_DIR"

ERROR_LOG="$OUTPUT_DIR/collection-errors.log"
INVENTORY_STDERR_LOG="$OUTPUT_DIR/inventory-stderr.log"
TRANSFER_LOG="$OUTPUT_DIR/transfer.log"
printf 'BYD system-stack collector v%s\n' "$SCRIPT_VERSION" > "$ERROR_LOG"
printf 'ADB inventory stderr (warnings and failed-command details)\n' > "$INVENTORY_STDERR_LOG"
printf 'ADB transfer log\n' > "$TRANSFER_LOG"

record_error() {
    printf '%s\n' "$*" >> "$ERROR_LOG"
}

normalize_file() {
    local file="$1"
    if [ -f "$file" ]; then
        tr -d '\r' < "$file" > "$file.normalized"
        mv "$file.normalized" "$file"
    fi
}

capture_shell() {
    local label="$1"
    local destination="$2"
    local command="$3"
    local stderr_output
    stderr_output="$(mktemp "$OUTPUT_DIR/.adb-shell.XXXXXX")" \
        || die "could not create a temporary ADB inventory log"
    log "inventory: $label"
    if "$ADB_BIN" -s "$SERIAL" shell "$command" </dev/null \
        > "$destination" 2> "$stderr_output"; then
        if [ -s "$stderr_output" ]; then
            printf '[%s]\n' "$label" >> "$INVENTORY_STDERR_LOG"
            sed 's/\r/\n/g' "$stderr_output" >> "$INVENTORY_STDERR_LOG"
        fi
        rm -f "$stderr_output"
        normalize_file "$destination"
        INVENTORY_SUCCEEDED=$((INVENTORY_SUCCEEDED + 1))
        return 0
    fi
    if [ -s "$stderr_output" ]; then
        printf '[%s]\n' "$label" >> "$INVENTORY_STDERR_LOG"
        sed 's/\r/\n/g' "$stderr_output" >> "$INVENTORY_STDERR_LOG"
        sed 's/\r/\n/g' "$stderr_output" >> "$ERROR_LOG"
    fi
    rm -f "$stderr_output"
    INVENTORY_FAILED=$((INVENTORY_FAILED + 1))
    record_error "FAILED inventory: $label"
    normalize_file "$destination"
    return 1
}

safe_remote_path() {
    local remote="$1"
    printf '%s' "$remote" | grep -Eq '^/[A-Za-z0-9._/@+=,:~-]+$' || return 1
    case "$remote" in
        *//*|*/../*|*/..|*/./*|*/.)
            return 1
            ;;
    esac
    return 0
}

pull_remote() {
    local remote="$1"
    local local_root="$2"
    local relative
    local destination
    local transfer_output
    if ! safe_remote_path "$remote"; then
        PULL_FAILED=$((PULL_FAILED + 1))
        record_error "SKIPPED unsafe remote path: $remote"
        return 1
    fi
    relative="${remote#/}"
    destination="$local_root/$relative"
    mkdir -p "$(dirname "$destination")"
    transfer_output="$(mktemp "$OUTPUT_DIR/.adb-pull.XXXXXX")" \
        || die "could not create a temporary ADB transfer log"
    log "pull: $remote"
    if "$ADB_BIN" -s "$SERIAL" pull "$remote" "$destination" \
        </dev/null > "$transfer_output" 2>&1; then
        sed 's/\r/\n/g' "$transfer_output" >> "$TRANSFER_LOG"
        rm -f "$transfer_output"
        PULL_SUCCEEDED=$((PULL_SUCCEEDED + 1))
        return 0
    fi
    sed 's/\r/\n/g' "$transfer_output" >> "$TRANSFER_LOG"
    sed 's/\r/\n/g' "$transfer_output" >> "$ERROR_LOG"
    rm -f "$transfer_output"
    PULL_FAILED=$((PULL_FAILED + 1))
    record_error "FAILED pull: $remote"
    return 1
}

pull_list() {
    local list_file="$1"
    local local_root="$2"
    local remote=""
    [ -f "$list_file" ] || return 0
    while IFS= read -r remote || [ -n "$remote" ]; do
        remote="$(printf '%s' "$remote" | tr -d '\r' | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')"
        [ -n "$remote" ] || continue
        pull_remote "$remote" "$local_root" || true
    done < "$list_file"
}

is_priority_package() {
    local package_name="$1"
    printf '%s\n' "$PRIORITY_PACKAGES" | grep -Fxq "$package_name"
}

log "collecting bounded device properties"
{
    printf 'collector_version=%s\n' "$SCRIPT_VERSION"
    printf 'collected_at=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    printf 'adb_serial=%s\n' "$SERIAL"
    for PROP in \
        ro.product.manufacturer \
        ro.product.model \
        ro.product.name \
        ro.product.device \
        ro.build.fingerprint \
        ro.build.display.id \
        ro.build.version.release \
        ro.build.version.sdk \
        ro.product.cpu.abilist \
        ro.hardware \
        ro.boot.hardware \
        ro.system.build.fingerprint \
        ro.vendor.build.fingerprint \
        sys.boot_completed; do
        printf '%s=%s\n' "$PROP" "$(adb_prop "$PROP")"
    done
} > "$OUTPUT_DIR/inventory/device-properties.txt"

capture_shell \
    "system package paths" \
    "$OUTPUT_DIR/inventory/system-packages-with-paths.txt" \
    "pm list packages -s -f -U" || true
capture_shell \
    "system package names" \
    "$OUTPUT_DIR/inventory/system-package-names.txt" \
    "pm list packages -s" || true
capture_shell \
    "all package paths" \
    "$OUTPUT_DIR/inventory/all-packages-with-paths.txt" \
    "pm list packages -f -U" || true
capture_shell \
    "all package names" \
    "$OUTPUT_DIR/inventory/all-package-names.txt" \
    "pm list packages" || true
capture_shell \
    "global package manager state" \
    "$OUTPUT_DIR/inventory/dumpsys-package-all.txt" \
    "dumpsys package" || true
capture_shell \
    "Binder services" \
    "$OUTPUT_DIR/inventory/binder-services.txt" \
    "service list" || true
capture_shell \
    "relevant HAL services" \
    "$OUTPUT_DIR/inventory/lshal-relevant.txt" \
    "lshal 2>/dev/null | grep -Ei '$DISCOVERY_REGEX' || true" || true
capture_shell \
    "process inventory" \
    "$OUTPUT_DIR/inventory/processes.txt" \
    "ps -A" || true
capture_shell \
    "extended process inventory" \
    "$OUTPUT_DIR/inventory/processes-extended.txt" \
    "ps -Aw -o PID,USER,NAME,ARGS 2>/dev/null || ps -A -o PID,USER,NAME,ARGS 2>/dev/null || ps -A" || true
RELEVANT_PROCESS_EXES="$OUTPUT_DIR/inventory/relevant-process-executable-paths.txt"
capture_shell \
    "relevant process executables" \
    "$RELEVANT_PROCESS_EXES" \
    "for p in \$(ps -A 2>/dev/null | grep -Ei '$DISCOVERY_REGEX' | awk '{print \$2}'); do readlink /proc/\$p/exe 2>/dev/null; done | grep '^/' | sort -u || true" || true
capture_shell \
    "relevant process command lines" \
    "$OUTPUT_DIR/inventory/relevant-process-command-lines.txt" \
    "for p in \$(ps -A 2>/dev/null | grep -Ei '$DISCOVERY_REGEX' | awk '{print \$2}'); do printf 'pid=%s exe=%s cmdline=' \"\$p\" \"\$(readlink /proc/\$p/exe 2>/dev/null)\"; tr '\\000' ' ' < /proc/\$p/cmdline 2>/dev/null; echo; done || true" || true
capture_shell \
    "display manager" \
    "$OUTPUT_DIR/inventory/dumpsys-display.txt" \
    "dumpsys display" || true
capture_shell \
    "full SurfaceFlinger state" \
    "$OUTPUT_DIR/inventory/dumpsys-surfaceflinger.txt" \
    "dumpsys SurfaceFlinger" || true
capture_shell \
    "all SurfaceFlinger layers" \
    "$OUTPUT_DIR/inventory/surfaceflinger-all-layers.txt" \
    "dumpsys SurfaceFlinger --list" || true
capture_shell \
    "relevant SurfaceFlinger layers" \
    "$OUTPUT_DIR/inventory/surfaceflinger-relevant-layers.txt" \
    "dumpsys SurfaceFlinger --list 2>/dev/null | grep -Ei '$DISCOVERY_REGEX' | head -n 500 || true" || true
capture_shell \
    "relevant running services" \
    "$OUTPUT_DIR/inventory/activity-relevant-services.txt" \
    "dumpsys activity services 2>/dev/null | grep -Ei '$DISCOVERY_REGEX' | head -n 1000 || true" || true
capture_shell \
    "relevant activities" \
    "$OUTPUT_DIR/inventory/activity-relevant-activities.txt" \
    "dumpsys activity activities 2>/dev/null | grep -Ei '$DISCOVERY_REGEX' | head -n 1000 || true" || true
capture_shell \
    "activity displays and tasks" \
    "$OUTPUT_DIR/inventory/activity-displays-and-tasks.txt" \
    "dumpsys activity activities" || true
capture_shell \
    "window displays" \
    "$OUTPUT_DIR/inventory/window-displays.txt" \
    "dumpsys window displays" || true
capture_shell \
    "runtime classpaths" \
    "$OUTPUT_DIR/inventory/runtime-classpaths.txt" \
    "env | grep -E '^(BOOTCLASSPATH|DEX2OATBOOTCLASSPATH|SYSTEMSERVERCLASSPATH)=' || true" || true
capture_shell \
    "filtered BYD/vendor properties" \
    "$OUTPUT_DIR/inventory/vendor-properties-filtered.txt" \
    "getprop | grep -Ei '$DISCOVERY_REGEX|projection|display' | grep -Evi 'serial|vin|imei|meid|mac|iccid|imsi|android[_-]?id|wifi|bluetooth' || true" || true

PACKAGE_SOURCE="$OUTPUT_DIR/inventory/all-package-names.txt"
CANDIDATES="$OUTPUT_DIR/inventory/candidate-packages.txt"
if [ -s "$PACKAGE_SOURCE" ]; then
    sed -n 's/^package://p' "$PACKAGE_SOURCE" \
        | grep -Ei "$DISCOVERY_REGEX" \
        | grep -Ev '^com\.bydmate(\.|$)' \
        | LC_ALL=C sort -u > "$CANDIDATES" || true
else
    : > "$CANDIDATES"
    record_error "system package list is empty; APK discovery could not run"
fi

PACKAGE_COUNT="$(awk 'NF { count++ } END { print count + 0 }' "$CANDIDATES")"
log "candidate system packages: $PACKAGE_COUNT"
PULLED_PACKAGES="$OUTPUT_DIR/inventory/packages-with-apk-pulled.txt"
: > "$PULLED_PACKAGES"

while IFS= read -r PACKAGE_NAME || [ -n "$PACKAGE_NAME" ]; do
    [ -n "$PACKAGE_NAME" ] || continue
    case "$PACKAGE_NAME" in
        *[!A-Za-z0-9._-]*)
            record_error "SKIPPED unsafe package name: $PACKAGE_NAME"
            continue
            ;;
    esac

    PACKAGE_DIR="$OUTPUT_DIR/packages/$PACKAGE_NAME"
    mkdir -p "$PACKAGE_DIR"
    log "package: $PACKAGE_NAME"
    PACKAGE_APK_PULLED=false

    capture_shell \
        "package details: $PACKAGE_NAME" \
        "$PACKAGE_DIR/dumpsys-package.txt" \
        "dumpsys package $PACKAGE_NAME" || true

    capture_shell \
        "package paths: $PACKAGE_NAME" \
        "$PACKAGE_DIR/remote-apk-paths.txt" \
        "pm path $PACKAGE_NAME" || true

    : > "$PACKAGE_DIR/compiled-artifact-paths.txt"
    while IFS= read -r APK_LINE || [ -n "$APK_LINE" ]; do
        REMOTE_APK="${APK_LINE#package:}"
        [ "$REMOTE_APK" != "$APK_LINE" ] || continue
        if ! safe_remote_path "$REMOTE_APK"; then
            record_error "SKIPPED unsafe APK path for $PACKAGE_NAME: $REMOTE_APK"
            continue
        fi
        if pull_remote "$REMOTE_APK" "$PACKAGE_DIR/apk"; then
            PACKAGE_APK_PULLED=true
        fi
        REMOTE_APK_DIR="${REMOTE_APK%/*}"
        "$ADB_BIN" -s "$SERIAL" shell \
            "find '$REMOTE_APK_DIR' -maxdepth 4 -type f 2>/dev/null | grep -E '\.(odex|vdex|art|so)$' || true" \
            </dev/null >> "$PACKAGE_DIR/compiled-artifact-paths.txt" \
            2>> "$INVENTORY_STDERR_LOG" || true
    done < "$PACKAGE_DIR/remote-apk-paths.txt"
    normalize_file "$PACKAGE_DIR/compiled-artifact-paths.txt"
    LC_ALL=C sort -u "$PACKAGE_DIR/compiled-artifact-paths.txt" \
        > "$PACKAGE_DIR/compiled-artifact-paths.sorted"
    mv "$PACKAGE_DIR/compiled-artifact-paths.sorted" "$PACKAGE_DIR/compiled-artifact-paths.txt"
    pull_list "$PACKAGE_DIR/compiled-artifact-paths.txt" "$PACKAGE_DIR/compiled"
    if [ "$PACKAGE_APK_PULLED" = true ]; then
        PACKAGES_WITH_APK=$((PACKAGES_WITH_APK + 1))
        printf '%s\n' "$PACKAGE_NAME" >> "$PULLED_PACKAGES"
        if is_priority_package "$PACKAGE_NAME"; then
            PRIORITY_APKS_PULLED=$((PRIORITY_APKS_PULLED + 1))
        fi
    fi
done < "$CANDIDATES"
LC_ALL=C sort -u "$PULLED_PACKAGES" > "$PULLED_PACKAGES.sorted"
mv "$PULLED_PACKAGES.sorted" "$PULLED_PACKAGES"

FRAMEWORK_PATHS="$OUTPUT_DIR/inventory/framework-paths.txt"
capture_shell \
    "framework files" \
    "$FRAMEWORK_PATHS" \
    "for d in /system/framework /system_ext/framework /product/framework /vendor/framework; do if [ -d \"\$d\" ]; then find \"\$d\" -maxdepth 1 -type f 2>/dev/null || true; fi; done" || true
grep -E '\.(jar|apk)$' "$FRAMEWORK_PATHS" | LC_ALL=C sort -u > "$FRAMEWORK_PATHS.filtered" || true
mv "$FRAMEWORK_PATHS.filtered" "$FRAMEWORK_PATHS"

APEX_JAR_PATHS="$OUTPUT_DIR/inventory/apex-java-library-paths.txt"
capture_shell \
    "APEX Java libraries" \
    "$APEX_JAR_PATHS" \
    "for d in /apex/*/javalib; do [ -d \"\$d\" ] && find \"\$d\" -maxdepth 1 -type f 2>/dev/null; done | grep -E '\.(jar|apk)$' || true" || true

RUNTIME_CLASSPATH_PATHS="$OUTPUT_DIR/inventory/runtime-classpath-artifact-paths.txt"
: > "$RUNTIME_CLASSPATH_PATHS"
while IFS= read -r CLASSPATH_LINE || [ -n "$CLASSPATH_LINE" ]; do
    case "$CLASSPATH_LINE" in
        *=*)
            CLASSPATH_VALUE="${CLASSPATH_LINE#*=}"
            printf '%s\n' "$CLASSPATH_VALUE" | tr ':' '\n' >> "$RUNTIME_CLASSPATH_PATHS"
            ;;
    esac
done < "$OUTPUT_DIR/inventory/runtime-classpaths.txt"
grep -E '^/.*\.(jar|apk)$' "$RUNTIME_CLASSPATH_PATHS" \
    | LC_ALL=C sort -u > "$RUNTIME_CLASSPATH_PATHS.filtered" || true
mv "$RUNTIME_CLASSPATH_PATHS.filtered" "$RUNTIME_CLASSPATH_PATHS"

FRAMEWORK_PULL_PATHS="$OUTPUT_DIR/inventory/framework-pull-paths.txt"
cat "$FRAMEWORK_PATHS" "$APEX_JAR_PATHS" "$RUNTIME_CLASSPATH_PATHS" \
    | grep -E '^/.*\.(jar|apk)$' \
    | LC_ALL=C sort -u > "$FRAMEWORK_PULL_PATHS" || true
pull_list "$FRAMEWORK_PULL_PATHS" "$OUTPUT_DIR/framework"

FRAMEWORK_COMPILED_PATHS="$OUTPUT_DIR/inventory/framework-compiled-paths.txt"
capture_shell \
    "compiled framework files" \
    "$FRAMEWORK_COMPILED_PATHS" \
    "for d in /system/framework/arm /system/framework/arm64 /system/framework/oat /system_ext/framework/arm /system_ext/framework/arm64 /system_ext/framework/oat /product/framework/arm /product/framework/arm64 /product/framework/oat /vendor/framework/arm /vendor/framework/arm64 /vendor/framework/oat /apex/*/javalib; do [ -d \"\$d\" ] && find \"\$d\" -maxdepth 3 -type f 2>/dev/null; done | grep -E '\.(odex|vdex|art|oat)$' || true" || true
LC_ALL=C sort -u "$FRAMEWORK_COMPILED_PATHS" > "$FRAMEWORK_COMPILED_PATHS.sorted"
mv "$FRAMEWORK_COMPILED_PATHS.sorted" "$FRAMEWORK_COMPILED_PATHS"
pull_list "$FRAMEWORK_COMPILED_PATHS" "$OUTPUT_DIR/framework"

PERMISSION_PATHS="$OUTPUT_DIR/inventory/permission-xml-paths.txt"
capture_shell \
    "relevant permission XML" \
    "$PERMISSION_PATHS" \
    "for d in /system/etc/permissions /system_ext/etc/permissions /product/etc/permissions /vendor/etc/permissions; do [ -d \"\$d\" ] && find \"\$d\" -maxdepth 1 -type f 2>/dev/null; done | grep -Ei '$DISCOVERY_REGEX|privapp-permissions' | grep -E '\.xml$' || true" || true
LC_ALL=C sort -u "$PERMISSION_PATHS" > "$PERMISSION_PATHS.sorted"
mv "$PERMISSION_PATHS.sorted" "$PERMISSION_PATHS"
pull_list "$PERMISSION_PATHS" "$OUTPUT_DIR/permissions"

INIT_PATHS="$OUTPUT_DIR/inventory/init-script-paths.txt"
capture_shell \
    "init scripts" \
    "$INIT_PATHS" \
    "for d in /system/etc/init /system_ext/etc/init /product/etc/init /vendor/etc/init /odm/etc/init; do [ -d \"\$d\" ] && find \"\$d\" -maxdepth 4 -type f 2>/dev/null; done | grep -E '\.rc$' || true" || true
LC_ALL=C sort -u "$INIT_PATHS" > "$INIT_PATHS.sorted"
mv "$INIT_PATHS.sorted" "$INIT_PATHS"
pull_list "$INIT_PATHS" "$OUTPUT_DIR/init"

capture_shell \
    "SELinux enforcement" \
    "$OUTPUT_DIR/inventory/selinux-enforcement.txt" \
    "getenforce 2>/dev/null || true" || true
SELINUX_PATHS="$OUTPUT_DIR/inventory/selinux-policy-paths.txt"
capture_shell \
    "SELinux policy files" \
    "$SELINUX_PATHS" \
    "for d in /system/etc/selinux /system_ext/etc/selinux /product/etc/selinux /vendor/etc/selinux /odm/etc/selinux; do [ -d \"\$d\" ] && find \"\$d\" -maxdepth 4 -type f 2>/dev/null; done || true" || true
LC_ALL=C sort -u "$SELINUX_PATHS" > "$SELINUX_PATHS.sorted"
mv "$SELINUX_PATHS.sorted" "$SELINUX_PATHS"
pull_list "$SELINUX_PATHS" "$OUTPUT_DIR/selinux"

VINTF_PATHS="$OUTPUT_DIR/inventory/vintf-paths.txt"
capture_shell \
    "VINTF manifests" \
    "$VINTF_PATHS" \
    "for d in /system/etc/vintf /system_ext/etc/vintf /product/etc/vintf /vendor/etc/vintf /odm/etc/vintf; do [ -d \"\$d\" ] && find \"\$d\" -maxdepth 3 -type f 2>/dev/null; done || true" || true
LC_ALL=C sort -u "$VINTF_PATHS" > "$VINTF_PATHS.sorted"
mv "$VINTF_PATHS.sorted" "$VINTF_PATHS"
pull_list "$VINTF_PATHS" "$OUTPUT_DIR/vintf"

NATIVE_PATHS="$OUTPUT_DIR/inventory/native-paths.txt"
capture_shell \
    "relevant native files" \
    "$NATIVE_PATHS" \
    "for d in /system/bin /system_ext/bin /product/bin /vendor/bin /vendor/bin/hw /system/lib /system/lib64 /system_ext/lib /system_ext/lib64 /product/lib /product/lib64 /vendor/lib /vendor/lib64; do [ -d \"\$d\" ] && find \"\$d\" -maxdepth 2 -type f 2>/dev/null; done | grep -Ei '$NATIVE_REGEX' || true" || true
cat "$NATIVE_PATHS" "$RELEVANT_PROCESS_EXES" | LC_ALL=C sort -u > "$NATIVE_PATHS.sorted"
mv "$NATIVE_PATHS.sorted" "$NATIVE_PATHS"
pull_list "$NATIVE_PATHS" "$OUTPUT_DIR/native"

PRIORITY_PACKAGE_LIST="$OUTPUT_DIR/inventory/priority-packages.txt"
PRIORITY_PACKAGE_STATUS="$OUTPUT_DIR/inventory/priority-package-status.txt"
printf '%s\n' "$PRIORITY_PACKAGES" | awk 'NF' > "$PRIORITY_PACKAGE_LIST"
: > "$PRIORITY_PACKAGE_STATUS"
PRIORITY_PACKAGES_INSTALLED=0
while IFS= read -r PACKAGE_NAME || [ -n "$PACKAGE_NAME" ]; do
    [ -n "$PACKAGE_NAME" ] || continue
    if grep -Fxq "$PACKAGE_NAME" "$CANDIDATES"; then
        PRIORITY_PACKAGES_INSTALLED=$((PRIORITY_PACKAGES_INSTALLED + 1))
        if grep -Fxq "$PACKAGE_NAME" "$PULLED_PACKAGES"; then
            printf '%s PULLED\n' "$PACKAGE_NAME" >> "$PRIORITY_PACKAGE_STATUS"
        else
            printf '%s APK_NOT_PULLED\n' "$PACKAGE_NAME" >> "$PRIORITY_PACKAGE_STATUS"
        fi
    else
        printf '%s NOT_INSTALLED_OR_NOT_DISCOVERED\n' "$PACKAGE_NAME" >> "$PRIORITY_PACKAGE_STATUS"
    fi
done < "$PRIORITY_PACKAGE_LIST"

ERROR_COUNT="$(grep -cE '^(FAILED|SKIPPED)' "$ERROR_LOG" 2>/dev/null || true)"
ERROR_DETAIL_COUNT="$(awk 'NR > 1 && NF { count++ } END { print count + 0 }' "$ERROR_LOG")"
COLLECTION_STATUS="COMPLETE"
if [ "$PACKAGE_COUNT" -eq 0 ] || [ "$PACKAGES_WITH_APK" -eq 0 ] || [ "$PULL_SUCCEEDED" -eq 0 ]; then
    COLLECTION_STATUS="INSUFFICIENT"
elif [ "$INVENTORY_FAILED" -gt 0 ] \
    || [ "$PULL_FAILED" -gt 0 ] \
    || [ "$ERROR_COUNT" -gt 0 ] \
    || [ "$PRIORITY_PACKAGES_INSTALLED" -gt "$PRIORITY_APKS_PULLED" ]; then
    COLLECTION_STATUS="PARTIAL"
fi

cat > "$OUTPUT_DIR/README.txt" <<EOF
BYD System Stack Dump
=====================

Collection status: $COLLECTION_STATUS
Collector version: $SCRIPT_VERSION
Collected at: $(date -u +%Y-%m-%dT%H:%M:%SZ)
ADB serial: $SERIAL
Manufacturer: $MANUFACTURER
Model: $MODEL
Product: $PRODUCT
Android SDK: $SDK
Fingerprint: $FINGERPRINT
Candidate system packages: $PACKAGE_COUNT
Packages with APK pulled: $PACKAGES_WITH_APK
Priority packages installed/discovered: $PRIORITY_PACKAGES_INSTALLED
Priority package APKs pulled: $PRIORITY_APKS_PULLED
Inventory commands succeeded: $INVENTORY_SUCCEEDED
Inventory commands failed: $INVENTORY_FAILED
Files pulled successfully: $PULL_SUCCEEDED
File pulls failed: $PULL_FAILED
Recorded failures/skips: $ERROR_COUNT
Diagnostic/error log lines: $ERROR_DETAIL_COUNT

Scope
-----
This dump was produced using read-only ADB inventory and pull operations.
No package was installed, no setting was changed, no partition was remounted,
no Binder transaction was issued, and the vehicle was not rebooted.

Start analysis with:
  inventory/candidate-packages.txt
  inventory/binder-services.txt
  packages/
  framework/
  native/
  permissions/
  init/
  selinux/
  vintf/

Warnings from otherwise successful inventory commands are recorded in
inventory-stderr.log and do not lower the collection status. Failed commands,
access denials that return a failure, unsafe paths, and failed pulls are recorded
in collection-errors.log.
COMPLETE means the collector detected no execution or read failures. PARTIAL means
the dump is still useful but at least one requested inventory or pull failed.
INSUFFICIENT means the core package/APK evidence is missing and the collection
should be repeated.

Keep this dump private: it contains proprietary vehicle software and may contain
current system component, process, display, and package metadata.
EOF

PRIORITY_ARCHIVE_FILE_LIST="$OUTPUT_DIR/inventory/priority-archive-files.txt"
if ! (
    cd "$OUTPUT_DIR" || exit 1
    {
        printf '%s\n' \
            README.txt \
            SHA256SUMS-priority.txt \
            collection-errors.log \
            inventory-stderr.log \
            transfer.log
        find inventory permissions init vintf -type f 2>/dev/null
        while IFS= read -r PACKAGE_NAME || [ -n "$PACKAGE_NAME" ]; do
            if [ -d "packages/$PACKAGE_NAME" ]; then
                find "packages/$PACKAGE_NAME" -type f \
                    ! -path "packages/$PACKAGE_NAME/compiled/*"
            fi
        done < inventory/priority-packages.txt
    } | grep -v '^inventory/priority-archive-files\.txt$' | LC_ALL=C sort -u
) > "$PRIORITY_ARCHIVE_FILE_LIST"; then
    die "could not create the priority archive file list"
fi

log "calculating priority SHA-256 inventory"
if ! (
    cd "$OUTPUT_DIR" || exit 1
    while IFS= read -r FILE || [ -n "$FILE" ]; do
        [ "$FILE" = "SHA256SUMS-priority.txt" ] && continue
        shasum -a 256 "$FILE" || exit 1
    done < inventory/priority-archive-files.txt
) > "$OUTPUT_DIR/SHA256SUMS-priority.txt"; then
    die "could not calculate the priority SHA-256 inventory"
fi

log "calculating SHA-256 inventory"
if ! (
    cd "$OUTPUT_DIR" || exit 1
    find . -type f ! -name SHA256SUMS.txt | LC_ALL=C sort | while IFS= read -r FILE; do
        shasum -a 256 "$FILE" || exit 1
    done
) > "$OUTPUT_DIR/SHA256SUMS.txt"; then
    die "could not calculate the SHA-256 inventory"
fi

ARCHIVE_PATH=""
PRIORITY_ARCHIVE_PATH=""
if [ "$CREATE_ARCHIVE" = true ]; then
    ARCHIVE_PATH="$OUTPUT_DIR.tar.gz"
    PRIORITY_ARCHIVE_PATH="$OUTPUT_DIR-priority.tar.gz"
    log "creating full private archive: $ARCHIVE_PATH"
    tar -czf "$ARCHIVE_PATH" -C "$(dirname "$OUTPUT_DIR")" "$(basename "$OUTPUT_DIR")" \
        || die "could not create full archive: $ARCHIVE_PATH"

    log "creating smaller priority archive: $PRIORITY_ARCHIVE_PATH"
    tar -czf "$PRIORITY_ARCHIVE_PATH" -C "$OUTPUT_DIR" -T "$PRIORITY_ARCHIVE_FILE_LIST" \
        || die "could not create priority archive: $PRIORITY_ARCHIVE_PATH"
fi

printf '\nCollection finished with status: %s\n' "$COLLECTION_STATUS"
printf '  Folder:       %s\n' "$OUTPUT_DIR"
printf '  Folder size:  %s\n' "$(du -sh "$OUTPUT_DIR" | awk '{ print $1 }')"
if [ -n "$ARCHIVE_PATH" ]; then
    printf '  Priority archive: %s\n' "$PRIORITY_ARCHIVE_PATH"
    printf '  Priority size:    %s\n' "$(du -sh "$PRIORITY_ARCHIVE_PATH" | awk '{ print $1 }')"
    printf '  Priority SHA-256: %s\n' "$(shasum -a 256 "$PRIORITY_ARCHIVE_PATH" | awk '{ print $1 }')"
    printf '  Full archive:     %s\n' "$ARCHIVE_PATH"
    printf '  Full size:        %s\n' "$(du -sh "$ARCHIVE_PATH" | awk '{ print $1 }')"
    printf '  Full SHA-256:     %s\n' "$(shasum -a 256 "$ARCHIVE_PATH" | awk '{ print $1 }')"
fi
case "$COLLECTION_STATUS" in
    COMPLETE)
        printf '\nThe collector detected no failed read-only collection operations.\n'
        ;;
    PARTIAL)
        printf '\nSome operations failed. The dump may still be useful; review collection-errors.log.\n'
        ;;
    INSUFFICIENT)
        printf '\nCore APK evidence is missing. Review collection-errors.log and repeat the collection before analysis.\n'
        ;;
esac
if [ -n "$PRIORITY_ARCHIVE_PATH" ]; then
    printf 'Send the priority archive first. Keep the full archive private unless its extra evidence is requested.\n'
fi
