# SDD ledger — plan: docs/superpowers/plans/2026-07-30-agent-visible-fire-closed-loop.md

Execution branch: feature/agent-visible-fire-closed-loop
Starting commit: 04c694a19642eedabadc83f77e7c54513c4aa909
Task 1: minor (deferred): README post-copy hash verification wording lacks an exact committed verification command.
Task 1: fix round 1/5 (1 addressed, 0 open — invalid YOLO class and geometry validation; commits 5296acd..c80232f)
Task 1: complete (commits 04c694a..c80232f, review clean)
Task 2: human ruling (2026-07-30): existing NCNN thermal-640 result may provisionally select NCNN to unblock development; it must be labeled provisional and cannot claim the visible-960 RC Plus gate or final production acceptance passed.
Task 2: fix round 1/5 (4 addressed, 2 open — asset/baseline binding, session provenance, Agent trust, NCNN identity; commits c9fdb21..e60e233)
Task 2: fix round 2/5 (2 addressed, 2 open — actual NCNN identity and provisional isolation closed; provider health and persisted digest remained; commits e60e233..55a2d17)
Task 2: fix round 3/5 (5 addressed, 0 open — provider resolution, live health, canonical digest, persisted/export identity; commits 55a2d17..356003b)
Task 2: complete provisionally (commits c80232f..356003b, review clean; formal visible-960 device gate remains fail-closed pending approved release anchor, 400-sample set, and RC Plus access)
Task 3: minor (deferred): NCNN packaging tasks force rebuild and defeat Gradle incrementality.
Task 3: minor (deferred): letterbox Float.toInt truncation can sample a boundary pixel for coordinates in (-1, 0).
Task 3: fix round 1/5 (5 addressed, 1 open — detector/Vulkan lifetime, typed failures, immutable cache, exact output shape; ABA token risk remained; commits 0e96bfd..817b19f)
Task 3: deferred minors resolved in commit 817b19f.
Task 3: fix round 2/5 (1 addressed, 0 open — monotonic native token registry closes ABA stale-handle risk; commits 817b19f..b22d6a5)
Task 3: complete (commits 356003b..b22d6a5, review clean; default-off provisional integration)
Task 2: rereview-1 supersedes the earlier APPROVE/CLEAR conclusion; fix round 2 closes repository Agent trust/health, actual NCNN execution identity, and persisted report/export integrity.
Task 2: formal device gate remains fail-closed until a reviewed Formal Agent signer/APK/version/buildId is committed and RC Plus 2 reruns visible-960.
Task 2: fix round 3/5 addresses rereview-2 provider reachability, reversible/fresh live health, serialization-stable numeric digests, persisted NCNN APK equality, and current-identity recapture before export.
Task 4: review requested changes (4 important, 2 minor): start/close lifecycle race, non-atomic terminal metrics, callback-side unthrottled copy/admission race, frame/source switch misbinding; concurrent ownership tests and shared cross-language golden fixture also required.
Task 4: fix round 1/5 started from d2a04db; RC Plus callback latency/GC evidence is deferred because the device is unavailable, while deterministic code/test fixes remain mandatory.
Task 4: fix round 1/5 (4 addressed, 2 open — lifecycle/metrics/generation/shared fixture fixed; pre-copy admission TOCTOU and non-RGB visible-source fallback remain; commits d2a04db..972ae323).
Task 4: fix round 2/5 started from 972ae323; device-only callback/decoder evidence remains pending and does not replace the two static fixes.
Task 4: fix round 2/5 implementation complete (2 addressed, 0 known open — linearized copy admission and strict RGB-visible source allowlist; commits 972ae323..55b66c8); independent rereview pending.
Task 4: fix round 2/5 (1 addressed, 1 open — RGB allowlist closed; Copying-to-Frame publication can still resurrect a slot after close/source-switch returns; commits 972ae323..55b66c8).
Task 4: fix round 3/5 started from 55b66c8; add revocable publication state and exact publication-window race tests.
Task 4: fix round 3/5 implementation complete (1 addressed, 0 known open — close/source-switch atomically revoke Copying publication; commit a846592); final independent rereview pending.
Task 4: complete (commits b22d6a5..a846592, final rereview APPROVE; Critical 0 / Important 0 / Minor 0). RC Plus callback latency/GC and real decoder-queue behavior remain pending device gates; NCNN evidence remains provisional and the feature flag remains default-off.
Task 5: implementation complete (commit 25e6556; focused 14/14, full JVM 281/281, packaging 2/2, SDK health 4/4); independent review pending.
Task 5: review requested changes (Critical 0 / Important 5 / Minor 2): terminal durability ordering, pending-frame freshness, trusted timestamp watermark, NMS contract binding, immutable evidence, monotonic observation time, and narrow exception handling.
Task 5: fix round 1/5 started from 25e6556.
Task 5: fix round 1/5 implementation complete (7 addressed, 0 known open — terminal proof, pair freshness, trusted clocks, NMS contract, immutable evidence, narrow exceptions; commits 25e6556..dd92759); independent rereview pending.
Task 5: fix round 1/5 (3 addressed, 4 open — pair freshness, NMS, immutable evidence and exception handling fixed; opaque persistence proof, correlated initial/terminal ACK identity, and candidate-free timeline remain; commits 25e6556..dd92759).
Task 5: fix round 2/5 started from dd92759.
Task 5: fix round 2/5 implementation complete (4 addressed, 0 known open — opaque reducer-owned phase, exact initial/terminal request identity, candidate-free accepted timeline; commits dd92759..a7f49df); final independent rereview pending.
Task 5: complete (commits a846592..a7f49df, final rereview APPROVE; Critical 0 / Important 0 / Minor 1). The documentation-only stale watermark sentence was corrected immediately; code and Task 2–4 regression review are clean.
Task 6: implementation complete (commit 15ec23e; real SQLite focused 15/15, Agent 307/307, benchmark 76/76, build/audits green); independent review pending.
Task 6: review requested changes (Critical 0 / Important 5 / Minor 2): expired lease completion, canonical payload binding, terminal source phase, duplicate full identity, recursive metadata allowlist, boot epoch fallback, and composite session/event foreign keys.
Task 6: fix round 1/5 started from 15ec23e.
Task 6: fix round 1/5 implementation complete (7 addressed, 0 known open — lease expiry ownership, canonical report identity, terminal gate, full duplicate identity, typed evidence, boot fallback, composite FKs; commits 15ec23e..908b9af); independent rereview pending.
Task 6: fix round 1/5 (4 addressed, 3 open — lease/outbox/evidence/boot/FK fixed; state-specific report semantics, bounded numeric canonicalization, and terminal-command provenance remain; commits 15ec23e..908b9af).
Task 6: fix round 2/5 started from 908b9af.
Task 6: fix round 2/5 implementation complete (3 addressed, 0 known open — state-specific typed report schemas, bounded number canonicalization, durable pending terminal registration; commits 908b9af..eadd450); final independent rereview pending.
Task 6: fix round 2/5 (2 addressed, 1 open — typed schemas/numeric bounds/pending terminal verified; progress reason remains caller-controlled; commits 908b9af..eadd450).
Task 6: fix round 3/5 started from eadd450.
Task 6: fix round 3/5 implementation complete (1 addressed, 0 known open — closed typed MANUAL_HOLD reasons and exact payload binding; commit 132accb); final independent rereview pending.
Task 6: complete (commits a7f49df..132accb, final rereview APPROVE; Critical 0 / Important 0 / Minor 0). RC Plus remains unavailable; automated SQLite/NCNN evidence is not a device acceptance claim.
Task 7: implementation complete (commit 641f6e4; focused 16/16, Agent 333/333, benchmark 76/76, build green); independent review pending.
Task 7: review requested changes (Critical 1 / Important 4 / Minor 2): atomic mission generation/state identity, replay-safe observation, per-token submitted outcome, trusted command-time safety, stable-hover proof, monotonic typed failure, and strict breakpoint validation.
Task 7: fix round 1/5 started from 641f6e4.
Task 7: fix round 1/5 implementation complete (7 addressed, 0 known open — atomic mission snapshots/generations, replay-safe observation, submitted outcome tracking, trusted fresh safety, stable-hover proof, typed clocks, strict breakpoints; commits 641f6e4..46c0734); independent rereview pending.
Task 7: fix round 1/5 (2 addressed, 5 open — atomic new-command path/subscription/breakpoints improved; legacy generation invalidation, success lifecycle session key, evidence claim ordering, hover proof epoch, observer iteration remain; commits 641f6e4..46c0734).
Task 7: fix round 2/5 started from 46c0734.
Task 7: fix round 2/5 implementation complete (6 addressed, 0 known open — legacy generation, exact-session terminal lifecycle, versioned safety claim boundary, hover epoch, serialized observers, timestamp causality; commits 46c0734..22832a4); independent rereview pending.
Task 7: fix round 2/5 (4 addressed, 2 open — resume lifecycle/claims/hover/causality fixed; stop command generation and paused-command-bound hold token remain, plus observer exception minor; commits 46c0734..22832a4).
Task 7: fix round 3/5 started from 22832a4.
Task 7: fix round 3/5 implementation complete (3 addressed, 0 known open — stop command generation, paused-command-bound hold/evidence, observer exception isolation; commit 58806da); final independent rereview pending.
Task 7: fix round 3/5 (3 addressed, 1 open — legal initial paused commandGeneration=0 rejected by token/evidence invariants; commit 58806da).
Task 7: fix round 4/5 started from 58806da.
Task 7: fix round 4/5 implementation complete (1 addressed, 0 known open — legal initial paused commandGeneration=0 accepted with exact matching, negative rejected; commit 8e418ed); final independent rereview pending.
Task 7: complete (commits 132accb..8e418ed, final rereview APPROVE; Critical 0 / Important 0 / Minor 0). RC Plus flight/device validation remains pending; NCNN automated evidence is provisional only.
Task 8: implementation complete (commit cde3aea; focused 32/32, Agent 372/372, benchmark 76/76, build green; pure-local inference result stream wired and backend latestVisibleRoi path removed); independent review pending.
Task 8: review requested changes (Critical 0 / Important 6 / Minor 2): lossless post-action result cursor, immutable complete inference evidence, intrinsically fresh laser observations, live source-generation guard, exact session/event ownership isolation, timestamped fresh OSD, typed timeout, and publication invariants.
Task 8: fix round 1/5 started from cde3aea.
Task 8: fix round 1/5 implementation complete (8 addressed, 0 known open — lossless immutable result journal, hardware-bound laser callbacks, live source guard, exact ownership, timestamped fresh OSD, typed timeout/publication invariants; commits cde3aea..190064f); independent rereview pending.
Task 8: fix round 1/5 (5 addressed, 3 open — result/OSD/source contracts fixed; pre-admission sample interval, cancellation-safe hold ownership, and laser listener registration rollback remain; plus two immutability/request-invariant minors; commits cde3aea..190064f).
Task 8: fix round 2/5 started from 190064f.
Task 8: fix round 2/5 implementation complete (5 addressed, 0 known open — pre-admission interval gate, cancellation-safe hold, laser listener lifecycle rollback, immutable samples, strict await requests; commits 190064f..7516a44); final independent rereview pending.
Task 8: complete (commits 8e418ed..7516a44, final rereview APPROVE; Critical 0 / Important 0 / Minor 0). RC Plus device validation remains pending; pure-local ROI/laser code remains default-off.
Task 9: complete (commits 7516a44..adbeb12, final rereview APPROVE; Critical 0 / Important 0 / Minor 0; Agent 458/458, benchmark 76/76, AndroidTest compile and APK build green; real SQLite/Outbox/Task7/Task8 integration reaches MissionResumed). RC Plus/real-flight validation remains pending; Task 10 transport gate remains intentionally unhealthy until implemented.
Task 10: complete (commits adbeb12..5654f60, final rereview APPROVE; Critical 0 / Important 0 / Minor 0; Agent 476/476, backend 443/443, benchmark 76/76, AndroidTest compile and APK build green). Historical Outbox replay uses a 30-day retention window with future-skew protection; drone/task identities remain independent through SQLite v5 and recovery; Task 11 durable ingress and RC Plus/real-flight validation remain pending.
Task 11: complete (commits 5654f60..eda57f8, final rereview APPROVE; Critical 0 / Important 0 / Minor 0; backend 471/471, focused lifecycle 101/101, Agent transport 29/29, migration double-run green). Ordered raw-payload persistence, immutable per-flight bindings, OSD/precise coordinate semantics, and notification transaction seam are closed; Task 12 notification Outbox and RC Plus/real-flight validation remain pending.
Task 12: implementation fix round 1 complete (commits 5d25743..07bf806; focused backend 44/44, full backend 493/493, forced Agent transport 29/29, migration double-run green; missing transaction synchronization now fails closed and monotonic wake drain prevents concurrent after-commit wake loss); independent rereview pending. RC Plus/real-flight validation remains pending.
Task 12: complete (commits eda57f8..07bf806, final rereview APPROVE; Critical 0 / Important 0 / Minor 0; focused backend 44/44, full backend 493/493, Agent transport 29/29, migration double-run green). Transactional notification Outbox, exact Chinese alert stages, WebSocket updates, monotonic after-commit wakeup, and scheduled retry fallback are closed; RC Plus/real-flight validation remains pending.
Task 13: started from 07bf806. Production backend must stop invoking ai-service and stop orchestrating automatic hold/ROI/laser/confirmation missions; detector arming and observed status move to the Agent command/heartbeat contract while manual operator safety commands remain available.
Task 13: implementation complete (backend 494/494, Agent 479/479, debug APK and production-source audit green). Backend start/stop now controls Agent detector intent; status is heartbeat-observed with a 15-second fail-closed freshness gate; ai-service, livestream auto-trigger, backend ROI/laser/confirmation orchestration, and Agent legacy remote fire actions are removed. RC Plus/real-flight validation remains pending and no device acceptance is claimed.
# Task 13 review remediation (2026-08-01)

