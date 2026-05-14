# 2026-04-25 Leadership Cockpit Live Panel Design

## Goal

Replace the current cluttered dual-stream layout inside `leadership-cockpit.vue` with a clean live panel that matches the user's intent:

- keep the existing cockpit page shell
- keep the current center panel footprint roughly unchanged
- show one large primary video
- show one small thermal preview in the top-right corner
- default to visible as primary
- click the small preview to swap primary/secondary streams

## In Scope

- Only redesign the live tab panel inside the cockpit middle column.
- Remove the current large status-card grid from the live panel body.
- Preserve the existing live-tab container size within the cockpit page.
- Keep a minimal HUD overlay only:
  - live status
  - current drone
  - mode
  - stream labels
- Support stream swapping by clicking the small preview.

## Out Of Scope

- No full-page livestream route redesign.
- No change to the outer cockpit three-column page structure.
- No backend contract changes.
- No attempt to invent a thermal stream if it is not available.

## Layout

Inside the current cockpit live panel:

1. A single large video stage fills the panel body.
2. A small preview window is pinned to the top-right of the large stage.
3. The preview window shows the secondary stream.
4. A compact HUD sits on top of the stage instead of separate large cards.

Default state:

- primary: visible
- preview: thermal

Swapped state after preview click:

- primary: thermal
- preview: visible

If thermal is unavailable:

- visible remains the primary stream
- preview stays as a placeholder state card in the same top-right position

## Data Flow

- Continue polling the existing dual-stream runtime state.
- Reuse the current visible stream player path.
- If `thermalPlayUrl` exists, mount a second player for the preview.
- If `thermalPlayUrl` does not exist, render the preview as an unavailable/degraded placeholder.
- Stream swap changes only frontend presentation state.

## Error Handling

- If primary stream fails, show an in-stage error overlay.
- If preview stream fails, keep the preview box visible and show a compact error state.
- If runtime state is unavailable, keep the live panel shell and show a single centered state message.

## Testing

- Live tab shows large visible stream by default.
- Thermal preview appears in the top-right when available.
- Clicking preview swaps visible and thermal.
- Clicking again swaps back.
- If thermal is absent, preview remains a placeholder and does not break visible playback.
- Existing cockpit map tab remains unaffected.
