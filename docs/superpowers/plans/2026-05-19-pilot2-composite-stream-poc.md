# Pilot2 Composite Stream PoC Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **Note:** This PoC has steps that can only be executed by a human at the device (Pilot2 UI on RC Plus, visual stream judgment). Tasks marked `[HUMAN]` require operator action — a subagent cannot complete them. Subagents can do prep, troubleshooting, and writeup.

**Goal:** Verify whether Pilot2 RTMP livestream pushes the PIP composite frame (visible + thermal in one frame) to backend ZLMediaKit. Answer is binary: feasible or not.

**Architecture:** Pilot2 (RC Plus, Android) → RTMP push → ZLMediaKit (Mac :1935) → ffplay + visual judgment by operator. Zero code changes; pure device-side configuration + observation.

**Tech Stack:** ZLMediaKit (existing, port 1935 RTMP, port 8090 HTTP API), ffplay/ffmpeg (`/usr/local/bin/`), DJI Pilot 2 (Android app on RC Plus).

**ZLM secret:** `CloudApiSample` (from `backend/uavfire/src/main/resources/application.yml`).

---

## Task 1: Pre-flight backend checks

**Files:** none (read-only)

- [ ] **Step 1: Verify ZLM is running and reachable**

Run:
```bash
curl -s "http://localhost:8090/index/api/getServerConfig?secret=CloudApiSample" | head -c 200
```
Expected: JSON starting with `{"code":0,"data":[{...`. Code 0 means OK.

If you see "secret error" or non-zero code: secret is wrong — check `application.yml`.
If connection refused: ZLM is not running — start it before continuing.

- [ ] **Step 2: Get Mac LAN IP**

Run:
```bash
ifconfig | grep "inet 192" | awk '{print $2}'
```
Expected: a single IP like `192.168.x.x`. Record it. We'll call this `<MAC_IP>` below.

If you see multiple IPs (multi-NIC), pick the one on the same wifi as RC Plus.

- [ ] **Step 3: Confirm ffplay + ffmpeg available**

Run:
```bash
which ffplay ffmpeg
```
Expected: two paths printed (both `/usr/local/bin/`). If missing, install:
```bash
brew install ffmpeg
```

---

## Task 2: ZLM RTMP smoke test (isolates ZLM problems from Pilot2 problems)

Before relying on Pilot2 to push correctly, verify ZLM accepts any RTMP push. If this fails, fix ZLM first — no point debugging Pilot2.

**Files:** none

- [ ] **Step 1: Push a synthetic test pattern from Mac to ZLM**

In Terminal #1, run (keep running):
```bash
ffmpeg -re -f lavfi -i testsrc=size=640x480:rate=30 -c:v libx264 -preset ultrafast -tune zerolatency -f flv "rtmp://localhost:1935/live/smoke-test"
```
Expected: streaming output, frames per second updates.

- [ ] **Step 2: Verify ZLM sees the stream**

In Terminal #2, run:
```bash
curl -s "http://localhost:8090/index/api/getMediaList?secret=CloudApiSample" | grep -o '"stream":"[^"]*"'
```
Expected: `"stream":"smoke-test"` in output.

If empty: ZLM is up but not accepting pushes — check ZLM config for `enable_rtmp` or auth hooks.

- [ ] **Step 3: Play the smoke stream to confirm pipeline works end-to-end**

In Terminal #3, run:
```bash
ffplay -loglevel warning "rtmp://localhost:1935/live/smoke-test"
```
Expected: ffplay window opens showing color bars + moving counter.

- [ ] **Step 4: Stop everything**

Ctrl+C the ffmpeg push, close ffplay window.

- [ ] **Step 5: Checkpoint**

If smoke test passed: ZLM is fully working — Pilot2 issues will be on Pilot2 side.
If smoke test failed: fix ZLM first. **Do not proceed.**

---

## Task 3: [HUMAN] Configure Pilot2 RTMP push URL

This must be done on the RC Plus device. A subagent cannot do this.

**Files:** none (device-side UI)

- [ ] **Step 1: Open Pilot 2 on RC Plus**

- [ ] **Step 2: Navigate to live-stream settings**

Path: 设置 → 直播平台 → 选择 "自定义 RTMP" / "RTMP Custom"

(If you cannot find the option: try the live-stream button on the flight UI top bar first → 直播设置 → RTMP)

- [ ] **Step 3: Enter the RTMP URL**

URL format: `rtmp://<MAC_IP>:1935/live/pilot2-composite`

Concrete example (replace with your `<MAC_IP>` from Task 1):
```
rtmp://192.168.2.34:1935/live/pilot2-composite
```

