# DRC-Assisted Takeoff Design

## Goal

Replace the current official takeoff stage-1 path for the TSA page with a DRC-assisted vertical climb so
that cloud control authority stays held throughout stage 1 and the existing stage-2 `fly_to_point`
sequence can continue without an RC-side "cloud control disconnected" interruption.

## Problem Summary

The current stage-1 implementation still uses `takeoff_to_point`.
Recent backend logs show that even after changing:

- `exit_wayline_when_rc_lost=0`
- `commander_mode_lost_action=0`

the device still reports `cloud_control_auth=[]` a few seconds after takeoff begins while
`takeoff_to_point_progress` is still active.

This means the root issue is not:

- frontend toast logic
- re-enter timing
- stale authority cache
- the two lost-action parameters

On this RC Plus 2 + M4T setup, `takeoff_to_point` itself does not satisfy the product requirement
"cloud control must remain connected during stage 1".

## Recommended Approach

Use DRC-assisted climb for stage 1 and keep the current `fly_to_point`-based stage 2.

Stage 1 becomes:

1. Verify DRC session is connected to the target gateway.
2. Verify cloud control flight authorization is currently present.
3. Send an upward joystick command using the existing DRC/manual-control path.
4. Continue monitoring aircraft OSD height until the altitude threshold is reached.
5. Send a hover command to stop vertical climb.
6. Dispatch stage-2 `fly_to_point` only after the aircraft is airborne, at/near the target height,
   and in a mode that can accept `fly_to_point`.

This keeps the control path entirely inside the DRC/live-flight-control flow rather than switching to
`takeoff_to_point`.

## Scope

In scope:

- Change TSA page "takeoff" button behavior for the official two-stage flow.
- Reuse existing DRC/manual vertical control helpers where possible.
- Preserve current stage-2 south and north `fly_to_point` behavior.
- Fail fast if DRC session or cloud control authorization drops during stage 1.
- Add tests for the new stage-1 decision logic.

Out of scope:

- Changing generic takeoff behavior in other pages/components.
- Backend orchestration for takeoff.
- Automatic recovery after DRC/auth loss during stage 1.
- Android / RC Plus app changes.

## Detailed Behavior

### Stage 1: DRC-Assisted Climb

- Preconditions:
  - Current remote session must target the same gateway.
  - `remoteControlState.connected` must be true.
  - `remoteControlState.cloudControlAuthorized` must be true.
- The page captures the current origin latitude/longitude/absolute height exactly as it does now.
- Instead of `postTakeoffToPoint`, the page starts a DRC-assisted ascent.
- The ascent uses the existing vertical control path, not a new control transport.
- While ascending:
  - the page watches OSD height changes
  - the page watches DRC link state
  - the page watches cloud control authorization
- Once the aircraft reaches the configured ready threshold, the page sends hover and marks stage 1 as complete.

### Stage 2: Existing Autonomous Segment

- Keep the current second-stage flow:
  - south `fly_to_point`
  - north return `fly_to_point`
- Keep the existing mode gate before dispatching `fly_to_point`.
- Keep the current forced flight-authority-grab precheck in backend.

## State Machine Changes

Current flow uses `waiting_altitude` immediately after stage 1 command dispatch.

New phases:

- `idle`
- `climbing_stage1`
- `waiting_altitude`
- `flying_stage2_south`
- `flying_stage2_north`
- `completed`
- `failed`

Rules:

- Enter `climbing_stage1` when upward DRC climb begins.
- Transition to `waiting_altitude` only after hover has been sent and the aircraft is at/above the
  altitude threshold.
- Transition to `failed` immediately if DRC link or cloud control authorization drops during
  `climbing_stage1`.

## Error Handling

- If DRC is not connected before takeoff starts, reject the action.
- If cloud control authorization is not present before takeoff starts, reject the action.
- If the aircraft does not gain altitude within a timeout window, fail the flow and send hover.
- If DRC disconnects during climb, fail the flow and stop further automation.
- If cloud control authorization drops during climb, fail the flow and stop further automation.
- If hover fails, surface the error and stop the flow.
- If stage 2 fails after a successful climb, preserve current airborne state and report that stage 2
  did not start.

## Testing

Add focused tests for:

1. Official takeoff no longer chooses `takeoff_to_point` for stage 1.
2. Stage 1 requires active DRC and cloud control authorization.
3. Stage 1 enters `climbing_stage1` before `waiting_altitude`.
4. Auth or DRC loss during `climbing_stage1` forces failure.
5. Stage 2 dispatch remains blocked until altitude threshold is satisfied.

## Implementation Notes

- Prefer extracting a small stage-1 policy/helper module instead of embedding more branching directly in
  `tsa.vue`.
- Reuse existing `holdVerticalControl` / `publishHover` primitives if their behavior is sufficient.
- If those primitives are too coarse for continuous climb, add a dedicated helper for stage-1 climb with
  explicit start/stop semantics inside `tsa.vue` or a nearby policy module.

## Success Criteria

The change is successful if a live test shows:

- clicking takeoff does not issue `takeoff_to_point`
- RC does not immediately report cloud control disconnected during stage 1
- stage 2 can still execute automatically after the climb completes
