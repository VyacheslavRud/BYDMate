# BYD Sea Lion 07 DiLink 5 compatibility passport

This document preserves sanitized, code-relevant findings from the read-only system-stack
collection. It contains no APK, native binary, VIN, account data, route history, or raw dump.

## Target vehicle

- Vehicle: BYD Sea Lion 07, model year 2025.
- Market: China.
- Drivetrain target: maximum trim, rear-wheel drive.
- Head unit: `BYD AUTO DiLink5.0 For BYD AUTO`.
- Android: 12, SDK 32.
- Compatibility rule: Leopard 3 FIDs, Binder commands, display assumptions and battery defaults are
  not valid for this target until separately confirmed on this car.

## Evidence status

Static evidence was collected read-only on 2026-07-22. Collector v1 produced useful framework,
native, VINTF, init and display inventories, but an stdin bug stopped package processing after the
first candidate and the final archive list failed under `pipefail`. Collector v3 fixes both issues,
separates successful-command warnings from real errors, excludes compiled package artifacts from
the priority archive, and gives that archive its own checksum manifest. The five priority APKs
still require one repeated v3 collection before their app implementations can be treated as
inspected.

Priority packages for the repeated collection:

- `com.xdja.containerservice`
- `com.byd.launchermap`
- `com.byd.clusterdebug`
- `com.ts.car.someip.service`
- `com.byd.someipsystemservice`

## Confirmed Android Binder contract

The runtime registers:

- `AutoContainerNative` with no Java descriptor;
- `auto_container` as `android.os.IAutoContainer`;
- `autoservice` as `android.gui.BYDAutoServer`.

`framework.jar` contains the exact `android.os.IAutoContainer` AIDL transaction map:

| Transaction | Method |
| --- | --- |
| 1 | `int sendJson(int type, String json)` |
| 2 | `int sendInfo(int type, int infoInt, String infoStr)` |
| 3 | `int sendInfo2(int type, byte[] data)` |
| 4 | `int registerCallback(IContainerCallback cb)` |
| 5 | `int getProjectionDisplayInfo(out ProjectionDisplayInfoParcel info)` |

`ProjectionDisplayInfoParcel` contains `name`, `width`, `height`, and an `IBinder surface`.
`IContainerCallback` exposes `onServiceDied`, `onReceivedJson`, `onReceivedInfo`, and
`onReceivedInfo2`.

The existing donor command:

```text
service call auto_container 2 i32 1000 i32 16 s16 ""
```

is therefore exactly `sendInfo(type=1000, infoInt=16, infoStr="")`. Sea Lion returns service value
`-1` for command 16 even though the `service call` process exits with code 0. This is a service-level
error, not proof that no transient native visual effect occurred. Commands 16/18/0 remain donor
calibration commands, not a confirmed Sea Lion API.

The decompiled `Stub.onTransact` constructs a new `ProjectionDisplayInfoParcel`, calls transaction
5 with that object, and writes it to the reply. This proves the AIDL direction is `out`: the request
contains only the interface token and no caller-provided parcel. Transaction 5 is a read-only getter,
is executed exactly once only by Cluster Lab `C09`, and is not part of the shared `C07/C08` probe.

## Display topology

The only additional Android display found in the collected runtime is:

```text
displayId=2
name=fission_bg_XDJAScreenProjection
size=1920x720
type=VIRTUAL
owner=com.xdja.containerservice (uid 1000)
```

This display is the center-screen floating projection container. It is not the physical instrument
cluster. Sending Waze there reproduces the previously observed small Waze window on the upper-right
of the center display, so production cluster selection must continue to exclude this name.

The physical instrument cluster is a native Fission/Qt path, not an app-visible Android display.
Collected components include:

- `/system/bin/BydClusterManager`
- `BydClusterKanzi`
- `BydClusterLijie`
- `ClusterRecorder`
- `libBydCluster.so`
- `libBydClusterForDi51Qt.so`
- `cluster_newui_ocean*.rcc`

Therefore an Android `VirtualDisplay` or task move alone cannot render Waze into the physical
cluster map area. The next implementation step requires the priority APKs or a confirmed vendor
surface/data contract; it must not guess new `sendInfo` values.

## Native cluster navigation model

`libBydCluster.so` is ARM64 and retains symbols/debug information. `DataSourceManager` exposes a
rich navigation model including:

- `turnIconId`, `nextNextTurnIconId`
- `nextRoadName`, `nextRoadDisAuto`, `nextNextRoadDist`
- `routeRemainDisAuto`
- `etaarrivalTimeDay/Hour/Minute/Second`
- `remainTime*`
- `mapSendStatus`, `naviState`, `naviType`
- road width, traffic, camera and lane-related state

This proves the native cluster can display structured route guidance. It does not yet prove which
public Binder/SOME-IP method a third-party app may use to populate those fields.

## SOME/IP and windshield HUD

The runtime exposes `vendor.ts.someip@1.0` interfaces `ISomeIp`, `ISomeIpClient`, and
`ISomeIpServer`, including the `SomeIpDaemon` instance. Native protobuf symbols confirm additional
navigation structures:

- `someip.hud.navi.info.service.HudRoadInfoNotifyStruct`
- `someip.hud.navi.info.service.HudMappathInfoNotifyStruct`
- `someip.navigation.status_.link.info.service.NavigationStatus_LinkInfoNotifyStruct`
- `someip.navigation.path.match.status.service.NavigationPathMatchStatusStruct`

The production Waze windshield-HUD contract remains deliberately minimal and confirmed:

- `f9`: real maneuver distance;
- `f10`: road text;
- `f28=2`: right;
- `f28=3`: left;
- `f28=7`: left U-turn/native circular-left indicator;
- `f28=10`: right U-turn/native circular-right indicator;
- `f28=11`: straight;
- the firmware applies its own distance threshold: 20/50 m shows the maneuver, while 100 m may
  remain straight.

Production omits PNG fields `f7/f8`, speed `f11`, ETA `f26`, and non-zero progress `f33` until the
separate Dev tests confirm them. The `HUD extensions` catalog tests these scalar fields without
changing production Waze output.

## Dev test matrix

All visual tests require a Dev build, gear P, 0 km/h, no active route, explicit driver confirmation,
durable logging and automatic cleanup.

### Windshield HUD

- `SL01-SL05`: confirmed production smoke tests.
- `HX01`: `f11=50` with render class `f6=1`.
- `HX02`: ETA `f26=12:34`.
- `HX03`: progress `f33=0.5`.
- `HX04`: combined road, speed, ETA and progress scalar frame.
- `HX05`: comparison frame with `f6=6` and `f11=50`, still without PNG.

### Instrument cluster

- `C07`: bounded donor container sequence with guaranteed 18 -> 0 cleanup and native/display
  snapshots. It may show the factory native map shell; it does not project Waze by itself.
- `C08`: read-only before/after watch while the driver manually selects the factory Navi panel.
- `C09`: read-only `IAutoContainer` descriptor plus transaction-5 projection parcel snapshot.

Never run HUD Lab and Cluster Lab concurrently. Export the journal after tests; a visual observation
is required before any field or path is promoted to production.

## Private-artifact policy

Raw system dumps, archives, APKs, framework files, native libraries, resource bundles and JADX or
apktool output are proprietary and must remain outside Git. The repository `.gitignore` reserves
private workspace names for this purpose. Only sanitized findings such as this file belong in Git.
