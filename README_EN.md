> English | [繁體中文](README.md)

# DJI Mini 4 Pro Drone Control App

An Android drone control application built on DJI MSDK v5. It receives remote commands via MQTT to execute automated flight missions.

## Quick Start

### 1. Get a DJI App Key (required)
DJI MSDK requires a developer App Key to connect to the drone:
1. Change the `applicationId` in `app/build.gradle` (currently `com.example.n8ntest`) to **your own package name** — the DJI App Key is bound to the package name, and each package can only bind one DJI App.
2. Register at the [DJI Developer Portal](https://developer.dji.com) and create an App, **binding it to your applicationId**.
3. Get the App Key and fill it into `gradle.properties`:
   ```
   DJI_API_KEY = YOUR_API_KEY
   ```
4. (Optional) Map features also require `GMAP_API_KEY` (Google Maps) and `MAPLIBRE_TOKEN`.

### 2. Build & Run
- Open this project in Android Studio → run `assembleDebug` (now uses the default debug signing, no personal keystore needed).
- The MQTT broker (default public `broker.emqx.io`) and topic prefix can be changed in the app's Settings page.

## Target Hardware

| Item | Model |
|------|-------|
| Drone | DJI Mini 4 Pro |
| Remote Controller | DJI RC-N2 |
| SDK | DJI MSDK v5 (5.18.0) |

## Feature Overview

### Mission Modes

- **Mission A (Fixed-point Recording + Photos)**: Fly to the target coordinate → 360° in-place rotation recording → 7 multi-angle photos → return home
- **Mission B (Orbit Recording)**: Fly to the target coordinate → orbit the target 360° at a specified radius while recording → return home
- **Mission C (Spiral Ascent Recording)**: Fly to the target coordinate → spiral up N turns at a fixed radius (Z_min → Z_max) while recording, gimbal dynamically locks on the center → return home
- **Mission D (Expanding Spiral Recording)**: Fly to the target coordinate → spiral up N turns while the radius grows linearly from rStart to rEnd, gimbal dynamically locks on the center → return home
- **Multi-Stop Mission**: Executes A/B/C/D missions at multiple stops in sequence, no landing between stops, returns home at the end

### Core Capabilities

- MQTT remote command control (takeoff, return-to-home, cancel, reset, mission start, multi-stop mission start)
- Real-time status reporting (coordinate, altitude, battery, GPS, mission state — every 10 s while flying)
- Gimbal angle control (independent recording/photo settings)
- Safety protection (GPS signal check, battery monitoring, flight boundary limits)
- Automatic photo download after mission completion

## Architecture

```
app/
├── config/          Global configuration
│   └── AppConfig.kt             Compile-time constants + SharedPreferences
├── dji/             DJI SDK wrapper layer
│   ├── FlightManager.kt         Flight control + state listening + safety parameters
│   ├── CameraManager.kt         Camera operations (photo/recording/focus)
│   ├── GimbalManager.kt         Gimbal angle control
│   └── MediaDownloadManager.kt  Media download (streaming write)
├── mission/         Mission execution
│   ├── MissionStateMachine.kt   Core state machine
│   ├── FlightPhaseExecutor.kt   Flight phases (takeoff/climb/fly to target)
│   ├── OrbitController.kt       Orbit flight (A in-place rotation, B orbit, C spiral, D expanding spiral)
│   ├── VisualYawAssist.kt       Visual yaw assist (SAD template matching)
│   ├── PhotoSequenceController.kt   Photo sequence
│   ├── VirtualStickController.kt    VS enable/disable/listen
│   └── VirtualStickParams.kt    VS parameter factory
├── mqtt/            MQTT communication layer
│   ├── MqttClientManager.kt     Connection management
│   ├── MqttMissionBridge.kt     Command bridging
│   ├── MqttStatusReporter.kt    Status reporting
│   └── MqttPayloadModels.kt     Data models
├── safety/          Safety events
│   ├── SafetyEventLogger.kt     CSV anomaly logging
│   ├── SafetyAnomalyType.kt     Anomaly type enum
│   └── SafetyAnomalyRecord.kt   Anomaly record data
├── util/            Utilities
│   ├── GeoMath.kt               GPS distance/bearing calculations
│   ├── AppLog.kt                Log wrapper (silent in release)
│   └── ...                      Other UI utilities
├── settings/        Settings page
│   └── SettingsActivity.kt      MQTT / flight limits / RC mode
└── pages/           UI pages
    ├── DJIMainActivity.kt       App entry
    └── MissionControlActivity.kt  FPV + HUD
```

## MQTT Communication

| Topic | Direction | Purpose |
|-------|-----------|---------|
| `{prefix}/command` | Backend → App | Flight commands (takeoff/rtl/cancel/reset) |
| `{prefix}/mission/start` | Backend → App | Mission start (A/B/C/D modes) |
| `{prefix}/mission/multi` | Backend → App | Multi-stop mission start (at least 2 stops) |
| `{prefix}/status` | App → Backend | Flight status reporting |
| `{prefix}/ack` | App → Backend | Command execution acknowledgement |
| `{prefix}/heartbeat` | App → Backend | Connection heartbeat |

See the [MQTT API contract](docs/mqtt-api-contract.html) for the full protocol.

## Development Environment

- Android Studio
- Kotlin / Java
- DJI MSDK v5 (**do NOT use v4 syntax**)

## Mission Flows

### Mission A
1. Auto takeoff → climb to target altitude
2. Fly to the target coordinate
3. (If `orbit.alt` is set) descend to the recording altitude
4. 370° in-place rotation recording
5. Photos (6 side + 1 nadir, 7 total)
6. Auto return-to-home and land

### Mission B
1. Auto takeoff → climb to target altitude
2. Fly to the target coordinate
3. Descend to `orbit.alt`
4. Fly to the orbit start point and stabilize facing the target center
5. Orbit the target 360° clockwise at the specified radius while recording
6. Stop recording → auto return-to-home and land

### Mission C (Spiral Ascent)
1. Auto takeoff → climb to target altitude
2. Fly to the target coordinate
3. Descend to `orbit.alt` (Z_min)
4. Fly to the orbit start point (orbit.radius north of the target), visual pre-selection locks the center
5. Start recording → spiral up clockwise at `orbit.radius` (Z_min → Z_max, N turns)
6. Gimbal adjusts dynamically with altitude, continuously locking the center
7. Gradual deceleration → GIMBAL_PULL_UP (gimbal smoothly levels over 6 s while recording) → freeze → stop recording
8. Climb to `target.alt` → auto return-to-home and land

### Mission D (Expanding Spiral Ascent)
1. Auto takeoff → climb to target altitude
2. Fly to the target coordinate
3. Descend to `orbit.alt` (Z_min)
4. Fly to the orbit start point (orbit.radius north of the target)
5. Visual pre-selection (move out 5 m → gimbal -65° → select target → yaw center)
6. Start recording → spiral up clockwise at `orbit.radius` while expanding (Z_min → Z_max, N turns)
7. Radius grows linearly from `radius` to `radius_end`, gimbal adjusts dynamically with altitude
8. Gradual deceleration → (optional `final_heading`: continue orbiting to this bearing) → GIMBAL_PULL_UP (gimbal smoothly levels over 6 s while recording) → freeze → stop recording
9. Climb to `target.alt` → auto return-to-home and land

### Flight Speed Parameters

| Parameter | Value | Description |
|-----------|-------|-------------|
| `VIRTUAL_STICK_MAX_SPEED` | 5.0 m/s | Max horizontal flight speed |
| `CLIMB_THROTTLE` | 5.0 m/s | Vertical climb/descend speed (DJI Mini 4 Pro limit) |
| `YAW_SPEED_DEG_PER_SEC` | 45 °/s | Normal yaw rotation speed |
| `RECORDING_YAW_SPEED_DEG_PER_SEC` | 12 °/s | Yaw rotation speed while recording (stable) |
| `SPEED_PROPORTIONAL_GAIN` | 0.5 | Speed = min(distance × gain, max_speed) |
| `YAW_ALIGN_PROPORTIONAL_GAIN` | 0.8 | Yaw alignment proportional gain |
| `POSITION_HOLD_MAX_SPEED` | 1.0 m/s | Position-hold max speed |
| VirtualStick frame | GROUND | pitch(+)=East, roll(+)=North, yaw=angular velocity, verticalThrottle=vertical speed |
| Command rate | 20 Hz | Control command sent every 50 ms |

### Multi-Stop Mission
1. Auto takeoff → fly to stop 1
2. Execute stop 1 mission (A, B, C, or D mode)
3. Without landing, fly directly to stop 2 (state: FLYING_TO_NEXT)
4. Execute stop 2 mission
5. Repeat until all stops are done
6. Auto return-to-home and land → download all photos

## Documentation Index

| Document | Content |
|----------|---------|
| [Architecture](docs/architecture.html) | System architecture & module design |
| [Code reference](docs/code-reference.html) | Core classes & flows quick reference |
| [MQTT API contract](docs/mqtt-api-contract.html) | Complete MQTT protocol |
| [Operation manual](docs/operation-manual.html) | Full operation guide |

## License

This project is released under the **MIT License** — see [LICENSE](LICENSE) for the full text.

- `uxsdk/` and the `dji.sampleV5.*` packages under `app/` are derived from [DJI Sample Code V5](https://github.com/dji-sdk/Mobile-SDK-Android-V5), Copyright (c) 2022 DJI, MIT License.
- All other original modules (`mission/`, `mqtt/`, `safety/`, etc.) are Copyright (c) 2026 kune, also MIT License.
- The DJI Mobile SDK for Android itself is **not distributed with this project**; it is a build-time Maven dependency governed by the [DJI EULA](http://developer.dji.com/policies/eula/).
