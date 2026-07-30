# RC Plus Agent Android Launcher Icon Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Generate and install a three-dimensional, technology-focused Android launcher icon that presents UAV monitoring first and a detected fire target second.

**Architecture:** Generate one square master artwork with all critical content inside the Android adaptive-icon safe area. Derive density-specific legacy and adaptive PNG resources from that master, connect them through adaptive-icon XML and explicit Manifest attributes, and protect the contract with a JVM resource-policy test plus an Android resource build.

**Tech Stack:** Built-in OpenAI image generation, ImageMagick 7, Android resource system, Kotlin/JUnit 4, Gradle

## Global Constraints

- The primary visual hierarchy is UAV monitoring; the fire is the monitored target.
- The style is three-dimensional and technological, using a charcoal-to-dark-red background, warm scan light, and restrained cool-blue rim light.
- Do not include text, watermarks, people, realistic scenery, third-party trademarks, DJI branding, firefighting axes, or shields.
- Keep the UAV, sensor, scan cone, targeting rings, and fire target within the Android adaptive-icon safe area.
- Support circular, rounded-square, and square launcher masks.
- Do not modify unrelated existing worktree changes.

---

### Task 1: Generate and approve the master artwork

**Files:**
- Create: `rcplus-msdk-agent/design/app-icon/uav-fire-monitor-master.png`

**Interfaces:**
- Consumes: the approved visual design in `docs/superpowers/specs/2026-07-28-android-launcher-icon-design.md`
- Produces: a square RGB or RGBA PNG master at least 1024 × 1024 pixels, with the complete subject contained in the central 66 percent safe region

- [ ] **Step 1: Generate the master with the built-in image generator**

Use this exact prompt:

```text
Use case: logo-brand
Asset type: Android adaptive launcher icon master artwork
Primary request: Create a premium three-dimensional technology icon for a UAV fire-monitoring application. A compact dark-metal quadcopter is the unmistakable primary subject, viewed from a slightly elevated three-quarter angle. Its belly-mounted thermal/optical gimbal actively scans downward and locks onto one small stylized fire hotspot.
Scene/backdrop: square charcoal-black to deep-crimson background with subtle depth, no real landscape
Composition/framing: centered, bold silhouette, drone and all rotors fully visible; gimbal, warm red-orange scan cone, concentric thermal targeting rings, reticle, and small fire hotspot all inside the central 66 percent safe region; generous outer padding for circular and rounded-square Android masks
Lighting/mood: dramatic controlled studio lighting, restrained cool-blue rim light on the drone, warm red-orange scan illumination, vigilant and high-tech
Color palette: charcoal, gunmetal, deep crimson, ember orange, small cool-blue highlights
Materials/textures: refined metal and glass sensor surfaces, clean app-icon finish, strong large-scale forms
Constraints: monitoring must read before firefighting; no text, no letters, no numbers, no watermark, no people, no buildings, no forest, no smoke cloud, no shield, no firefighting axe, no third-party logo, no DJI branding
Avoid: photorealistic scenery, tiny details, thin linework, clutter, elements touching the canvas edge
```

- [ ] **Step 2: Inspect the generated output**

Open the generated image and verify:

```text
PASS only if the quadcopter is the dominant first read; a belly sensor visibly emits or anchors the scan; one small fire target is visibly locked by rings or a reticle; every rotor and the complete monitoring motif remain within the central safe area; no prohibited text, logo, scenery, or emblem appears.
```

- [ ] **Step 3: Iterate once if a single defect is visible**

Issue one targeted edit that names only the failed invariant, for example:

```text
Keep the style, drone, lighting, palette, and square composition unchanged. Reduce the drone and monitoring motif by 12 percent and center them so all rotors, scan cone, targeting rings, and hotspot fit within the central Android adaptive-icon safe area. Add nothing else.
```

- [ ] **Step 4: Copy the selected built-in output into the repository**

Run:

```bash
mkdir -p rcplus-msdk-agent/design/app-icon
cp /absolute/path/from-generated_images/final.png rcplus-msdk-agent/design/app-icon/uav-fire-monitor-master.png
magick identify rcplus-msdk-agent/design/app-icon/uav-fire-monitor-master.png
```

Expected: one square PNG with both dimensions at least `1024`.

- [ ] **Step 5: Commit the approved master**

```bash
git add rcplus-msdk-agent/design/app-icon/uav-fire-monitor-master.png
git commit -m "design: add UAV fire monitor app icon artwork"
```

### Task 2: Add an executable launcher-icon resource contract

