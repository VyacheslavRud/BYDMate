# Sea Lion 07 instrument cluster: confirmed architecture

Sanitized conclusions from the offline analysis of the read-only DiLink 5 system stack collected on
2026-07-22, cross-checked against the on-car Cluster Lab export of the same day. No APK, native
binary, VIN, account, route or raw dump content is reproduced here.

The subject of this document is the **physical instrument cluster behind the steering wheel**. The
windshield HUD is a different system, is already working, and is not affected by anything below.

## Short answer

The instrument cluster is **not an Android surface of this head unit**. It is a separate root-owned
Qt process running in the Fission *host* cell, while the whole Android system we run in — including
BYDMate, Waze and every Android display — is a guest cell. Rendering a Waze map there from an
Android application is therefore not blocked by a missing permission or an undiscovered display id.
There is no Android render target to reach.

## Evidence

### 1. The cluster UI is a native Qt process started by init as root

`/system/etc/init/fission_cluster.fission_host.rc` declares:

```text
service byd_demo_dual /system/bin/BydClusterManager
    class core
    user root
    seclabel u:r:fission_qtandroidnative:s0
    priority -20

service byd_demo_split /system/bin/qtandroidnative panel /system/lib64/libBydCluster.so
    class core
    disabled
    user root
    seclabel u:r:fission_qtandroidnative:s0
```

The cluster is `qtandroidnative` loading `libBydCluster.so` onto the `panel` output. It is not an
Activity, has no window, and is not visible to `ActivityTaskManager`, `WindowManager` or
`DisplayManager` inside our Android cell.

### 2. Android runs as a Fission guest cell, the cluster lives in the host

The same init directory contains:

```text
service fissiond /system/bin/fissiond -c /data/cells -s /data/cells_sd -F
service FissionHostSvc /system/bin/fission_service
service ivi_shutdown  /system/bin/fission stop  cell2
service ivi_poweron   /system/bin/fission start cell2 -Ds
```

The IVI Android is `cell2`. `FissionHostSvc` and `FissionGeneraySvc` are native Binder services
(`android::IFissionHostService`, `android::IFissionGenerayService` in `libfission_services.so`) that
belong to the host, not to the guest.

The XDJA reference plugins for putting IVI content on the panel exist —
`libDemoIviProjection_arm64-v8a.so`, `libFissionClusterDualDisp_arm64-v8a.so` under
`/vendor/FissionCluster_5_15_10/` — but they are loaded by `qtandroidnative` as root from a vendor
partition. An installed application cannot supply or load one.

### 3. The cluster draws navigation itself from a structured data model

`libBydCluster.so` is a Qt/QML application built around `DataSourceManager` with 739
`DATA_ITEM_ID_*` entries. The navigation subset includes:

```text
DATA_ITEM_ID_TURN_ICON_ID            DATA_ITEM_ID_NEXT_NEXT_TURN_ICON_ID
DATA_ITEM_ID_NEXT_ROAD_NAME          DATA_ITEM_ID_NEXT_ROAD_DIS_AUTO
DATA_ITEM_ID_NEXT_NEXT_ROAD_DIST     DATA_ITEM_ID_ROUTE_REMAIN_DIS_AUTO
DATA_ITEM_ID_ETAARRIVAL_TIME_DAY/HOUR/MINUTE/SECOND
DATA_ITEM_ID_REMAIN_TIME_DAY/HOUR/MINUTE/SECOND
DATA_ITEM_ID_NAVI_STATE  DATA_ITEM_ID_NAVI_TYPE  DATA_ITEM_ID_MAP_SEND_STATUS
```

QML geometry confirms two panel variants: `CLU_SIZE_8_8` → 1280×480, otherwise 1920×720, with
`NAVI_TYPE_SMALL_SCREEN` and `NAVI_TYPE_FULL_SCREEN` modes. This is the same right-hand map area the
reference photo shows.

So the factory cluster navigation is **structured data rendered by the cluster firmware**, not a
bitmap or video stream pushed from the IVI. The map raster is a separate concern gated by
`MAP_SEND_STATUS`.

### 4. The data enters inside the cluster process, from CAN and SOME/IP

`libBydDataSourceForDi5SF.so` exports the whole data-source API:

