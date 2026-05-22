# MSDK Sample Tools Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add DJI MSDK V5 sample Aircraft Testing Tools pages to `rcplus-msdk-agent` behind an independent tools entry.

**Architecture:** Preserve existing `MainActivity` as UAVFire Agent console and add a separate `SampleToolsActivity` hosting copied DJI sample navigation/fragments/resources. Use minimal package/import adaptation and compile-driven batching to keep broad sample functionality intact while isolating it from Agent runtime behavior.

**Tech Stack:** Android Kotlin, DJI MSDK V5, AndroidX AppCompat, AndroidX Navigation Fragment/UI, RecyclerView, Lifecycle ViewModel/LiveData, DataBinding.

---

## File Structure

- Modify `rcplus-msdk-agent/app/build.gradle.kts`: enable dataBinding and add Navigation/Fragment/RecyclerView dependencies.
- Modify `rcplus-msdk-agent/app/src/main/AndroidManifest.xml`: register `SampleToolsActivity`.
- Modify `rcplus-msdk-agent/app/src/main/java/com/yinxin/uavfir/MainActivity.kt`: add button wiring to open Sample Tools.
- Modify `rcplus-msdk-agent/app/src/main/res/layout/activity_main.xml`: add Sample Tools button.
- Create `rcplus-msdk-agent/app/src/main/java/com/yinxin/uavfir/sample/SampleToolsActivity.kt`: sample navigation host activity.
- Create/copy sample sources under `rcplus-msdk-agent/app/src/main/java/dji/sampleV5/...`: DJI sample pages/models/utils/data/view code.
- Copy sample resources under `rcplus-msdk-agent/app/src/main/res/...`: layouts, navigation graphs, drawables, values needed by sample pages.
- Update copied sample resources/source package compatibility only as required to compile inside this app.

## Task 1: Isolated branch/worktree

- [ ] Create a worktree branch named `feature/msdk-sample-tools` outside the dirty main worktree.
- [ ] Copy current working-tree project state into the worktree so existing uncommitted UAVFire Agent changes are preserved for this feature branch.
- [ ] Confirm `git status --short --branch` in the feature worktree.

## Task 2: Add Sample Tools shell

- [ ] Enable Android build features and dependencies in `rcplus-msdk-agent/app/build.gradle.kts`:
  - `dataBinding = true`
  - Navigation Fragment/UI KTX
  - Fragment KTX
  - RecyclerView
  - Lifecycle ViewModel/LiveData KTX if required by copied sample pages
- [ ] Create `SampleToolsActivity` with a layout containing `FragmentContainerView`/NavHostFragment.
- [ ] Register activity in manifest.
- [ ] Add an `openSampleToolsButton` to `activity_main.xml`.
- [ ] Wire `MainActivity` to launch `SampleToolsActivity`.
- [ ] Run `./gradlew :app:compileDebugKotlin` from `rcplus-msdk-agent`; expected first result may fail because sample graph/pages are not copied yet.

## Task 3: Copy sample menu/navigation foundation

- [ ] Copy sample navigation XMLs needed for Aircraft Testing Tools.
- [ ] Copy `MainFragment`, `MainFragmentListAdapter`, `FragmentPageItem`, `FragmentPageItemList`, and menu source data/ViewModel classes.
- [ ] Copy base classes required by sample fragments: `DJIFragment`, `DJIViewModel`, MSDK info ViewModels, title/header layouts.
- [ ] Copy required resources referenced by menu/base pages.
- [ ] Run `./gradlew :app:compileDebugKotlin` and collect missing classes/resources.

## Task 4: Batch copy Aircraft page implementations

- [ ] Copy all classes under `dji/sampleV5/aircraft/pages` referenced by `nav_aircraft.xml`.
- [ ] Copy corresponding ViewModels under `dji/sampleV5/aircraft/models`.
- [ ] Copy utility classes under `dji/sampleV5/aircraft/util`, `utils`, `keyvalue`, `virtualstick`, and related packages referenced by copied pages.
- [ ] Copy layouts/drawables/values referenced by these pages.
- [ ] Run compile and patch unresolved imports/resources.

## Task 5: Patch package/resource conflicts and app integration issues

- [ ] Fix duplicate resource names or missing style/theme references with minimal local edits.
- [ ] Fix manifest/activity/package references so copied sample code resolves inside this app.
- [ ] Keep existing app namespace `com.yinxin.uavfir` unchanged.
- [ ] Avoid changing existing Agent service APIs unless compilation requires import-only fixes.
- [ ] Run compile after each focused patch.

## Task 6: Verification and handoff notes

- [ ] Run `./gradlew :app:compileDebugKotlin`.
- [ ] Run `./gradlew :app:testDebugUnitTest` if compilation succeeds.
- [ ] Write `docs/MSDK_SAMPLE_TOOLS_MIGRATION_NOTES.md` with:
  - branch/worktree path
  - what pages are visible
  - compile/test result
  - known unsupported or unverified hardware-dependent areas
  - morning real-device checklist
- [ ] Commit logically if the worktree reaches a compiling state.