- Fixed Agent JWT coverage and drone-SN binding for authoritative detector control endpoints.
- Added atomic, durable, versioned detector desired state and backend/Agent restart reconciliation.
- Tightened truthful running status to real Agent enum/legal heartbeat combinations.
- Preserved detector heartbeat fields through cache and Redis restore.
- Removed obsolete commented/vacuous tests.
- Verification: backend 500/500, Agent 484/484, debug APK assembled; RC Plus acceptance remains pending.
Task 13: complete (final commit 05829aa, independent rereview APPROVE; Critical 0 / Important 0 / Minor 0). Detector observations are monotonic across backend nodes; RC Plus device acceptance remains pending.
Task 14: complete (final commit 1ae5fd3, independent rereview APPROVE; Critical 0 / Important 0 / Minor 0). Frontend reconciles authoritative fire snapshots and renders controlled Chinese status text.
Task 15: complete (commits 8701e5e and 775ced7; static production policy and acceptance/rollback documentation complete). Clean code baseline 05829aa gates: static PASS, offline pytest 199 passed/1 skipped, backend 514/514, Agent 491/491, benchmark 76/76, frontend 303/303, Agent APK/frontend production builds green. The Agent forced build requires an explicit reviewed Android NDK path. All RC Plus, visible-960 three-engine, soak, real-laser, no-prop, and controlled-flight gates remain BLOCKED_PENDING_DEVICE; release status is NOT_READY_DEFAULT_OFF.
Task 15: final tracked evidence added under docs/evidence from clean baseline b77f0ee; the rerun preserved a transient Maven dependency-resolution failure and the successful 514/514 retry as separate hashed logs. Automated status PASS; device/release status remains NOT_READY_DEFAULT_OFF.
Task 15: review requested changes (Critical 0 / Important 3 / Minor 0): common ai-service launch commands, Agent-side legacy ROI polling, and production RUNBOOK reviewed-NDK input were not fully guarded.
Task 15: fix round 1/5 implementation complete (3 addressed, 0 known open — compose/service-manager/launcher command guards with prose-safe fixtures, backend+Agent src/main ROI guard, reviewed NDK preflight/property; 16 fixture scenarios and active checkout PASS). Product logic and prior full-stack evidence were unchanged; device/release status remains NOT_READY_DEFAULT_OFF.
