# T-C Laser Rangefinder Findings - 2026-07-07

## Scope

Project MSDK dependency: `com.dji:dji-sdk-v5-aircraft:5.18.0` / `com.dji:dji-sdk-v5-aircraft-provided:5.18.0`.

Verification used:

- Local SDK jar: `C:\Users\51799\.gradle\caches\modules-2\files-2.1\com.dji\dji-sdk-v5-aircraft-provided\5.18.0\...\dji-sdk-v5-aircraft-provided-5.18.0.jar`
- `javap` on `dji.sdk.keyvalue.key.DJICameraKey`
- DJI Mobile SDK V5 API reference: `Key_Camera_CameraKey.html`

## Keys Present in MSDK 5.18.0

The laser measurement keys are present on `dji.sdk.keyvalue.key.DJICameraKey`, not on the newer/smaller `CameraKey` subclass:

- `DJICameraKey.KeyLaserWorkMode`: `DJIKeyInfo<LaserWorkMode>`, get/set/listen.
- `DJICameraKey.KeyLaserMeasureEnabled`: `DJIKeyInfo<Boolean>`, get/set/listen, inner identifier `LaserMeasureEnable`.
- `DJICameraKey.KeyLaserMeasureInformation`: `DJIKeyInfo<LaserMeasureInformation>`, get/listen.
- Related capability keys: `KeyLaserMeasureExisted`, `KeyLaserMeasureSettable`.

The project already uses `KeyTools.createCameraKey(...)` for lens-scoped `DJICameraKey` access, so the implementation can create the laser keys with:

```kotlin
KeyTools.createCameraKey(
    DJICameraKey.KeyLaserMeasureInformation,
    ComponentIndexType.LEFT_OR_MAIN,
    CameraLensType.CAMERA_LENS_ZOOM,
)
```

## Enable Conditions

DJI's MSDK V5 API reference states:

- `KeyLaserWorkMode` controls laser working mode.
- When `KeyLaserWorkMode` is `OPEN_ON_DEMAND`, `KeyLaserMeasureEnabled` can open/close the laser module.
- `KeyLaserMeasureInformation` returns laser sensor information.
- The laser sensor must be at least 3 m from the target point.

For M4T triple-light payloads, the safest lens binding is `CAMERA_LENS_ZOOM` on `ComponentIndexType.LEFT_OR_MAIN`, because the laser rangefinder is associated with the zoom/rangefinding optical axis rather than the thermal measurement path. This should be validated on hardware; the code keeps this behind `LaserRangefinderClient`.

## Returned Structure

`LaserMeasureInformation` in 5.18.0 exposes:

- `getLocation3D(): LocationCoordinate3D` - target point location, including latitude/longitude/altitude.
- `getDistance(): Double` - distance from laser sensor to target point.
- `getTargetPoint(): DoublePoint2D` - target point on camera screen.
- `getLaserMeasureState(): LaserMeasureState` - `NORMAL`, `TOO_CLOSE`, `TOO_FAR`, `NO_SIGNAL`, `OUT_OF_RANGE`, `UNKNOWN`.

## Decision

Judgment: usable, with hardware validation still required.

Implementation path:

- Add injectable `LaserRangefinderClient`.
- Default production adapter sets `KeyLaserWorkMode = OPEN_ON_DEMAND`, enables `KeyLaserMeasureEnabled`, waits briefly, then reads `KeyLaserMeasureInformation`.
- Use returned `LocationCoordinate3D` only when state is `NORMAL`.
- If the adapter returns null, throws, or reports a non-normal state, fall back to the computed standoff hover point and mark the result `geoMethod = "standoff-hover-point-fallback"`.

No backend payload expansion is required for T-C; precise coordinates are returned in `FireConfirmationResult` and logged/available for follow-up integration.
