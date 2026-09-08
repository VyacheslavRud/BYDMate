# Sea Lion 07 instrument cluster architecture

Sanitized conclusions from the read-only DiLink 5 system stack collected on 2026-07-22, the on-car
Cluster Lab exports, and the original BYDMate projection implementation. No APK, native binary,
VIN, account, route or raw dump content is reproduced here.

This document is about the **instrument cluster behind the steering wheel**. The windshield HUD is
a separate SOME/IP system and is deliberately outside the projection code described below.

## Short answer

The physical instrument cluster is a native Qt renderer in the Fission host cell, not a normal
Android `Display`. That fact does **not** mean Android pixels can never be shown there. Compatible
DiLink firmware can expose a dedicated virtual display named `XDJAScreenProjection...`; the Fission
host compositor embeds that surface into the native cluster UI.

The original BYDMate implementation uses this optional bridge as follows:

```text
Waze task -> BYDMate VirtualDisplay -> SurfaceView on XDJAScreenProjection -> Fission host -> cluster
```

It does not write Waze data into the native 739-field navigation model and does not replace the Qt
cluster process. This distinction explains both the published Yandex screenshot and the Sea Lion
07 result: the mechanism works only when firmware exposes a **dedicated, app-visible** projection
display.

The live capture from 2026-07-22 refined that conclusion. The factory `com.byd.launchermap`
process creates a `PresentationView` on `fission_bg_XDJAScreenProjection`; display 2 changes
`mHasContent false -> true` and SurfaceFlinger receives a `bydAdd-com.byd.launchermap` layer.
Therefore this is a real factory pixel input, but it is **not a direct Activity-task target**.
Moving Waze's task there produced the small upper-right window on the centre screen because that
bypassed the factory Presentation path. Production task selection must still reject `fission_bg`.

## The two independent cluster paths

### 1. Native structured navigation

`/system/bin/BydClusterManager` (or `qtandroidnative panel /system/lib64/libBydCluster.so`) runs as
root in the Fission host cell. Its Qt/QML UI consumes a 739-item `DataSourceManager`. Navigation
items include:

```text
DATA_ITEM_ID_TURN_ICON_ID
DATA_ITEM_ID_NEXT_ROAD_NAME
DATA_ITEM_ID_NEXT_ROAD_DIS_AUTO
DATA_ITEM_ID_ROUTE_REMAIN_DIS_AUTO
DATA_ITEM_ID_ETAARRIVAL_TIME_*
DATA_ITEM_ID_NAVI_STATE
DATA_ITEM_ID_NAVI_TYPE
DATA_ITEM_ID_MAP_SEND_STATUS
```

CAN and in-process SOME/IP plugin messages feed this model. The collected stack does not expose a
safe guest-Android API for publishing these fields, and `libbydautoservice.so` has no navigation
channel. BYDMate therefore does not guess plugin message IDs or write to this path.

### 2. XDJA pixel projection

The vendor stack contains XDJA/Fission projection components which provide Android virtual
displays. There are two materially different access modes:

- a dedicated app-visible surface such as `XDJAScreenProjection_1`, on which BYDMate can use its
  existing overlay + `VirtualDisplay` pipeline;
- the protected `fission_bg_XDJAScreenProjection`, which the tested firmware exposes only to a
  hardcoded package whitelist and which factory code addresses through `Presentation`.

For the first mode BYDMate can:

1. create a display-scoped overlay `SurfaceView`;
2. create a helper-owned `VirtualDisplay` backed by that surface;
3. move the selected Waze task onto the new `VirtualDisplay`;
4. let the vendor compositor place those pixels into the cluster's navigation region;
5. move Waze back to display 0 and release both surfaces on exit.

This is the factory-compatible projection path restored for the Sea Lion dev build. Direct
`freeform` task placement is not used by default because it previously selected the centre-screen
`fission_bg` compositor. The `VirtualDisplay` must be PUBLIC so Waze remains visible to
Accessibility and the windshield HUD feed. If firmware rejects PUBLIC creation, BYDMate aborts
without moving Waze; it never falls back to a HUD-blind private display.

## Evidence from the tested Sea Lion 07

The 2026-07-22 runtime snapshot contained:

```text
physicalDisplays=1
displayNames=Built-in Screen|fission_bg_XDJAScreenProjection
fission_projection_inventory status=EMPTY reportedCount=0
```

The later live capture showed that display 2 remains present throughout factory Navi ON/OFF and is
owned by `com.xdja.containerservice`. Factory Navi adds and removes content on that existing display
with a `Presentation`; it does not create `XDJAScreenProjection_1` and it does not move an Activity
task there.

Decompiled `services.jar` also showed a hardcoded display-visibility whitelist containing
`com.byd.launchermap`, `com.xdja.containerservice`, `android`, `com.android.systemui`,
`com.android.shell`, `com.byd.mecanum`, `com.byd.mecanum.dashboard`, and `com.byd.naviauto`.
BYDMate and Waze are absent. `PROJECT_MEDIA` does not override this firmware check.

The safe runtime rule is therefore:

- keep rejecting `fission_bg` for direct Waze task placement;
- prefer the `_1` surface when more than one exists;
- use overlay + `VirtualDisplay` by default;
- require PUBLIC `VirtualDisplay` flags so windshield HUD guidance remains observable;
- optionally ask `auto_container` to enter factory projection mode, then wait for the dedicated
  display;
- if it never appears, abort and keep Waze on the centre screen;
- never treat a successful shell process or a non-zero Binder reply as proof of cluster state.

The helper-owned `Presentation` experiment for the protected display returned
`Rejected PRESENTATION_PROBE_FAILED` on this car. Its presence on the firmware whitelist did not
prove a usable rendering path. The experiment and the instrument-cluster laboratory have been
removed; their result remains historical compatibility evidence.

## Why the original Yandex implementation could show a map

Yandex did not need a special public "cluster HUD API" for the screenshot. BYDMate projected the
actual Android navigator pixels through the XDJA surface. Yandex's own route/HUD integration was
used for other features, including structured windshield HUD guidance and route reading, but the
instrument-cluster map itself was the projected Android app.

Waze can use the same pixel bridge when that bridge exists. The remaining compatibility question
is therefore firmware/display exposure, not whether Waze offers a cluster-map SDK.

## Windshield HUD isolation

The windshield HUD uses `someip.hud.navi.info.service` through
`com.ts.car.someip.service`. Cluster projection does not publish or clear those frames. Restoring
factory projection must not change Waze Accessibility parsing, maneuver mapping, distance updates,
or the proven SOME/IP HUD lifecycle.

## Current conclusion

The native Qt architecture is confirmed, and the original Android projection bridge is also real.
On the tested Sea Lion 07 firmware, direct Waze task placement on `fission_bg` is confirmed wrong,
but the display itself is confirmed as the factory Presentation target. The helper-owned
Presentation experiment failed and has been removed together with the other cluster scenarios.
Full Waze-map projection on this car remains unimplemented. The windshield HUD remains a separate,
working path. This document records architecture and historical findings; it is not an in-car
test procedure.
