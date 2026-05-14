# Agora Dynamic Token Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the backend's hard-coded Agora RTC token with a dynamically generated short-lived token while keeping the frontend API unchanged.

**Architecture:** Keep `/manage/api/v1/live/agora/config` as the single frontend entry point, but change `LiveStreamServiceImpl` to generate tokens on demand using the Agora authentication dependency and new backend config fields.

**Tech Stack:** Spring Boot, Maven, JUnit 5, Agora `io.agora:authentication`, existing livestream DTO/service layer.

---

### Task 1: Lock desired behavior with failing backend tests

**Files:**
- Create: `backend/sample/src/test/java/com/dji/sample/manage/service/impl/LiveStreamServiceImplAgoraConfigTest.java`
- Modify: none
- Test: `backend/sample/src/test/java/com/dji/sample/manage/service/impl/LiveStreamServiceImplAgoraConfigTest.java`

- [ ] **Step 1: Write the failing test**

Write a focused unit test that injects Agora config into `LiveStreamServiceImpl`, calls `getAgoraFrontendConfig()`, and asserts:
- `appid` is preserved
- `channel` is preserved
- `token` is non-empty and not equal to a placeholder static token

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl backend/sample -Dtest=LiveStreamServiceImplAgoraConfigTest test`

Expected: FAIL because the service still returns the static configured token and has no dynamic generation logic.

- [ ] **Step 3: Implement the minimal code to make the test pass**

Add config support and token generation in the service.

- [ ] **Step 4: Re-run the test to verify it passes**

Run: `mvn -pl backend/sample -Dtest=LiveStreamServiceImplAgoraConfigTest test`

Expected: PASS

### Task 2: Add dependency and backend generation path

**Files:**
- Modify: `backend/sample/pom.xml`
- Modify: `backend/sample/src/main/java/com/dji/sample/manage/model/dto/LiveStreamProperty.java`
- Modify: `backend/sample/src/main/java/com/dji/sample/manage/service/impl/LiveStreamServiceImpl.java`
- Modify: `backend/sample/src/main/resources/application.yml`

- [ ] **Step 1: Add Agora authentication dependency**

Add official Maven dependency for token generation.

- [ ] **Step 2: Extend config binding**

Bind `app-certificate` and `token-expire-seconds` under `livestream.url.agora`.

- [ ] **Step 3: Generate token dynamically**

Replace direct `agora.getToken()` return with a per-request RTC token build using app id, certificate, channel, uid `0`, subscriber role, and expiry seconds.

- [ ] **Step 4: Keep DTO contract unchanged**

Continue returning `appid`, `channel`, and generated `token`.

### Task 3: Verify backend behavior end-to-end

**Files:**
- Modify: none
- Test: `backend/sample/src/test/java/com/dji/sample/manage/service/impl/LiveStreamServiceImplAgoraConfigTest.java`

- [ ] **Step 1: Run targeted test**

Run: `mvn -pl sample -Dtest=LiveStreamServiceImplAgoraConfigTest test`

Expected: PASS

- [ ] **Step 2: Run existing nearby test**

Run: `mvn -pl sample -Dtest=CloudControlAuthStateResolverTest test`

Expected: PASS

- [ ] **Step 3: Compile backend**

Run: `mvn -pl sample -DskipTests compile`

Expected: PASS
