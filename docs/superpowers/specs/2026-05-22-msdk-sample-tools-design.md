# MSDK Sample Tools Integration Design

## Goal
Embed DJI Mobile SDK V5 sample default Aircraft Testing Tools pages into `rcplus-msdk-agent` as an independent diagnostic/debug entry, while preserving the existing UAVFire Agent runtime, livestream, MQTT, and wayline behavior.

## Scope
The integration should expose the DJI sample-style menu and migrate the associated page implementations where practical:

- Virtual Stick / basic flight controls
- Flight Record
- RTK Network / Station / Center
- Upgrade
- Simulator
- Megaphone
- Payload / PSDK pages
- Waypoint V3
- Perception
- UAS / Remote ID regional pages
- LTE
- FlySafe
- Security Code
- MOP pages
- LookAt
- Intelligent Flight

Unsupported-by-device features should remain visible but fail gracefully with clear DJI/sample error feedback rather than crashing the Agent.

## Architecture
Keep `MainActivity` as the existing UAVFire validation console. Add a separate `SampleToolsActivity` launched by a new button from the main console. The new activity owns the sample-style navigation host and migrated fragments/resources. This isolates sample UI and DJI diagnostic operations from the Agent runtime loop.

The integration favors compatibility over refactoring: copy sample page code and resources into the app namespace, add required AndroidX features (`dataBinding`, Navigation, RecyclerView), then patch package names/imports and only minimally adapt code that conflicts with the existing app.

## Safety Boundaries
- Existing background Agent services must continue to be initialized from `App` and `AppServices` without sample UI dependence.
- Existing buttons and validation console behavior must remain available.
- Sample Tools must be manually opened; no sample flight operation should auto-run.
- Virtual Stick and flight-control pages should rely on DJI callbacks and lifecycle cleanup already provided by sample ViewModels/fragments where available.
- Unsupported modules should compile and surface runtime errors instead of being removed when feasible.

## Verification
Primary verification is Android compilation for `rcplus-msdk-agent`, plus existing unit tests where they remain applicable. Full functional verification requires RC Plus/M4T real-device testing in the morning, especially for flight-control, RTK, payload, and intelligent-flight pages.
