# Cloud SDK Dual-Stream (RC + Pilot 2) PoC Plan

> **Note:** Tightly scoped follow-up to the just-finished `poc/pilot2-composite-stream` PoC. Most plumbing (backend on 6789, ZLM on 58925, Mac IP 192.168.2.34, ffmpeg/ffplay) is already verified — this plan only adds the new piece: backend API trigger for the demand half + observe second stream.

**Goal:** With Pilot 2 manually pushing visible (RC_PLUS_LOCAL-0 alive), call `POST /manage/api/v1/live/streams/start` with `video_type:'ir'` and verify a second thermal stream appears in ZLM.

**Architecture:** Pilot 2 manual half + backend curl demand half = expected dual stream at ZLM.

**Tech Stack:** Existing backend (Spring Boot, port 6789), existing ZLMediaKit (1935 RTMP / 58925 HTTP), curl, ffmpeg.

**Known runtime values (from previous PoC):**
- Mac LAN IP: `192.168.2.34`
- ZLM secret: `psvKeKowZ3tp0Z43oC9O4gWHKFYZAkMy`
- Lens enum: `wide` / `zoom` / `ir`
- Drone SN: `RC_PLUS_LOCAL`
- Existing visible stream-id when active: `RC_PLUS_LOCAL-0`

---

## Task 1: Pre-flight — backend reachable + auth method

**Files:** none

- [ ] **Step 1: Confirm backend is running**

```bash
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:6789/manage/api/v1/login
```
Expected: `405` or `404` (route exists but GET not allowed) — NOT `000` (not running). If backend not up, start it before continuing.

- [ ] **Step 2: Obtain JWT**

Easiest path: open the running frontend at `http://localhost:8080`, log in normally, open browser DevTools → Network → any backend XHR → copy `x-auth-token` header value. Save as `$JWT` env var.

Alternative path: POST to `/manage/api/v1/login` with credentials (if you have them at hand).

```bash
export JWT="<token-here>"
echo "${#JWT}"  # should be > 100 if it's a real JWT
```

- [ ] **Step 3: Hit a JWT-gated endpoint to verify token works**

```bash
curl -s -o /dev/null -w "%{http_code}\n" -H "x-auth-token: $JWT" \
  http://localhost:6789/manage/api/v1/live/capacity
```
Expected: `200`. If `401`, JWT is invalid — refresh and retry.

---

## Task 2: Discover video_id for thermal lens

**Files:** none

- [ ] **Step 1: Fetch live capacity**

```bash
curl -s -H "x-auth-token: $JWT" \
  http://localhost:6789/manage/api/v1/live/capacity | python3 -m json.tool
```

- [ ] **Step 2: Locate the M4T entry under RC_PLUS_LOCAL drone**

Look for the object where `sn == "RC_PLUS_LOCAL"`. Inside, `cameras_list` should have one or more camera entries. For M4T:
- Likely one camera with `videos_list` containing wide/zoom/ir entries
- Each video entry has a `video_index` (or similar) and `video_type`

- [ ] **Step 3: Construct the thermal video_id**

Format: `<droneSn>/<cameraIndex>/<videoIndex>`. For thermal, find the videos_list entry whose `video_type` is `ir` and concatenate.

Example shape (will differ slightly per device):
```
RC_PLUS_LOCAL/89-0-7/0-1-1   # exact indices depend on capacity response
```

Save as `$VIDEO_ID_IR`. If no `ir` entry exists in capacity, **stop and report**: this device/firmware doesn't expose thermal as a separate channel.

---

## Task 3: [HUMAN] Start Pilot 2 manual half (visible)

Same as previous PoC. Skip if already running.

- [ ] **Step 1: On RC Plus, Pilot 2 → 第三方云平台 → 手动直播**
- [ ] **Step 2: Mode dropdown → `video-demand-aux-manual`**
- [ ] **Step 3: Tap 开始**
- [ ] **Step 4: Verify in ZLM** (run on Mac):

```bash
curl -s "http://localhost:58925/index/api/getMediaList?secret=psvKeKowZ3tp0Z43oC9O4gWHKFYZAkMy" \
  | python3 -c "import sys, json; [print(s.get('app')+'/'+s.get('stream')) for s in json.load(sys.stdin).get('data') or []]"
```
Expected: `live/RC_PLUS_LOCAL-0` listed.

---

## Task 4: Trigger demand half (thermal)

**Files:** none — pure API call

- [ ] **Step 1: POST /live/streams/start with video_type='ir'**

```bash
curl -v -X POST 'http://localhost:6789/manage/api/v1/live/streams/start' \
  -H "Content-Type: application/json" \
  -H "x-auth-token: $JWT" \
  -d "{\"url_type\":1,\"video_id\":\"$VIDEO_ID_IR\",\"video_quality\":2,\"video_type\":\"ir\"}"
```

- [ ] **Step 2: Interpret response**

Expected body: `{"code": 0, "data": {...}}` with `data.url` containing the RTMP URL the device was told to push to (should be `rtmp://192.168.2.34:1935/live/RC_PLUS_LOCAL-<something>`).

If response code != 0: read the message and Failure Modes table in the spec. Record verbatim error message for the writeup.

---

## Task 5: Verify ZLM shows two streams

**Files:** none

- [ ] **Step 1: Wait 5 seconds for device-side push to start**

```bash
sleep 5
```

- [ ] **Step 2: Poll ZLM for active streams (3 attempts, 5s apart)**