ZLM by default does **not** require secret on push (`hook.on_publish` is what gates that, currently unset). Try without secret first. If Pilot2 reports auth failure, append `?secret=CloudApiSample`:
```
rtmp://192.168.2.34:1935/live/pilot2-composite?secret=CloudApiSample
```

- [ ] **Step 4: Save settings, do NOT start streaming yet**

---

## Task 4: [HUMAN] Enable Pilot2 PIP / dual-lens view

**Files:** none (device-side UI)

- [ ] **Step 1: Enter Pilot 2 flight interface (manual flight / 手动飞行)**

The drone needs to be powered on and connected. You do not need to take off — ground preview is enough.

- [ ] **Step 2: Locate the camera switch / PIP control**

M4T typically exposes:
- A "主+次镜头" (main + secondary) toggle, OR
- A small PIP icon on the camera bar

- [ ] **Step 3: Set up dual-lens display**

Configure so that:
- Main view = wide / zoom (visible light)
- PIP small window = thermal

Confirm both views are visible in the Pilot 2 UI before pushing.

- [ ] **Step 4: Record the PIP layout for the final notes**

Note (mentally / in scratchpad): which lens is main? Where does the thermal window appear (corner, size)? This goes in the writeup.

---

## Task 5: [HUMAN] Start RTMP push from Pilot2

**Files:** none

- [ ] **Step 1: Open the live-stream control on the flight UI**

Usually a "直播" / "Live" button on the top toolbar.

- [ ] **Step 2: Tap "开始直播" / "Start Live Stream"**

- [ ] **Step 3: Confirm push is active**

Look for an indicator: green dot, "直播中" / "LIVE" badge, or bitrate counter.

If Pilot2 reports push error here: re-check URL, network, Mac firewall. Stop and resolve before continuing.

---

## Task 6: Verify ZLM received the stream

**Files:** none

- [ ] **Step 1: Check ZLM media list**

Run:
```bash
curl -s "http://localhost:8090/index/api/getMediaList?secret=CloudApiSample" | grep -o '"stream":"[^"]*"'
```
Expected: `"stream":"pilot2-composite"` in output.

- [ ] **Step 2: If stream not present, troubleshoot in this order**

```bash
# 1. Firewall (port 1935 reachable from LAN)
sudo lsof -iTCP:1935 -sTCP:LISTEN
# Expected: ZLM process listening on *:1935 (not just 127.0.0.1)

# 2. Connectivity from RC Plus to Mac
adb shell ping -c 3 <MAC_IP>
# Expected: 0% packet loss

# 3. ZLM logs (tail recent entries during push attempt)
# Location depends on ZLM install — common: /usr/local/var/log/ZLMediaKit/
ls /usr/local/var/log/ZLMediaKit/ 2>/dev/null && tail -30 /usr/local/var/log/ZLMediaKit/*.log
```

Resolve before continuing. **Do not proceed if ZLM is not receiving the stream.**

---

## Task 7: Play stream and capture screenshot

**Files:** none yet (screenshot saved in Task 9)

- [ ] **Step 1: Play the stream**

Run:
```bash
ffplay -loglevel warning -fflags nobuffer "rtmp://localhost:1935/live/pilot2-composite"
```
Expected: ffplay window opens within ~3 seconds, showing video.

- [ ] **Step 2: Wait for ~5 seconds of stable playback**

Initial frames may stutter or show black — let it stabilize.

- [ ] **Step 3: Take a screenshot of the ffplay window**

macOS: `Cmd+Shift+4` → click on the ffplay window → screenshot lands on Desktop.

Move it to a known path:
```bash
mkdir -p /Users/likewang/uavfire/docs/poc
mv ~/Desktop/Screen\ Shot*.png /Users/likewang/uavfire/docs/poc/pilot2-composite-evidence.png
# or rename whatever the actual file is called
ls -la /Users/likewang/uavfire/docs/poc/pilot2-composite-evidence.png
```
Expected: file exists.

- [ ] **Step 4: Close ffplay (q key) and stop Pilot2 push**

On RC Plus: tap "结束直播" / "Stop Live Stream".

---

## Task 8: Judge composite presence

**Files:** none (decision only)

- [ ] **Step 1: Inspect the screenshot**

Open `/Users/likewang/uavfire/docs/poc/pilot2-composite-evidence.png`.

