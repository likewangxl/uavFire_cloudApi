# Codex Work Record

Date: 2026-04-16

## Scope

- Investigated DJI Cloud API DRC control behavior for RC Plus 2.
- Updated frontend and backend code for official `takeoff_to_point` flow.
- Fixed backend console/log encoding startup approach during local verification.
- Prepared and pushed the frontend/backend source code to `https://github.com/likewangxl/uavFire_cloudApi.git`.
- Added Claude and Codex work records under `work-records/`.

## Main Findings

- The official RC Plus 2 `takeoff_to_point` API requires target longitude and latitude.
- `takeoff_to_point` is an official takeoff-to-coordinate-and-hover command, not a local 1 m stick simulation command.
- Official constraints require `security_takeoff_height` to be at least 20 m.
- RC Plus 2 gateway should not be checked with dock-only idle mode logic before takeoff.

## Code Changes

- Frontend: `frontend/src/pages/page-web/projects/tsa.vue`
  - Replaced the previous simulated stick takeoff flow with official `takeoff_to_point`.
  - Uses current OSD longitude and latitude as the target point.
  - Sends official takeoff parameters including target height, security takeoff height, speed, and lost-link behavior.

- Backend: `backend/uavfire/src/main/java/com/yx/uavfire/control/service/impl/ControlServiceImpl.java`
  - Limited dock idle-mode checks to dock gateways.
  - Allows RC Plus 2 control flow to proceed after device online and flight authority checks.

- Backend: `backend/uavfire/src/main/java/com/yx/uavfire/control/model/param/TakeoffToPointParam.java`
  - Updated `securityTakeoffHeight` validation to match the documented minimum of 20 m.

## Verification

- Ran frontend build with `npm run build`; build completed with existing warnings.
- Ran backend compile with `mvn -pl uavfire -DskipTests compile`; compile completed with existing Maven warnings.
- Restarted backend service on port 6789 with readable UTF-8 console output.
- Confirmed the frontend source and backend source were copied to the publish repository without generated folders such as `node_modules`, `dist`, `target`, and runtime log files.

## GitHub Publish

- Repository: `https://github.com/likewangxl/uavFire_cloudApi.git`
- Branch: `main`
- Initial replacement commit: `0985186 Initialize UAV fire Cloud API frontend and backend`
- Remote `main` was force-updated to replace the previous repository contents with the current frontend/backend code.