**Files:**
- Create: `rcplus-msdk-agent/app/src/test/java/com/yinxin/uavfir/LauncherIconResourcePolicyTest.kt`
- Test: `rcplus-msdk-agent/app/src/test/java/com/yinxin/uavfir/LauncherIconResourcePolicyTest.kt`

**Interfaces:**
- Consumes: Android resource names `ic_launcher`, `ic_launcher_round`, `ic_launcher_foreground`, and `ic_launcher_background`
- Produces: a JVM policy test that validates Manifest references, adaptive XML wiring, density coverage, and exact PNG dimensions

- [ ] **Step 1: Write the failing policy test**

Create `LauncherIconResourcePolicyTest.kt`:

```kotlin
package com.yinxin.uavfir

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherIconResourcePolicyTest {
    private val main = File("src/main")

    @Test
    fun launcherIcon_hasManifestAdaptiveAndLegacyResources() {
        val manifest = File(main, "AndroidManifest.xml").readText()
        assertTrue(manifest.contains("""android:icon="@mipmap/ic_launcher""""))
        assertTrue(manifest.contains("""android:roundIcon="@mipmap/ic_launcher_round""""))

        listOf("ic_launcher.xml", "ic_launcher_round.xml").forEach { name ->
            val xml = File(main, "res/mipmap-anydpi-v26/$name").readText()
            assertTrue(xml.contains("""android:drawable="@color/ic_launcher_background""""))
            assertTrue(xml.contains("""android:drawable="@mipmap/ic_launcher_foreground""""))
        }

        val sizes = linkedMapOf(
            "mdpi" to 48,
            "hdpi" to 72,
            "xhdpi" to 96,
            "xxhdpi" to 144,
            "xxxhdpi" to 192,
        )
        sizes.forEach { (density, size) ->
            assertSquarePng("res/mipmap-$density/ic_launcher.png", size)
            assertSquarePng("res/mipmap-$density/ic_launcher_round.png", size)
            assertSquarePng("res/mipmap-$density/ic_launcher_foreground.png", size * 9 / 4)
        }
    }

    private fun assertSquarePng(relativePath: String, expectedSize: Int) {
        val image: BufferedImage = ImageIO.read(File(main, relativePath))
        assertEquals(relativePath, expectedSize, image.width)
        assertEquals(relativePath, expectedSize, image.height)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:

```bash
cd rcplus-msdk-agent
./gradlew :app:testDebugUnitTest --tests com.yinxin.uavfir.LauncherIconResourcePolicyTest
```

Expected: `FAIL` because `AndroidManifest.xml` does not reference `@mipmap/ic_launcher` and the icon resources do not exist.

- [ ] **Step 3: Commit the failing test**

```bash
git add rcplus-msdk-agent/app/src/test/java/com/yinxin/uavfir/LauncherIconResourcePolicyTest.kt
git commit -m "test(msdk-agent): define launcher icon resource contract"
```

### Task 3: Install adaptive and legacy Android resources

**Files:**
- Create: `rcplus-msdk-agent/app/src/main/res/values/ic_launcher_background.xml`
- Create: `rcplus-msdk-agent/app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`
- Create: `rcplus-msdk-agent/app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml`
- Create: `rcplus-msdk-agent/app/src/main/res/mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.png`
- Create: `rcplus-msdk-agent/app/src/main/res/mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher_round.png`
- Create: `rcplus-msdk-agent/app/src/main/res/mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher_foreground.png`
- Modify: `rcplus-msdk-agent/app/src/main/AndroidManifest.xml`
- Test: `rcplus-msdk-agent/app/src/test/java/com/yinxin/uavfir/LauncherIconResourcePolicyTest.kt`

**Interfaces:**
- Consumes: `rcplus-msdk-agent/design/app-icon/uav-fire-monitor-master.png`
- Produces: `@mipmap/ic_launcher`, `@mipmap/ic_launcher_round`, `@mipmap/ic_launcher_foreground`, and `@color/ic_launcher_background`

- [ ] **Step 1: Add the adaptive background color**

Create `values/ic_launcher_background.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="ic_launcher_background">#19070A</color>
</resources>
```

- [ ] **Step 2: Add both adaptive-icon entry points**

Create identical `mipmap-anydpi-v26/ic_launcher.xml` and `mipmap-anydpi-v26/ic_launcher_round.xml` files:

```xml
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
</adaptive-icon>
```

- [ ] **Step 3: Derive exact density PNGs with ImageMagick**

Run from the repository root:

```bash
master="rcplus-msdk-agent/design/app-icon/uav-fire-monitor-master.png"
res="rcplus-msdk-agent/app/src/main/res"
for spec in mdpi:48:108 hdpi:72:162 xhdpi:96:216 xxhdpi:144:324 xxxhdpi:192:432; do
  density="${spec%%:*}"
  remainder="${spec#*:}"
  legacy="${remainder%%:*}"
  foreground="${remainder##*:}"
  mkdir -p "$res/mipmap-$density"
  magick "$master" -resize "${legacy}x${legacy}!" -strip "$res/mipmap-$density/ic_launcher.png"
  cp "$res/mipmap-$density/ic_launcher.png" "$res/mipmap-$density/ic_launcher_round.png"
  magick "$master" -resize "${foreground}x${foreground}!" -strip "$res/mipmap-$density/ic_launcher_foreground.png"
