# Official Takeoff Two-Stage Design

## Goal

Adjust the current "official takeoff" behavior so that:

- The implementation still uses the aircraft's current OSD latitude/longitude as the origin.
- The old "offset current position north by about 16 m and send one `takeoff_to_point`" flow is removed.
- Stage 1 only performs safe takeoff and climbs to 30 m AGL.
- Stage 2 starts only after the aircraft is confirmed near 30 m, then sends `fly_to_point`.
- Stage 2 flies about 20 m north of the same origin position while keeping 30 m height.

## Current Problem

The existing implementation in `frontend/src/pages/page-web/projects/tsa.vue` sends a single
`takeoff_to_point` request whose target point is roughly 16 m north of the current OSD position.
Although the frontend already sends `target_height=30`, `security_takeoff_height=30`, and
`commander_flight_height=30`, backend logs show the aircraft-side planned path later includes lower
heights after reaching about 30 m. This means the height drop is not caused by missing frontend
parameters. The more likely cause is that a single `takeoff_to_point` lets the aircraft generate a
combined autonomous path whose later segments are outside our direct control.

## Recommended Approach

Use a two-stage autonomous flow initiated from the frontend:

1. Read current aircraft OSD latitude/longitude exactly as today.
2. Send `takeoff_to_point` using the current position as the target horizontal point, with
   height-related parameters fixed at 30 m.
3. Wait until telemetry confirms the aircraft is airborne and altitude is close enough to 30 m.
4. Automatically send `fly_to_point` whose target is about 20 m north of the same origin point and
   whose target height is 30 m.

This separates "climb to altitude" from "translate horizontally", which is the key change needed
to keep altitude behavior predictable.

## Detailed Behavior

### Stage 1

- Input source remains current aircraft OSD latitude/longitude.
- `takeoff_to_point` target latitude equals current latitude.
- `takeoff_to_point` target longitude equals current longitude.
- `target_height=30`
- `security_takeoff_height=30`
- `commander_flight_mode=SETTING_HEIGHT`
- `commander_flight_height=30`

Expected result: aircraft takes off and climbs to 30 m without embedding a 16 m northbound segment
inside the same command.

### Stage 2

- Use the same origin position captured before Stage 1 starts.
- Compute target latitude as `originLatitude + northOffset20m`.
- Keep target longitude equal to `originLongitude`.
- `fly_to_point.height=30`
- `fly_to_point.max_speed` keeps existing safe default unless further tuning is requested.

Expected result: once stable near 30 m, the aircraft flies about 20 m north while holding 30 m.

## Trigger and Completion Rules

Frontend should treat Stage 1 as ready for Stage 2 only when all of the following are true:

- Aircraft telemetry is present and valid.
- Relative height is near 30 m within a tolerance window.
- Aircraft is no longer in a ground or immediate takeoff-transition state.
- Stage 2 has not already been dispatched for the current run.

Recommended tolerance:

- Altitude ready threshold: `>= 28 m`

This avoids waiting for an exact floating-point 30.0 while still preventing early horizontal motion.

## State Management

Add a small in-memory state machine in `tsa.vue` for the official takeoff flow:

- `idle`
- `taking_off`
- `waiting_altitude`
- `flying_forward`
- `completed`
- `failed`

State should track:

- gateway SN
- aircraft SN
- captured origin latitude/longitude
- whether Stage 2 has been sent
- last known altitude

The state lives only in the page runtime and resets on:

- explicit failure
- manual cancellation if supported later
- page reload
- completion

## Error Handling

- If Stage 1 request fails, stop immediately and show the backend error.
- If telemetry never reaches the altitude threshold within a timeout, mark the flow failed and do
  not send Stage 2.
- If Stage 2 request fails, preserve the aircraft's current airborne state and show a clear message
  that takeoff succeeded but forward flight did not start.
- If cloud control authority is released before Stage 2 dispatch, fail fast with a message that the
  operator must re-enter DRC before retrying.

## User-Facing Copy

The confirmation copy for official takeoff should change to reflect the new flow:

- Stage 1: take off and climb to 30 m
- Stage 2: then fly about 20 m north
- No mention of the old 16 m north offset

Success/failure messages should distinguish:

- takeoff request sent
- waiting for altitude
- forward flight request sent
- forward flight could not start

## Testing

Verification should cover:

1. `takeoff_to_point` request now uses current latitude/longitude without the old 16 m offset.
2. Stage 2 does not dispatch before altitude threshold is reached.
3. Stage 2 uses about 20 m north offset and height 30 m.
4. Duplicate Stage 2 dispatch is prevented when telemetry updates rapidly.
5. Timeout/failure path leaves the UI in a recoverable state.

## Scope Limits

This design does not attempt to solve:

- automatic re-acquisition of cloud flight authority after autonomous commands
- changes to return-home behavior
- backend-side orchestration of the two-stage flow

The implementation remains frontend-driven unless later evidence shows the sequencing must move to
the backend.