```text
BydDataSourceInit / BydDataSourceDeInit
BydDataSourceGetDataItem{Bool,Int,Real,String}
BydDataSourceSendPluginMsg{,Bool,Int,Real,String}
```

and feeds it from `BusinessSF::canDataUpdate(...)` plus
`PluginMsgManager::SendSomeIpMsg(unsigned, void const*)` /
`PluginMsgManager::RecvSomeIpMessage(unsigned, void*)`. A concrete navigation CAN signal name is
present: `NAVI_43F_SUB01_30_57_NEXT_ROAD_DIST`, alongside `NAVIGATION_REQ_FROM_CLUSTER` and
`NAVIGATION_REQ_FROM_PAD`.

Both transports terminate **inside the cluster process in the host cell**. Neither is an Android
API, and neither is exposed to an application in the guest cell.

### 5. The cluster and the windshield HUD are different systems

The windshield HUD is served by `someip.hud.navi.info.service` (`HudRoadInfoNotifyStruct`,
`HudMappathInfoNotifyStruct`), which BYDMate already publishes to through the
`com.ts.car.someip.service` gateway. None of `libBydCluster.so`,
`libBydDataSourceForDi5SF.so` or `libDi5LijieBydDataSource.so` references `hud.navi.info` or its
structs. The working windshield path therefore has no overlap with the cluster path, and cluster
research cannot regress it.

### 6. `autoservice` has no navigation channel

`libbydautoservice.so` exposes only the generic `BYDAutoService::getInt / setInt / getIntByArray`
device/event accessors that BYDMate already uses for vehicle parameters. There is no navigation
device type, turn icon, road name or ETA channel. The one vehicle write API available to this
project cannot address the cluster navigation model.

### 7. On-car state agrees

Cluster Lab C09 of 2026-07-22 reported:

```text
directAndroidTaskProjectionAvailable=false
physicalDisplays=1   displayNames=Built-in Screen|fission_bg_XDJAScreenProjection
AutoContainerNative  -> BINDER_EXCEPTION (DeadObjectException)
auto_container       -> TRANSACT_REJECTED for the projection getter
fission_projection_inventory status=EMPTY reportedCount=0
vendorServices=AutoContainerNative|FissionGeneraySvc|FissionHostSvc|auto_container|...
```

`AutoContainerNative` is registered but its process is dead, and `auto_container` rejects the
projection getter. `fission_bg_XDJAScreenProjection` remains the centre-screen floating container,
which is exactly what earlier attempts hit.

## Hypotheses resolved

| Hypothesis | Verdict |
| --- | --- |
| Cluster is a secondary Android `Display` | **Refuted.** One HWC display; the only virtual display is the centre floating container. |
| A `VirtualDisplay`/task move can reach it | **Refuted.** No render target in this cell. |
| Cluster mirrors or captures an IVI Activity | **Refuted.** Projection plugins are root-loaded vendor `.so` in the host cell. |
| Cluster consumes a video/bitmap stream from an app | **Refuted for applications.** Map transfer is gated inside the host by `MAP_SEND_STATUS`. |
| Cluster renders structured navigation data itself | **Confirmed.** 739-item `DataSourceManager`, full turn/road/ETA model. |
| A third-party app can publish that data | **Refuted with current evidence.** CAN and in-process SOME/IP plugin messages only; `autoservice` has no navigation channel. |
| `auto_container` is a usable projection API here | **Refuted.** Donor commands return `-1`; the projection getter is rejected; the native peer is dead. |

## Consequence for the project

Graphical Waze output on the Sea Lion 07 instrument cluster is **not achievable from an installed
Android application** on this firmware. This is a platform boundary, not a missing implementation,
so the project must not ship a partial or simulated cluster feature.

Nothing here changes the windshield HUD, which stays the supported navigation surface.

## What would change the answer

Only new *external* facts, none of which an app can obtain by itself:

1. A vendor-signed plugin under `/vendor/FissionCluster_*` (requires system/root and a vendor
   partition write — out of scope for this project).
2. A documented cross-cell SOME/IP service that accepts navigation items from the guest cell, with
   its service and method ids published by BYD. Guessing ids is explicitly out of scope.
3. A firmware release that exposes the cluster as a real Android display.

Until one of those exists, further on-car cluster tests would repeat a settled result and are not
planned.