done
```

Expected dimensions:

```text
mdpi: legacy 48, foreground 108
hdpi: legacy 72, foreground 162
xhdpi: legacy 96, foreground 216
xxhdpi: legacy 144, foreground 324
xxxhdpi: legacy 192, foreground 432
```

- [ ] **Step 4: Wire the Manifest to the new resources**

Add these attributes to the existing `<application>` element:

```xml
android:icon="@mipmap/ic_launcher"
android:roundIcon="@mipmap/ic_launcher_round"
```

- [ ] **Step 5: Run the policy test**

Run:

```bash
cd rcplus-msdk-agent
./gradlew :app:testDebugUnitTest --tests com.yinxin.uavfir.LauncherIconResourcePolicyTest
```

Expected: `BUILD SUCCESSFUL`, with `LauncherIconResourcePolicyTest` passing.

- [ ] **Step 6: Run Android resource processing**

Run:

```bash
cd rcplus-msdk-agent
./gradlew :app:processDebugResources
```

Expected: `BUILD SUCCESSFUL` with no missing or duplicate launcher resources.

- [ ] **Step 7: Inspect common mask crops**

Create a temporary preview sheet outside tracked resources:

```bash
mkdir -p /tmp/uavfire-icon-preview
magick rcplus-msdk-agent/design/app-icon/uav-fire-monitor-master.png \
  \( +clone -alpha set -background none -gravity center \
     -fill white -draw "circle 512,512 512,32" \) \
  -compose DstIn -composite /tmp/uavfire-icon-preview/circle.png
magick rcplus-msdk-agent/design/app-icon/uav-fire-monitor-master.png \
  /tmp/uavfire-icon-preview/circle.png +append /tmp/uavfire-icon-preview/masks.png
```

Open `/tmp/uavfire-icon-preview/masks.png`. Expected: the drone, all rotors, gimbal, scan cone, targeting rings, and fire target remain readable in both square and circular views.

- [ ] **Step 8: Commit the Android integration**

```bash
git add \
  rcplus-msdk-agent/app/src/main/AndroidManifest.xml \
  rcplus-msdk-agent/app/src/main/res/values/ic_launcher_background.xml \
  rcplus-msdk-agent/app/src/main/res/mipmap-anydpi-v26 \
  rcplus-msdk-agent/app/src/main/res/mipmap-mdpi \
  rcplus-msdk-agent/app/src/main/res/mipmap-hdpi \
  rcplus-msdk-agent/app/src/main/res/mipmap-xhdpi \
  rcplus-msdk-agent/app/src/main/res/mipmap-xxhdpi \
  rcplus-msdk-agent/app/src/main/res/mipmap-xxxhdpi
git commit -m "feat(msdk-agent): add UAV fire monitoring launcher icon"
```

### Task 4: Final verification

**Files:**
- Verify: `rcplus-msdk-agent/design/app-icon/uav-fire-monitor-master.png`
- Verify: `rcplus-msdk-agent/app/src/main/AndroidManifest.xml`
- Verify: `rcplus-msdk-agent/app/src/main/res/mipmap-*`

**Interfaces:**
- Consumes: all artifacts from Tasks 1–3
- Produces: evidence that the generated visual and Android resource graph satisfy the approved design

- [ ] **Step 1: Run the focused unit test and resource build together**

```bash
cd rcplus-msdk-agent
./gradlew \
  :app:testDebugUnitTest \
  :app:processDebugResources
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Verify PNG formats and dimensions**

```bash
magick identify \
  rcplus-msdk-agent/design/app-icon/uav-fire-monitor-master.png \
  rcplus-msdk-agent/app/src/main/res/mipmap-*/ic_launcher*.png
```

Expected: the master is square and at least 1024 × 1024; all derived PNGs match the Task 3 dimension table.

- [ ] **Step 3: Review only task-owned changes**

```bash
git status --short
git log -4 --oneline
```

Expected: launcher-icon commits are present; unrelated pre-existing modified and untracked files remain untouched.