```bash
for i in 1 2 3; do
  echo "--- attempt $i ---"
  curl -s "http://localhost:58925/index/api/getMediaList?secret=psvKeKowZ3tp0Z43oC9O4gWHKFYZAkMy" \
    | python3 -c "
import sys, json
d = json.load(sys.stdin)
for s in d.get('data') or []:
    tr = ','.join((t.get('codec_id_name') or '?') + '@' + str(t.get('width')) + 'x' + str(t.get('height')) for t in (s.get('tracks') or []) if t.get('width'))
    print(f'{s.get(\"app\")}/{s.get(\"stream\")} alive={s.get(\"aliveSecond\")}s tracks=[{tr}]')"
  sleep 5
done
```

Expected: TWO different `live/RC_PLUS_LOCAL-*` entries by attempt 2 or 3.

- [ ] **Step 3: Checkpoint**

- 2+ streams visible → success path, go to Task 6
- Only 1 stream after 15s → demand didn't materialize; record this as the result and skip Task 6, jump to Task 7

---

## Task 6: Grab a frame from each stream + identify content

**Files:**
- Create: `docs/poc/cloud-sdk-dual-stream-evidence-stream0.png`
- Create: `docs/poc/cloud-sdk-dual-stream-evidence-stream1.png`

Adjust filenames if stream-ids differ.

- [ ] **Step 1: Identify the two stream IDs**

From Task 5 output, note both stream names (likely `RC_PLUS_LOCAL-0` and `RC_PLUS_LOCAL-N` where N is the thermal's payload index).

- [ ] **Step 2: Grab one frame from each**

```bash
mkdir -p /Users/likewang/uavfire/docs/poc
for SID in RC_PLUS_LOCAL-0 RC_PLUS_LOCAL-N; do  # replace -N with actual value
  ffmpeg -y -loglevel error -i "rtmp://localhost:1935/live/$SID" \
    -frames:v 1 -update 1 \
    "/Users/likewang/uavfire/docs/poc/cloud-sdk-dual-stream-evidence-$SID.png"
done
ls -la /Users/likewang/uavfire/docs/poc/cloud-sdk-dual-stream-evidence-*.png
```

- [ ] **Step 3: [HUMAN] Visually identify content of each**

Open each PNG. Record:
- Which one is visible (natural color)?
- Which one is thermal (false-color or grayscale heatmap)?
- Are either ambiguous / both same content (failure case)?

---

## Task 7: Write findings to docs/poc/cloud-sdk-dual-stream-rc.md

**Files:**
- Create: `docs/poc/cloud-sdk-dual-stream-rc.md`

- [ ] **Step 1: Create writeup using this template** (fill bracketed parts):

```markdown
# Cloud SDK Dual-Stream (RC + Pilot 2) PoC — Findings

Date: 2026-05-20
Operator: lkw
Drone: M4T
RC: RC Plus

## TL;DR

[One of:]
- ✅ Dual stream works. Two streams (visible + thermal) land at ZLM after backend demand call.
- ❌ Demand call succeeded but no second stream appeared at ZLM.
- ❌ Demand call failed at API level: [error message].
- ❌ No `ir` entry in /live/capacity for this drone.

## Setup used

- Backend on port 6789
- ZLM HTTP API 58925 / RTMP 1935
- Drone SN: RC_PLUS_LOCAL
- video_id used: [actual value]
- Mode in Pilot 2: video-demand-aux-manual

## What the API returned

[Paste verbatim curl response from Task 4 Step 1]

## ZLM observation

[Paste Task 5 Step 2 final output]

## Frame analysis

[For each saved PNG, one line: filename + 一句话内容]

## Conclusion

[One sentence. ✅ feasible / ❌ failed because <reason> / ⚠ inconclusive]

## Next-step recommendation

[If ✅] Design spec for ai-service to ingest the thermal stream + run thermal detection pipeline.
[If ❌ at API] Re-investigate backend livestream service — may need code to support this mode.
[If ❌ no second stream] Possibilities: stream-id naming collision overrides the first stream; device doesn't actually support demand+manual simultaneously; needs different mode.
```

- [ ] **Step 2: Verify files**

```bash
ls -la /Users/likewang/uavfire/docs/poc/
```
Expected: writeup md + at least one evidence png.

---

## Task 8: Commit

**Files:** writeup + evidence PNGs

- [ ] **Step 1: Stage**

```bash
git add docs/poc/cloud-sdk-dual-stream-rc.md docs/poc/cloud-sdk-dual-stream-evidence-*.png
git status -s | grep "^A "
```

- [ ] **Step 2: Commit** (choose message based on outcome)

**If success:**
```bash
git commit -m "$(cat <<'EOF'
docs(poc): Cloud SDK dual-stream on RC + Pilot 2 — VERIFIED

POST /live/streams/start with video_type:'ir' triggers second stream
appearing at ZLM under live/RC_PLUS_LOCAL-<thermal_idx>, with visible
already running as RC_PLUS_LOCAL-0. Both frames captured. This is the
clean path for M4T visible+thermal AI detection during RC manual
flight — no dock required, no MSDK takeover required.

Next: spec ai-service thermal pipeline ingest.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

**If failure:**
```bash
git commit -m "$(cat <<'EOF'
docs(poc): Cloud SDK dual-stream on RC + Pilot 2 — failed at <stage>

[one-sentence failure description]. Evidence captured for follow-up
investigation. Recommendation: <next direction>.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Done

PoC closes when Task 8 commits. Branch `poc/cloud-sdk-dual-stream-rc` stays unmerged pending user decision on next step.