- [ ] **Step 2: Answer three questions in writing (you'll use these in Task 9)**

1. Can you identify **visible-light content** in the frame? (normal-color terrain, buildings, sky, etc.) Y/N
2. Can you identify **thermal content** in the frame? (false-color heat map, e.g. red/yellow/blue palette, OR grayscale heat) Y/N
3. Are both in the **same frame**? Y/N

- [ ] **Step 3: Determine outcome**

- All three Y → ✅ **Pilot2 PIP composite RTMP works** → option B is feasible
- Q1 only → ❌ **Pilot2 only pushed visible** → option B not feasible
- Q2 only → ❌ **Pilot2 only pushed thermal** → option B not feasible (rare but possible)
- Stream playable but neither visible nor thermal recognizable → check codec/decoder, may need raw frame dump to diagnose

---

## Task 9: Write up findings

**Files:**
- Create: `docs/poc/pilot2-composite-stream.md`
- Screenshot already saved: `docs/poc/pilot2-composite-evidence.png`

- [ ] **Step 1: Create the notes file**

Use this template (fill in the bracketed parts with actual values from the run):

```markdown
# Pilot2 Composite Stream PoC — Findings

Date: 2026-05-19
Operator: [your name]
Pilot 2 version: [look up in Pilot2 "关于" page]
Drone: M4T
RC: RC Plus

## Setup used

- Mac LAN IP: [actual IP]
- RTMP URL: `rtmp://[actual IP]:1935/live/pilot2-composite[?secret=...]`
- ZLM version: [run `curl -s "http://localhost:8090/index/api/version?secret=CloudApiSample"`]
- PIP layout: [describe — e.g. "main=wide visible, small window=thermal bottom-left"]

## What happened

[Did Pilot2 accept the URL? Did push start? Any error messages on RC side?]

## Backend observation

[Did ZLM list the stream? curl output? Did ffplay open and play?]

## Visual judgment

- Visible light content present in frame: [Y/N]
- Thermal content present in frame: [Y/N]
- Both in the same frame: [Y/N]

See evidence: `pilot2-composite-evidence.png`

## Conclusion

[One of:]
- ✅ Option B is FEASIBLE. Pilot2 RTMP pushes the PIP composite frame containing both visible and thermal content.
- ❌ Option B is NOT feasible. Pilot2 RTMP only pushes [visible|thermal] regardless of PIP UI state.
- ⚠ Inconclusive — [reason, e.g. ZLM refused push, Pilot2 disabled RTMP when PIP active, etc.]

## Troubleshooting that was needed

[Any deviations from the original procedure — e.g. "had to add secret to URL", "Pilot2 PIP control was under camera settings not flight UI", etc.]

## Next step recommendation

[One of:]
- B feasible → design backend split + ai-service dual-path ingestion (separate spec)
- B not feasible → fall back to option A (single-lens Pilot2 RTMP) for cockpit display, and option D (rcplus-msdk-agent dual-stream) for AI detection in agent-primary mode
- Inconclusive → [specific next experiment]
```

- [ ] **Step 2: Verify file**

Run:
```bash
ls -la /Users/likewang/uavfire/docs/poc/
```
Expected: both `pilot2-composite-stream.md` and `pilot2-composite-evidence.png` present.

---

## Task 10: Commit findings

**Files:**
- `docs/poc/pilot2-composite-stream.md`
- `docs/poc/pilot2-composite-evidence.png`

- [ ] **Step 1: Stage**

```bash
git add docs/poc/pilot2-composite-stream.md docs/poc/pilot2-composite-evidence.png
git status -s | grep "^A "
```
Expected: both files staged, nothing else.

- [ ] **Step 2: Commit**

Pick the commit message that matches the outcome:

**If feasible:**
```bash
git commit -m "$(cat <<'EOF'
docs(poc): Pilot2 RTMP pushes PIP composite — option B feasible

Verified on M4T + RC Plus: when Pilot 2's PIP/dual-lens mode is
active, the built-in RTMP livestream pushes the composite frame
(visible + thermal in one frame) to backend ZLMediaKit. Evidence
screenshot captured. Next step: design backend split + ai-service
dual-path ingestion as a follow-up spec.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

**If not feasible:**
```bash
git commit -m "$(cat <<'EOF'
docs(poc): Pilot2 RTMP does NOT push PIP composite — option B dead

Verified on M4T + RC Plus: with Pilot 2's PIP/dual-lens mode
active, the built-in RTMP livestream pushes only [visible|thermal]
regardless of UI state. Evidence screenshot captured. Recommendation:
fall back to option A (single-lens Pilot2 RTMP for cockpit) and
option D (rcplus-msdk-agent dual-stream for AI in agent-primary mode).

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 3: Verify commit landed**

```bash
git log -1 --stat
```
Expected: commit shows 2 files added.

---

## Done

The PoC is complete when Task 10 commits successfully. Outcome (feasible / not feasible / inconclusive) is recorded in the notes file and screenshot is preserved as evidence. Next-step decision is captured in the notes.

Do **not** merge this branch to main until the user decides what to do based on the findings.
