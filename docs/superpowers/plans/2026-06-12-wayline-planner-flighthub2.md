# 航线规划页司空2风格改造 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把航线规划页改造为司空2 风格（卫星深色底图、三段式布局、信息常显航点、拖拽编辑、高度剖面、模拟预演），并落地真实 DEM 高程后端。

**Architecture:** 前端组件化重构——`wayline.vue` 变薄壳（页签「监测规划/投放任务」），新组件群放 `frontend/src/components/wayline-planner/`；数据与执行层（`use-wayline-planning.ts` store、`api/wayline.ts`）不动；GMap 覆盖物渲染抽到 `use-planner-overlays.ts`。后端新增 `.hgt` 高程服务（零新依赖）+ 批量查询接口，条件装配回落现有 `MissingTerrainElevationService`。

**Tech Stack:** Vue3 + antd-vue 2.2.8（弹窗用 `v-model:visible`）+ AMap JSAPI 2.0；Spring Boot (JDK11)；测试：node:test（`.test.mjs`）+ JUnit5。

**设计文档：** `docs/superpowers/specs/2026-06-12-wayline-planner-flighthub2-design.md`（v2，所有产品决策以它为准）

**关键背景（执行者必读）：**
- 后端启动：`cd backend && JAVA_HOME=/usr/local/opt/openjdk@11 mvn spring-boot:run -pl uavfire`（端口 6789）；后端测试：`cd backend && JAVA_HOME=/usr/local/opt/openjdk@11 mvn -pl uavfire test -Dtest=类名`
- 前端 dev：`cd frontend && npm run serve`（vite，:8080）；前端测试：`node frontend/scripts/<name>.test.mjs`
- 前端测试惯例：`node:test` + `assert/strict`；纯逻辑写成 `.mjs` 模块（Vue 文件 import 它），测试直接 import 执行（参考 `frontend/src/pages/page-web/projects/leadership-cockpit-live-layout.mjs` 与对应测试）；对 `.vue` 文件用正则断言源码（参考 `frontend/scripts/fc100-delivery-ui.test.mjs`）
- 坐标：地图/距离计算用 GCJ02（`gcjLng/gcjLat`），DJI 与 DEM 用 WGS84（`wgsLng/wgsLat`），转换用 `/@/vendors/coordtransform` 的 `gcj02towgs84/wgs84togcj02`
- `wayline.vue` 现状：template L1-948（顶栏 L4-31；M4T 航线列表面板 L42-103，其中 L75-83 任务监控；FC100 面板 L104-255；投放列表面板 L256-409；弹窗 L410-555；M4T 规划编辑区+高级配置 L556-948）；script L950-2715；style L2717-3593
- `GMap.vue` 现状：卫星图层已存在（L28 卫星按钮、L877-905 `ensureSatelliteLayer`/`setWaylineMapLayer`）；规划覆盖物 L756-1040（`renderPlanningWaypoints` :758、`rebuildPlanningOverlays` :817、`updateFlightPositionOverlay` :946）
- 每个 Task 完成即 commit；commit message 末尾加 `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`

---

## Part A：DEM 高程后端（独立可交付，先做——前端剖面依赖它）

### Task 1: HgtTerrainElevationService（.hgt 解析 + 双线性插值）

**Files:**
- Create: `backend/uavfire/src/main/java/com/yx/uavfire/fc100/event/service/impl/HgtTerrainElevationService.java`
- Test: `backend/uavfire/src/test/java/com/yx/uavfire/fc100/event/service/impl/HgtTerrainElevationServiceTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.yx.uavfire.fc100.event.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.BufferedOutputStream;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class HgtTerrainElevationServiceTest {

    private static final int SIZE = 3601;

    @TempDir
    Path demDir;

    private HgtTerrainElevationService service;

    /**
     * 合成 N39E115.hgt：全格网填 100，除了 (row=1800,col=1800) 起的 2x2 块
     * 填 [100, 200; 300, 400] 用于双线性插值验证。
     * row=1800 对应 lat = 40 - 1800/3600 = 39.5；col=1800 对应 lng = 115.5。
     */
    @BeforeEach
    void writeSyntheticTile() throws IOException {
        Path file = demDir.resolve("N39E115.hgt");
        try (DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(new FileOutputStream(file.toFile()), 1 << 20))) {
            for (int row = 0; row < SIZE; row++) {
                for (int col = 0; col < SIZE; col++) {
                    short v = 100;
                    if (row == 1800 && col == 1801) v = 200;
                    if (row == 1801 && col == 1800) v = 300;
                    if (row == 1801 && col == 1801) v = 400;
                    if (row == 100 && col == 100) v = -32768; // 无效值格点
                    out.writeShort(v);
                }
            }
        }
        service = new HgtTerrainElevationService(demDir.toString());
    }

    @Test
    void exactGridPointReturnsCellValue() {
        // lat=39.5, lng=115.5 正好落在 (1800,1800)，值 100
        Optional<Double> elev = service.queryElevation(39.5, 115.5);
        assertTrue(elev.isPresent());
        assertEquals(100.0, elev.get(), 0.01);
    }

    @Test
    void bilinearInterpolatesBetweenFourCells() {
        // (1800,1800)=100 (1800,1801)=200 (1801,1800)=300 (1801,1801)=400
        // 取四格中心：row=1800.5, col=1800.5 → lat=40-1800.5/3600, lng=115+1800.5/3600
        double lat = 40.0 - 1800.5 / 3600.0;
        double lng = 115.0 + 1800.5 / 3600.0;
        Optional<Double> elev = service.queryElevation(lat, lng);
        assertTrue(elev.isPresent());
        assertEquals(250.0, elev.get(), 0.01); // (100+200+300+400)/4
    }

    @Test
    void invalidCellReturnsEmpty() {
        // (100,100) 是 -32768：lat=40-100/3600, lng=115+100/3600
        Optional<Double> elev = service.queryElevation(40.0 - 100.0 / 3600.0, 115.0 + 100.0 / 3600.0);
        assertTrue(elev.isEmpty());
    }

    @Test
    void missingTileReturnsEmpty() {
        assertTrue(service.queryElevation(38.5, 115.5).isEmpty()); // N38 瓦片不存在
    }

    @Test
    void tileEdgeDoesNotThrow() {
        // 瓦片北边界 lat=40.0（row=0）与东边界附近，验证 clamp 不越界
        assertDoesNotThrow(() -> service.queryElevation(40.0, 115.0));
        assertDoesNotThrow(() -> service.queryElevation(39.0000001, 115.9999999));
    }

    @Test
    void southernAndWesternHemisphereTileName() {
        assertEquals("S05W070.hgt", HgtTerrainElevationService.tileName(-5, -70));
        assertEquals("N39E115.hgt", HgtTerrainElevationService.tileName(39, 115));
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `cd backend && JAVA_HOME=/usr/local/opt/openjdk@11 mvn -pl uavfire test -Dtest=HgtTerrainElevationServiceTest`
Expected: 编译失败 `cannot find symbol: class HgtTerrainElevationService`

- [ ] **Step 3: 实现**

```java
package com.yx.uavfire.fc100.event.service.impl;

import com.yx.uavfire.fc100.event.service.TerrainElevationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * SRTM .hgt 格式 DEM 高程服务（数据由 scripts/fetch-dem.sh 从 Copernicus GLO-30 转换而来，
 * 注意 GLO-30 是 DSM——含树冠/建筑高度）。
 * 格式：3601x3601 int16 大端，行序北→南，无文件头，无效值 -32768，文件名 NXXEYYY.hgt。
 */
@Service
@Primary
@ConditionalOnProperty(prefix = "uavfire.terrain", name = "dem-dir")
public class HgtTerrainElevationService implements TerrainElevationService {

    private static final int SIZE = 3601;
    private static final short INVALID = -32768;
    private static final long EXPECTED_BYTES = (long) SIZE * SIZE * 2;
    private static final int MAX_CACHED_TILES = 8;

    private final Path demDir;
    /** LRU：瓦片名 -> 网格（mmap）。Optional.empty 表示已确认缺失，避免重复探测。 */
    private final Map<String, Optional<ShortBuffer>> tiles = Collections.synchronizedMap(
            new LinkedHashMap<String, Optional<ShortBuffer>>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Optional<ShortBuffer>> eldest) {
                    return size() > MAX_CACHED_TILES;
                }
            });

    public HgtTerrainElevationService(@Value("${uavfire.terrain.dem-dir}") String demDir) {
        this.demDir = Paths.get(demDir);
    }

    @Override
    public Optional<Double> queryElevation(double lat, double lng) {
        if (lat < -90 || lat > 90 || lng < -180 || lng > 180) {
            return Optional.empty();
        }
        int latBase = (int) Math.floor(lat);
        int lngBase = (int) Math.floor(lng);
        Optional<ShortBuffer> tile = loadTile(latBase, lngBase);
        if (tile.isEmpty()) {
            return Optional.empty();
        }
        ShortBuffer grid = tile.get();
        double row = (latBase + 1 - lat) * 3600.0; // 北边界是 row 0
        double col = (lng - lngBase) * 3600.0;
        int r0 = Math.min((int) Math.floor(row), SIZE - 2);
        int c0 = Math.min((int) Math.floor(col), SIZE - 2);
        double fr = row - r0;
        double fc = col - c0;
        short v00 = grid.get(r0 * SIZE + c0);
        short v01 = grid.get(r0 * SIZE + c0 + 1);
        short v10 = grid.get((r0 + 1) * SIZE + c0);
        short v11 = grid.get((r0 + 1) * SIZE + c0 + 1);
        if (v00 == INVALID || v01 == INVALID || v10 == INVALID || v11 == INVALID) {
            return Optional.empty();
        }
        double top = v00 * (1 - fc) + v01 * fc;
        double bottom = v10 * (1 - fc) + v11 * fc;
        return Optional.of(top * (1 - fr) + bottom * fr);
    }

    private Optional<ShortBuffer> loadTile(int latBase, int lngBase) {
        String name = tileName(latBase, lngBase);
        return tiles.computeIfAbsent(name, this::mapTile);
    }

    private Optional<ShortBuffer> mapTile(String name) {
        Path file = demDir.resolve(name);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r");
             FileChannel channel = raf.getChannel()) {
            if (channel.size() != EXPECTED_BYTES) {
                return Optional.empty();
            }
            ByteBuffer mapped = channel.map(FileChannel.MapMode.READ_ONLY, 0, EXPECTED_BYTES);
            mapped.order(ByteOrder.BIG_ENDIAN);
            return Optional.of(mapped.asShortBuffer());
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    static String tileName(int latBase, int lngBase) {
        return String.format("%s%02d%s%03d.hgt",
                latBase >= 0 ? "N" : "S", Math.abs(latBase),
                lngBase >= 0 ? "E" : "W", Math.abs(lngBase));
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `cd backend && JAVA_HOME=/usr/local/opt/openjdk@11 mvn -pl uavfire test -Dtest=HgtTerrainElevationServiceTest`
Expected: `Tests run: 6, Failures: 0, Errors: 0` BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add backend/uavfire/src/main/java/com/yx/uavfire/fc100/event/service/impl/HgtTerrainElevationService.java backend/uavfire/src/test/java/com/yx/uavfire/fc100/event/service/impl/HgtTerrainElevationServiceTest.java
git commit -m "feat(terrain): .hgt DEM 高程服务（双线性插值+瓦片LRU）"
```

### Task 2: 配置与条件装配验证

**Files:**
- Modify: `backend/uavfire/src/main/resources/application.yml`（文件末尾追加）
- Test: `backend/uavfire/src/test/java/com/yx/uavfire/fc100/event/service/impl/TerrainElevationWiringTest.java`

- [ ] **Step 1: 写失败测试（验证条件装配两个方向）**

```java
package com.yx.uavfire.fc100.event.service.impl;

import com.yx.uavfire.fc100.event.service.TerrainElevationService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class TerrainElevationWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(HgtTerrainElevationService.class, MissingTerrainElevationService.class);

    @Test
    void demDirConfiguredUsesHgtServiceAsPrimary() {
        runner.withPropertyValues("uavfire.terrain.dem-dir=/tmp/nonexistent-dem")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(HgtTerrainElevationService.class);
                    assertThat(ctx.getBean(TerrainElevationService.class))
                            .isInstanceOf(HgtTerrainElevationService.class);
                });
    }

    @Test
    void demDirAbsentFallsBackToMissingService() {
        runner.run(ctx -> {
            assertThat(ctx).doesNotHaveBean(HgtTerrainElevationService.class);
            assertThat(ctx.getBean(TerrainElevationService.class))
                    .isInstanceOf(MissingTerrainElevationService.class);
        });
    }
}
```

- [ ] **Step 2: 运行**

Run: `cd backend && JAVA_HOME=/usr/local/opt/openjdk@11 mvn -pl uavfire test -Dtest=TerrainElevationWiringTest`
Expected: PASS（Task 1 的注解已满足条件装配；若 `assertj`/`spring-boot-test` 缺依赖则按报错补——项目已有 Spring Boot 测试，正常应直接绿）

- [ ] **Step 3: application.yml 加注释化配置（默认不启用，保持现有行为）**

在 `backend/uavfire/src/main/resources/application.yml` 末尾追加：

```yaml
# DEM 地形高程（Copernicus GLO-30 转 .hgt，见 scripts/fetch-dem.sh）。
# 配置 dem-dir 后 HgtTerrainElevationService 生效；不配置则回落 MissingTerrainElevationService。
uavfire:
  terrain:
    dem-dir: ${UAVFIRE_DEM_DIR:data/dem}
```

注意：若 `application.yml` 已有顶层 `uavfire:` 键则合并进去（先 `grep -n "^uavfire:" backend/uavfire/src/main/resources/application.yml` 检查）。`data/dem` 是相对后端工作目录的默认路径，目录不存在时服务返回 empty，不影响启动。

- [ ] **Step 4: 启动冒烟（确认不破坏现有启动）**

Run: `cd backend && JAVA_HOME=/usr/local/opt/openjdk@11 timeout 90 mvn -q spring-boot:run -pl uavfire 2>&1 | head -50`（或直接观察已运行的 tmux 会话重启）
Expected: 正常启动无 Bean 冲突报错（看到 Tomcat started on port 6789 即成功，Ctrl-C/timeout 退出）

- [ ] **Step 5: Commit**

```bash
git add backend/uavfire/src/main/resources/application.yml backend/uavfire/src/test/java/com/yx/uavfire/fc100/event/service/impl/TerrainElevationWiringTest.java
git commit -m "feat(terrain): dem-dir 条件装配配置"
```

### Task 3: 批量高程查询接口

**Files:**
- Create: `backend/uavfire/src/main/java/com/yx/uavfire/fc100/event/controller/TerrainController.java`
- Test: `backend/uavfire/src/test/java/com/yx/uavfire/fc100/event/controller/TerrainControllerTest.java`

- [ ] **Step 1: 写失败测试（纯单元测试，stub 函数式接口）**

```java
package com.yx.uavfire.fc100.event.controller;

import com.dji.sdk.common.HttpResultResponse;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class TerrainControllerTest {

    private static Map<String, Double> pt(Double lat, Double lng) {
        Map<String, Double> m = new HashMap<>();
        if (lat != null) m.put("lat", lat);
        if (lng != null) m.put("lng", lng);
        return m;
    }

    @Test
    void returnsElevationsInOrderWithNullForMissing() {
        TerrainController c = new TerrainController((lat, lng) ->
                lat > 39 ? Optional.of(123.5) : Optional.empty());
        List<Map<String, Double>> body = new ArrayList<>();
        body.add(pt(39.5, 115.5));
        body.add(pt(38.5, 115.5));
        HttpResultResponse resp = c.elevations(body);
        assertEquals(HttpResultResponse.CODE_SUCCESS, resp.getCode());
        @SuppressWarnings("unchecked")
        List<Double> data = (List<Double>) resp.getData();
        assertEquals(123.5, data.get(0), 0.001);
        assertNull(data.get(1));
    }

    @Test
    void rejectsEmptyAndOversizedAndMalformed() {
        TerrainController c = new TerrainController((lat, lng) -> Optional.of(1.0));
        assertNotEquals(HttpResultResponse.CODE_SUCCESS, c.elevations(null).getCode());
        assertNotEquals(HttpResultResponse.CODE_SUCCESS, c.elevations(new ArrayList<>()).getCode());
        List<Map<String, Double>> tooMany = new ArrayList<>();
        for (int i = 0; i < 501; i++) tooMany.add(pt(39.5, 115.5));
        assertNotEquals(HttpResultResponse.CODE_SUCCESS, c.elevations(tooMany).getCode());
        List<Map<String, Double>> missingLng = new ArrayList<>();
        missingLng.add(pt(39.5, null));
        assertNotEquals(HttpResultResponse.CODE_SUCCESS, c.elevations(missingLng).getCode());
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `cd backend && JAVA_HOME=/usr/local/opt/openjdk@11 mvn -pl uavfire test -Dtest=TerrainControllerTest`
Expected: 编译失败 `cannot find symbol: class TerrainController`

- [ ] **Step 3: 实现（路径前缀与鉴权跟随现有 manage 控制器，参考 `FireDetectionController`）**

```java
package com.yx.uavfire.fc100.event.controller;

import com.dji.sdk.common.HttpResultResponse;
import com.yx.uavfire.fc100.event.service.TerrainElevationService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 批量地形高程查询（WGS84），供前端高度剖面图使用。走 manage 前缀（JWT 鉴权链路）。 */
@RestController
@RequestMapping("${url.manage.prefix}${url.manage.version}/terrain")
public class TerrainController {

    private static final int MAX_POINTS = 500;

    private final TerrainElevationService terrainElevationService;

    public TerrainController(TerrainElevationService terrainElevationService) {
        this.terrainElevationService = terrainElevationService;
    }

    @PostMapping("/elevations")
    public HttpResultResponse elevations(@RequestBody List<Map<String, Double>> points) {
        if (points == null || points.isEmpty()) {
            return HttpResultResponse.error("points required");
        }
        if (points.size() > MAX_POINTS) {
            return HttpResultResponse.error("max " + MAX_POINTS + " points");
        }
        List<Double> elevations = new ArrayList<>(points.size());
        for (Map<String, Double> p : points) {
            Double lat = p.get("lat");
            Double lng = p.get("lng");
            if (lat == null || lng == null) {
                return HttpResultResponse.error("lat/lng required for every point");
            }
            elevations.add(terrainElevationService.queryElevation(lat, lng).orElse(null));
        }
        return HttpResultResponse.success(elevations);
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `cd backend && JAVA_HOME=/usr/local/opt/openjdk@11 mvn -pl uavfire test -Dtest=TerrainControllerTest`
Expected: `Tests run: 2, Failures: 0` BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add backend/uavfire/src/main/java/com/yx/uavfire/fc100/event/controller/TerrainController.java backend/uavfire/src/test/java/com/yx/uavfire/fc100/event/controller/TerrainControllerTest.java
git commit -m "feat(terrain): POST /terrain/elevations 批量高程接口"
```

### Task 4: fetch-dem.sh 数据准备脚本

**Files:**
- Create: `scripts/fetch-dem.sh`（chmod +x）

- [ ] **Step 1: 写脚本**

```bash
#!/usr/bin/env bash
# 下载 Copernicus GLO-30 DEM 并转为 SRTM .hgt（供 HgtTerrainElevationService 使用）。
# 用法: scripts/fetch-dem.sh <west> <south> <east> <north> [outdir]
# 例:   scripts/fetch-dem.sh 115 39 117 41 backend/data/dem
# 注意: GLO-30 是 DSM（含树冠）。瓦片为 3600x3600 pixel-is-area，必须 gdalwarp
#       重采样到 3601x3601 pixel-is-point，gdal_translate 直转会被 SRTMHGT 驱动拒绝。
set -euo pipefail

if [ $# -lt 4 ]; then
  echo "用法: $0 <west> <south> <east> <north> [outdir]" >&2
  exit 1
fi
WEST=$1; SOUTH=$2; EAST=$3; NORTH=$4
OUTDIR=${5:-backend/data/dem}

command -v gdalwarp >/dev/null 2>&1 || { echo "需要 gdal: brew install gdal" >&2; exit 1; }
mkdir -p "$OUTDIR"

for ((lat=SOUTH; lat<NORTH; lat++)); do
  for ((lon=WEST; lon<EAST; lon++)); do
    if [ "$lat" -ge 0 ]; then ns=N; alat=$lat; else ns=S; alat=$((-lat)); fi
    if [ "$lon" -ge 0 ]; then we=E; alon=$lon; else we=W; alon=$((-lon)); fi
    tile=$(printf 'Copernicus_DSM_COG_10_%s%02d_00_%s%03d_00_DEM' "$ns" "$alat" "$we" "$alon")
    hgt=$(printf '%s%02d%s%03d.hgt' "$ns" "$alat" "$we" "$alon")
    if [ -f "$OUTDIR/$hgt" ]; then echo "跳过(已存在) $hgt"; continue; fi
    tmp=$(mktemp -d)
    url="https://copernicus-dem-30m.s3.amazonaws.com/${tile}/${tile}.tif"
    if ! curl -fSs -o "$tmp/in.tif" "$url"; then
      echo "瓦片不存在(海洋?) $tile" >&2; rm -rf "$tmp"; continue
    fi
    gdalwarp -q -overwrite -ts 3601 3601 -te "$lon" "$lat" $((lon+1)) $((lat+1)) \
      -r bilinear "$tmp/in.tif" "$OUTDIR/$hgt"
    rm -rf "$tmp"
    echo "完成 $hgt"
  done
done
echo "输出目录: $OUTDIR （后端配置 uavfire.terrain.dem-dir 或环境变量 UAVFIRE_DEM_DIR 指向它）"
```

- [ ] **Step 2: 手动验证（任选一个 1° 瓦片，需要本机有 gdal；没有 gdal 就只验证语法）**

Run: `bash -n scripts/fetch-dem.sh && chmod +x scripts/fetch-dem.sh && echo OK`
Expected: `OK`
（可选完整验证，约下载 80MB：`scripts/fetch-dem.sh 115 39 116 40 /tmp/dem-test && ls -l /tmp/dem-test/N39E115.hgt` → 文件大小恰为 25934402 字节）

- [ ] **Step 3: Commit**

```bash
git add scripts/fetch-dem.sh
git commit -m "feat(terrain): GLO-30 下载转换脚本 fetch-dem.sh"
```

---

## Part B：前端纯逻辑基础（utils + UI 状态）

### Task 5: planner-utils.mjs（统计/采样/预演时间轴纯函数）

**Files:**
- Create: `frontend/src/components/wayline-planner/planner-utils.mjs`
- Test: `frontend/scripts/planner-utils.test.mjs`

- [ ] **Step 1: 写失败测试**

```js
import test from 'node:test'
import assert from 'node:assert/strict'
import {
  haversineMeters,
  computeRouteStats,
  sampleRoutePoints,
  buildSimulationTimeline,
  positionAtTime,
} from '../src/components/wayline-planner/planner-utils.mjs'

// 赤道上经度差 0.001° ≈ 111.32m
const P = (lng, lat, extra = {}) => ({ gcjLng: lng, gcjLat: lat, wgsLng: lng, wgsLat: lat, height: 30, ...extra })

test('haversineMeters: 赤道 0.001° 经度差约 111.3m', () => {
  const d = haversineMeters(0, 0, 0.001, 0)
  assert.ok(Math.abs(d - 111.32) < 0.5, `got ${d}`)
})

test('computeRouteStats: 距离/时长/航点数（速度覆写+悬停）', () => {
  const wps = [
    P(0, 0),
    P(0.001, 0, { speed: 2, actions: [{ actuatorFunc: 'hover', params: { hoverTime: 10 } }] }),
    P(0.002, 0),
  ]
  const s = computeRouteStats(wps, 5)
  assert.equal(s.count, 3)
  assert.ok(Math.abs(s.distanceM - 222.64) < 1, `distance ${s.distanceM}`)
  // 段1: 111.32/5=22.26s(到达wp2,wp2无覆写时段1用默认速度? 约定: 段速度=终点航点速度覆写||默认)
  // 段1 终点 wp2 speed=2 → 111.32/2=55.66s；段2 终点 wp3 无覆写 → 111.32/5=22.26s；悬停 10s
  assert.ok(Math.abs(s.durationS - (55.66 + 22.26 + 10)) < 1, `duration ${s.durationS}`)
})

test('computeRouteStats: 空/单航点', () => {
  assert.deepEqual(computeRouteStats([], 5), { distanceM: 0, durationS: 0, count: 0 })
  const s = computeRouteStats([P(0, 0)], 5)
  assert.equal(s.count, 1)
  assert.equal(s.distanceM, 0)
})

test('sampleRoutePoints: 步长自适应保证 ≤maxPoints，含两端航点', () => {
  // 22.26km 直线，minStep 30m → 742 点会超 500 → 自适应步长 ≥ 44.5m
  const wps = [P(0, 0), P(0.2, 0)]
  const samples = sampleRoutePoints(wps, { maxPoints: 500, minStepM: 30 })
  assert.ok(samples.length <= 500, `len ${samples.length}`)
  assert.ok(samples.length > 400)
  assert.equal(samples[0].distM, 0)
  const last = samples[samples.length - 1]
  assert.ok(Math.abs(last.wgsLng - 0.2) < 1e-9)
  // 单调递增里程
  for (let i = 1; i < samples.length; i++) assert.ok(samples[i].distM > samples[i - 1].distM)
})

test('sampleRoutePoints: 短航线用 minStep', () => {
  const wps = [P(0, 0), P(0.001, 0)] // 111m → 30m 步长 ≈ 5 点
  const samples = sampleRoutePoints(wps, { maxPoints: 500, minStepM: 30 })
  assert.ok(samples.length >= 4 && samples.length <= 6, `len ${samples.length}`)
})

test('buildSimulationTimeline + positionAtTime: 匀速插值与悬停停留', () => {
  const wps = [
    P(0, 0),
    P(0.001, 0, { speed: 2, actions: [{ actuatorFunc: 'hover', params: { hoverTime: 10 } }] }),
    P(0.002, 0),
  ]
  const tl = buildSimulationTimeline(wps, 5)
  // 总时长 = 55.66 + 10 + 22.26
  assert.ok(Math.abs(tl.totalS - 87.93) < 1, `total ${tl.totalS}`)
  // t=0 在起点
  let pos = positionAtTime(tl, 0)
  assert.ok(Math.abs(pos.gcjLng - 0) < 1e-9)
  // 段1 中点时刻 ≈ 27.83s → 应在 lng≈0.0005
  pos = positionAtTime(tl, 27.83)
  assert.ok(Math.abs(pos.gcjLng - 0.0005) < 1e-4, `lng ${pos.gcjLng}`)
  // 悬停期间停在 wp2
  pos = positionAtTime(tl, 60)
  assert.ok(Math.abs(pos.gcjLng - 0.001) < 1e-9)
  // 超出总时长停在终点
  pos = positionAtTime(tl, 9999)
  assert.ok(Math.abs(pos.gcjLng - 0.002) < 1e-9)
})
```

- [ ] **Step 2: 运行确认失败**

Run: `node frontend/scripts/planner-utils.test.mjs`
Expected: `ERR_MODULE_NOT_FOUND`（planner-utils.mjs 不存在）

- [ ] **Step 3: 实现**

```js
// 航线规划纯计算工具（.mjs：Vue 组件与 node 测试共用，禁止引浏览器/Vue 依赖）。
// 距离全部用球面 haversine；坐标用传入对象自带的字段（统计用 GCJ02，采样输出 WGS84 供 DEM 查询）。

const EARTH_RADIUS_M = 6378137.0

export function haversineMeters (lng1, lat1, lng2, lat2) {
  const rad = Math.PI / 180
  const dLat = (lat2 - lat1) * rad
  const dLng = (lng2 - lng1) * rad
  const a = Math.sin(dLat / 2) ** 2 +
    Math.cos(lat1 * rad) * Math.cos(lat2 * rad) * Math.sin(dLng / 2) ** 2
  return 2 * EARTH_RADIUS_M * Math.asin(Math.sqrt(a))
}

function hoverSeconds (wp) {
  if (!Array.isArray(wp.actions)) return 0
  return wp.actions.reduce((sum, a) => {
    if (a && a.actuatorFunc === 'hover') {
      const t = Number(a.params && a.params.hoverTime)
      return sum + (Number.isFinite(t) && t > 0 ? t : 0)
    }
    return sum
  }, 0)
}

/** 段速度约定：取该段终点航点的 speed 覆写，否则全局默认速度。 */
function segmentSpeed (toWp, defaultSpeed) {
  const v = Number(toWp && toWp.speed)
  return Number.isFinite(v) && v > 0 ? v : defaultSpeed
}

export function computeRouteStats (waypoints, defaultSpeed) {
  if (!Array.isArray(waypoints) || waypoints.length === 0) {
    return { distanceM: 0, durationS: 0, count: 0 }
  }
  let distanceM = 0
  let durationS = hoverSeconds(waypoints[0])
  for (let i = 1; i < waypoints.length; i++) {
    const a = waypoints[i - 1]
    const b = waypoints[i]
    const d = haversineMeters(a.gcjLng, a.gcjLat, b.gcjLng, b.gcjLat)
    distanceM += d
    durationS += d / segmentSpeed(b, defaultSpeed) + hoverSeconds(b)
  }
  return { distanceM, durationS, count: waypoints.length }
}

/**
 * 沿航线等步长采样（WGS84 输出，供 DEM 批量查询）。
 * 步长 = max(minStepM, 总长/( maxPoints-1 ))，保证返回 ≤ maxPoints；始终包含每个航点本身。
 */
export function sampleRoutePoints (waypoints, { maxPoints = 500, minStepM = 30 } = {}) {
  if (!Array.isArray(waypoints) || waypoints.length === 0) return []
  const segs = []
  let total = 0
  for (let i = 1; i < waypoints.length; i++) {
    const a = waypoints[i - 1]
    const b = waypoints[i]
    const d = haversineMeters(a.gcjLng, a.gcjLat, b.gcjLng, b.gcjLat)
    segs.push({ a, b, d })
    total += d
  }
  const step = Math.max(minStepM, total / Math.max(1, maxPoints - 1))
  const out = [{ wgsLng: waypoints[0].wgsLng, wgsLat: waypoints[0].wgsLat, distM: 0, waypointIndex: 0 }]
  let walked = 0
  segs.forEach((seg, segIdx) => {
    if (seg.d <= 0) return
    let offset = step - ((walked % step) || step)
    if (offset <= 0) offset = step
    for (let s = offset; s < seg.d; s += step) {
      const t = s / seg.d
      out.push({
        wgsLng: seg.a.wgsLng + (seg.b.wgsLng - seg.a.wgsLng) * t,
        wgsLat: seg.a.wgsLat + (seg.b.wgsLat - seg.a.wgsLat) * t,
        distM: walked + s,
        waypointIndex: -1,
      })
    }
    walked += seg.d
    out.push({ wgsLng: seg.b.wgsLng, wgsLat: seg.b.wgsLat, distM: walked, waypointIndex: segIdx + 1 })
  })
  if (out.length <= maxPoints) return out
  // 超预算时只稀疏中间采样点，航点本身（waypointIndex>=0，含首尾）必须保留
  const anchors = out.filter(p => p.waypointIndex >= 0)
  const interior = out.filter(p => p.waypointIndex < 0)
  const budget = Math.max(0, maxPoints - anchors.length)
  const thinned = []
  if (budget > 0 && interior.length > 0) {
    const stride = interior.length / budget
    for (let i = 0; i < budget; i++) thinned.push(interior[Math.floor(i * stride)])
  }
  return [...anchors, ...thinned].sort((a, b) => a.distM - b.distM)
}

/** 预演时间轴：travel 段（匀速插值）与 hover 段（原地停留）交替。 */
export function buildSimulationTimeline (waypoints, defaultSpeed) {
  const segments = []
  let t = 0
  if (Array.isArray(waypoints) && waypoints.length > 0) {
    const h0 = hoverSeconds(waypoints[0])
    if (h0 > 0) {
      segments.push({ kind: 'hover', from: waypoints[0], to: waypoints[0], startS: t, endS: t + h0 })
      t += h0
    }
    for (let i = 1; i < waypoints.length; i++) {
      const a = waypoints[i - 1]
      const b = waypoints[i]
      const d = haversineMeters(a.gcjLng, a.gcjLat, b.gcjLng, b.gcjLat)
      const travelS = d / segmentSpeed(b, defaultSpeed)
      segments.push({ kind: 'travel', from: a, to: b, startS: t, endS: t + travelS })
      t += travelS
      const h = hoverSeconds(b)
      if (h > 0) {
        segments.push({ kind: 'hover', from: b, to: b, startS: t, endS: t + h })
        t += h
      }
    }
  }
  return { segments, totalS: t, waypoints }
}

export function positionAtTime (timeline, timeS) {
  const { segments, waypoints } = timeline
  if (!segments.length) {
    const wp = waypoints && waypoints[0]
    return wp ? { gcjLng: wp.gcjLng, gcjLat: wp.gcjLat, height: wp.height } : null
  }
  if (timeS <= 0) {
    const wp = segments[0].from
    return { gcjLng: wp.gcjLng, gcjLat: wp.gcjLat, height: wp.height }
  }
  for (const seg of segments) {
    if (timeS <= seg.endS) {
      if (seg.kind === 'hover') {
        return { gcjLng: seg.to.gcjLng, gcjLat: seg.to.gcjLat, height: seg.to.height }
      }
      const t = (timeS - seg.startS) / (seg.endS - seg.startS)
      return {
        gcjLng: seg.from.gcjLng + (seg.to.gcjLng - seg.from.gcjLng) * t,
        gcjLat: seg.from.gcjLat + (seg.to.gcjLat - seg.from.gcjLat) * t,
        height: seg.from.height + (seg.to.height - seg.from.height) * t,
      }
    }
  }
  const last = segments[segments.length - 1].to
  return { gcjLng: last.gcjLng, gcjLat: last.gcjLat, height: last.height }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `node frontend/scripts/planner-utils.test.mjs`
Expected: 全部 `# pass`，exit 0（若个别数值断言差超容差，核对段速度约定后修实现而非放宽断言）

- [ ] **Step 5: Commit**

```bash
git add frontend/src/components/wayline-planner/planner-utils.mjs frontend/scripts/planner-utils.test.mjs
git commit -m "feat(planner): 统计/采样/预演时间轴纯函数"
```

### Task 6: use-planner-ui.ts（页签与面板 UI 状态）

**Files:**
- Create: `frontend/src/hooks/use-planner-ui.ts`
- Test: `frontend/scripts/planner-ui.test.mjs`（源码正则断言）

- [ ] **Step 1: 实现（小文件直接写，随后用源码断言测试锁形态）**

```ts
// 航线规划页 UI 状态（页签/抽屉/剖面/预演）。与飞行 store(use-wayline-planning) 解耦：
// 这里只放纯界面状态，刷新即重置，不持久化。
import { reactive, readonly } from 'vue'

export type PlannerTab = 'monitor' | 'delivery'

const state = reactive({
  activeTab: 'monitor' as PlannerTab,
  paramDrawerOpen: false,
  profileOpen: true,
  simulating: false,
  simulationTimeS: 0,
  simulationSpeedX: 1,
})

export function usePlannerUi () {
  return readonly(state)
}

export function setPlannerTab (tab: PlannerTab) {
  state.activeTab = tab
}

export function setParamDrawerOpen (open: boolean) {
  state.paramDrawerOpen = open
}

export function setProfileOpen (open: boolean) {
  state.profileOpen = open
}

export function startSimulation () {
  state.simulating = true
  state.simulationTimeS = 0
}

export function stopSimulation () {
  state.simulating = false
  state.simulationTimeS = 0
}

export function setSimulationTime (timeS: number) {
  state.simulationTimeS = Math.max(0, timeS)
}

export function setSimulationSpeed (x: number) {
  state.simulationSpeedX = [1, 2, 4, 8].includes(x) ? x : 1
}

export function getPlannerUiRaw () {
  return state
}
```

- [ ] **Step 2: 写测试并运行**

```js
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const src = readFileSync(new URL('../src/hooks/use-planner-ui.ts', import.meta.url), 'utf8')

test('planner ui hook exposes tab/drawer/profile/simulation state and setters', () => {
  assert.match(src, /activeTab:\s*'monitor'/)
  assert.match(src, /export function setPlannerTab/)
  assert.match(src, /export function setParamDrawerOpen/)
  assert.match(src, /export function setProfileOpen/)
  assert.match(src, /export function startSimulation/)
  assert.match(src, /export function stopSimulation/)
  assert.match(src, /export function setSimulationSpeed/)
  assert.match(src, /simulationSpeedX/)
})
```

Run: `node frontend/scripts/planner-ui.test.mjs`
Expected: pass

- [ ] **Step 3: Commit**

```bash
git add frontend/src/hooks/use-planner-ui.ts frontend/scripts/planner-ui.test.mjs
git commit -m "feat(planner): UI 状态 hook (页签/抽屉/剖面/预演)"
```

---

## Part C：页面骨架重组（每步保持页面可编译可用）

> 平移原则：**剪切-粘贴-改引用**，不重写逻辑。每个 Task 结束跑 `cd frontend && npm run build` 确认编译，并跑 `node frontend/scripts/fc100-delivery-ui.test.mjs` 等相关测试（断言旧 DOM 的测试在 Task 16 统一更新，此前若因平移变红，在该 Task 内同步把断言指到新文件，更新前后断言数量不得减少）。

### Task 7: 抽出 Fc100DeliveryView.vue + wayline.vue 加页签

**Files:**
- Create: `frontend/src/components/wayline-planner/Fc100DeliveryView.vue`
- Modify: `frontend/src/pages/page-web/projects/wayline.vue`
- Modify(若断言旧位置): `frontend/scripts/fc100-delivery-ui.test.mjs`

- [ ] **Step 1: 圈定 FC100 边界（先产出清单再动手）**

Run: `grep -n "fc100\|Fc100\|FC100" frontend/src/pages/page-web/projects/wayline.vue > /tmp/fc100-refs.txt && wc -l /tmp/fc100-refs.txt`
平移范围（按行号圈定，动手前用实际行号核对一遍）：
- template：`<div class="fc100-planning-panel">` 整块（约 L104-255）+ `planned-wayline-panel--delivery` 整块（约 L256-409）
- script：所有 `fc100*`/`Fc100*`/`isFc100*`/`formatFc100*`/`handleFc100*`/`refreshFc100*`/`syncFc100*`/`getFc100*` 函数、`fc100PlanningState`、`fc100RealtimeTimer` 等变量（约 L1100-2500 间穿插），及其专属 import（`/@/api/fire/delivery` 等）
- style：`.fc100-*` 全部规则
**不平移**（共享设施）：`<GMap>`、保存/详情弹窗（L410-555）、`use-wayline-planning` store 调用、`setTrackedAircraft`/`setFlightPositionFromWgs` 等 store 函数引用（FC100 代码里调用它们 → 在新组件里 import 同名函数即可）

- [ ] **Step 2: 创建 Fc100DeliveryView.vue 并剪切平移**

组件骨架（template/script/style 三段填入剪切内容，保持代码原样只改 import 路径）：

```vue
<template>
  <div class="fc100-delivery-view">
    <!-- 粘贴: fc100-planning-panel 整块 -->
    <!-- 粘贴: planned-wayline-panel--delivery 整块 -->
  </div>
</template>

<script lang="ts" setup>
// 粘贴: wayline.vue 中所有 FC100 专属 import / 状态 / 函数（原样搬运）
// 原 wayline.vue 中 FC100 代码引用的共享函数（如 setTrackedAircraft, setFlightPositionFromWgs,
// getPlannedWaylines 等）在此处直接 import 自原模块，不复制实现。
</script>

<style lang="scss" scoped>
/* 粘贴: .fc100-* 全部样式规则 */
</style>
```

- [ ] **Step 3: wayline.vue 顶部加页签，按页签渲染**

在 template 顶栏（L4-31 区域）下方加：

```vue
<a-tabs v-model:activeKey="plannerTab" class="planner-tabs" @change="onPlannerTabChange">
  <a-tab-pane key="monitor" tab="监测规划" />
  <a-tab-pane key="delivery" tab="投放任务" />
</a-tabs>
```

script 增加：

```ts
import Fc100DeliveryView from '/@/components/wayline-planner/Fc100DeliveryView.vue'
import { usePlannerUi, setPlannerTab } from '/@/hooks/use-planner-ui'
import { computed } from 'vue' // 已有则不重复

const plannerUi = usePlannerUi()
const plannerTab = computed({
  get: () => plannerUi.activeTab,
  set: (v: 'monitor' | 'delivery') => setPlannerTab(v),
})
function onPlannerTabChange () { /* 占位：Task 12 在此联动覆盖物显隐 */ }
```

原 M4T 区块包进 `v-show="plannerTab === 'monitor'"`，FC100 替换为 `<Fc100DeliveryView v-show="plannerTab === 'delivery'" />`。用 `v-show` 不用 `v-if`——FC100 的轮询定时器与状态须跨页签存活（设计决策：飞机跨页签常显）。

- [ ] **Step 4: 编译 + 测试 + 手动冒烟**

Run: `cd frontend && npm run build 2>&1 | tail -5 && node scripts/fc100-delivery-ui.test.mjs`
Expected: build 成功；测试如断言旧位置则把对应 `read('.../wayline.vue')` 改为新组件路径后全绿（断言数量不减少）
手动：浏览器打开航线页，页签切换正常，FC100 功能（设备列表刷新）在投放页签下可用

- [ ] **Step 5: Commit**

```bash
git add -A frontend
git commit -m "refactor(planner): FC100 投放区块平移至 Fc100DeliveryView，页面加双页签"
```

### Task 8: PlannerWorkspace + RouteListPanel（含任务监控）+ WaypointListPanel

**Files:**
- Create: `frontend/src/components/wayline-planner/PlannerWorkspace.vue`
- Create: `frontend/src/components/wayline-planner/RouteListPanel.vue`
- Create: `frontend/src/components/wayline-planner/WaypointListPanel.vue`
- Modify: `frontend/src/pages/page-web/projects/wayline.vue`

- [ ] **Step 1: PlannerWorkspace.vue（三段式布局壳，深色浮层）**

```vue
<template>
  <div class="planner-workspace">
    <div class="planner-left">
      <RouteListPanel />
      <WaypointListPanel />
    </div>
    <slot />  <!-- 统计条/工具栏/抽屉/剖面 由后续 Task 填充 -->
  </div>
</template>

<script lang="ts" setup>
import RouteListPanel from './RouteListPanel.vue'
import WaypointListPanel from './WaypointListPanel.vue'
</script>

<style lang="scss" scoped>
.planner-workspace {
  position: absolute;
  inset: 0;
  pointer-events: none; /* 地图可点；面板自身恢复事件 */
}
.planner-left {
  position: absolute;
  top: 12px;
  left: 12px;
  bottom: 12px;
  width: 300px;
  display: flex;
  flex-direction: column;
  gap: 10px;
  pointer-events: auto;
}
</style>
```

布局前提核查：`wayline.vue` 中 `<GMap>` 与左侧面板目前是并排布局还是地图全屏铺底？若现状是「左侧栏 + 地图」分栏（`scrollbar` 容器 L32），本 Task 把 M4T 左栏内容改为浮在地图上的 `PlannerWorkspace`（地图占满），这是设计决策"地图满屏铺底"的落点——动手前先确认 `<GMap>` 渲染位置（`grep -n "GMap" frontend/src/pages/page-web/projects/wayline.vue`），将其容器改为 `position:relative; height:100%` 并把 PlannerWorkspace 作为其兄弟浮层。

- [ ] **Step 2: RouteListPanel.vue —— 平移 M4T 航线列表（L42-103）**

剪切 `planned-wayline-panel` 整块进组件；**必须保留**：`record.flightId` 时的 `<WaylineMissionMonitor>`（L75-83，暂停/恢复/断点续飞/中止）、加载更多滚动、`onPreviewPlannedWayline`、详情/编辑/删除/下发按钮及其 handler（handler 留在 wayline.vue 的先以 props/emit 传入，或一并平移其实现——凡只被列表使用的函数一并平移）。深色样式：面板底色 `rgba(13,17,23,.93)`、边框 `#2c3a4f`、圆角 6px（对照设计 mockup `final-design.html`）。

- [ ] **Step 3: WaypointListPanel.vue —— 新写航点卡片列表**

```vue
<template>
  <div class="wp-list-panel">
    <div class="wp-list-head">航点列表 <span class="wp-count">{{ planningState.waypoints.length }}</span></div>
    <div class="wp-list-body">
      <div
        v-for="(wp, idx) in planningState.waypoints"
        :key="wp.id"
        class="wp-card"
        :class="{ selected: planningState.selectedWaypointId === wp.id }"
        @click="onSelect(wp.id)">
        <span class="wp-index">{{ idx + 1 }}</span>
        <span class="wp-meta">{{ wp.height }}m · {{ wp.speed || planningState.maxSpeed }}m/s</span>
        <span class="wp-actions-summary">{{ actionsSummary(wp) }}</span>
        <a-button type="text" size="small" danger class="wp-del" @click.stop="removeWaypoint(wp.id)">删</a-button>
      </div>
      <div v-if="planningState.waypoints.length === 0" class="wp-empty">点击地图添加航点</div>
    </div>
  </div>
</template>

<script lang="ts" setup>
import { usePlanningState, selectWaypoint, removeWaypoint } from '/@/hooks/use-wayline-planning'
import { setParamDrawerOpen } from '/@/hooks/use-planner-ui'
import type { PlannedWaypoint } from '/@/hooks/use-wayline-planning'

const { planningState } = usePlanningState()

const ACTION_LABELS: Record<string, string> = {
  takePhoto: '拍照', startRecord: '录像', stopRecord: '停录',
  gimbalRotate: '云台', hover: '悬停', focus: '对焦', rotateYaw: '转向',
}

function actionsSummary (wp: PlannedWaypoint) {
  if (!wp.actions || wp.actions.length === 0) return ''
  return wp.actions.map(a => ACTION_LABELS[a.actuatorFunc] || a.actuatorFunc).join('·')
}

function onSelect (id: string) {
  selectWaypoint(id)
  setParamDrawerOpen(true)
}
</script>
```

（样式同 RouteListPanel 深色基调；选中卡片边框 `#43d675`。`usePlanningState` 返回结构先 `grep -n "usePlanningState" frontend/src/hooks/use-wayline-planning.ts` 核对解构形态，L993-1002。）

- [ ] **Step 4: wayline.vue 接线 + 编译 + 冒烟**

monitor 页签内容替换为 `<PlannerWorkspace>`（旧的 M4T 列表模板段删除——已平移）。
Run: `cd frontend && npm run build 2>&1 | tail -5`
Expected: build 成功；浏览器冒烟：列表加载、点选航点高亮、删点生效、任务监控（若有执行中任务）仍显示

- [ ] **Step 5: Commit**

```bash
git add -A frontend
git commit -m "refactor(planner): 三段式骨架 + 航线列表(含监控)平移 + 航点卡片列表"
```

### Task 9: WaypointParamDrawer + MissionParamsPanel

**Files:**
- Create: `frontend/src/components/wayline-planner/WaypointParamDrawer.vue`
- Create: `frontend/src/components/wayline-planner/MissionParamsPanel.vue`
- Modify: `frontend/src/pages/page-web/projects/wayline.vue`

- [ ] **Step 1: WaypointParamDrawer.vue —— 平移航点级表单**

现 wayline.vue L556-948 区域中的**航点级**编辑表单（高度/速度/云台俯仰/航向模式/POI/转弯模式/动作增删 —— 调用 `updateWaypointField`/`addWaypointAction`/`removeWaypointAction`/`updateWaypointActionParam` 的部分）剪切进右侧抽屉：

```vue
<template>
  <a-drawer
    :visible="plannerUi.paramDrawerOpen && !!selectedWaypoint"
    placement="right"
    :width="320"
    :mask="false"
    :title="`航点 ${selectedIndex + 1} 参数`"
    class="wp-param-drawer"
    @close="setParamDrawerOpen(false)">
    <!-- 粘贴: 航点级参数表单（原样搬运，v-model 绑定不变） -->
    <a-button danger block style="margin-top: 12px" @click="onRemove">删除航点</a-button>
  </a-drawer>
</template>

<script lang="ts" setup>
import { computed } from 'vue'
import { usePlanningState, removeWaypoint } from '/@/hooks/use-wayline-planning'
import { usePlannerUi, setParamDrawerOpen } from '/@/hooks/use-planner-ui'
// 粘贴: 表单逻辑需要的其余 import（updateWaypointField 等）

const { planningState } = usePlanningState()
const plannerUi = usePlannerUi()
const selectedWaypoint = computed(() =>
  planningState.waypoints.find(w => w.id === planningState.selectedWaypointId) || null)
const selectedIndex = computed(() =>
  planningState.waypoints.findIndex(w => w.id === planningState.selectedWaypointId))

function onRemove () {
  if (selectedWaypoint.value) {
    removeWaypoint(selectedWaypoint.value.id)
    setParamDrawerOpen(false)
  }
}
</script>
```

注意 antd-vue 2.2.8：抽屉是 `:visible`（**不是** 4.x 的 `open`）。

- [ ] **Step 2: MissionParamsPanel.vue —— 平移任务级表单**

现 wayline.vue 的"高级配置"区（约 L622-740：`finishAction`/`exitOnRcLost`/`rcLostAction`/`takeoffSecurityHeight`/`globalTransitionalSpeed`/`rthAltitude`/默认高度/默认速度，调用 `setMissionConfig` 的部分）剪切为左下角可折叠面板（挂在 PlannerWorkspace 左列底部）。逻辑原样，调用 `setMissionConfig`（hook L719-734）。

- [ ] **Step 3: 编译 + 冒烟 + Commit**

Run: `cd frontend && npm run build 2>&1 | tail -5`
Expected: 成功；冒烟：点航点弹抽屉、改高度立即反映在卡片、任务参数改动在保存的 body 中生效（保存一条航线验证 finishAction 传值）

```bash
git add -A frontend
git commit -m "refactor(planner): 航点参数抽屉 + 任务级参数面板"
```

### Task 10: MissionStatsBar + PlannerToolbar

**Files:**
- Create: `frontend/src/components/wayline-planner/MissionStatsBar.vue`
- Create: `frontend/src/components/wayline-planner/PlannerToolbar.vue`
- Modify: `frontend/src/pages/page-web/projects/wayline.vue`
- Test: `frontend/scripts/planner-components.test.mjs`

- [ ] **Step 1: MissionStatsBar.vue**

```vue
<template>
  <div class="mission-stats-bar" v-if="stats.count > 0">
    <span>里程 <b>{{ formatDistance(stats.distanceM) }}</b></span>
    <span>预计 <b>{{ formatDuration(stats.durationS) }}</b></span>
    <span>航点 <b>{{ stats.count }}</b></span>
  </div>
</template>

<script lang="ts" setup>
import { computed } from 'vue'
import { usePlanningState } from '/@/hooks/use-wayline-planning'
import { computeRouteStats } from './planner-utils.mjs'

const { planningState } = usePlanningState()
const stats = computed(() => computeRouteStats(planningState.waypoints, planningState.maxSpeed))

function formatDistance (m: number) {
  return m >= 1000 ? `${(m / 1000).toFixed(2)}km` : `${Math.round(m)}m`
}
function formatDuration (s: number) {
  const min = Math.floor(s / 60)
  const sec = Math.round(s % 60)
  return min > 0 ? `${min}min${sec}s` : `${sec}s`
}
</script>

<style lang="scss" scoped>
.mission-stats-bar {
  position: absolute;
  top: 12px;
  left: 50%;
  transform: translateX(-50%);
  background: rgba(13, 17, 23, .93);
  border: 1px solid #2c3a4f;
  border-radius: 16px;
  padding: 4px 16px;
  color: #cfd8e3;
  pointer-events: auto;
  display: flex;
  gap: 16px;
  b { color: #fff; }
}
</style>
```

（`.mjs` 从 `.vue` 引入：vite 原生支持，无需配置。）

- [ ] **Step 2: PlannerToolbar.vue —— 平移既有按钮+新增预演入口**

底部居中工具栏：加点模式开关（对应现有 `startPlanning/stopPlanning` 或现有"规划"开关按钮——`grep -n "startPlanning\|stopPlanning" frontend/src/pages/page-web/projects/wayline.vue` 找到现有触发点平移）、撤销（=删除最后一个航点：`removeWaypoint(waypoints[waypoints.length-1].id)`）、清空（`clearWaypoints`，带 `a-popconfirm` 确认）、保存（打开现有保存弹窗——弹窗本体留在 wayline.vue，通过 emit `save` 触发）、下发执行（现有 `startExecution` 入口平移）、模拟预演按钮（`startSimulation()`，执行中 `planningState.executing` 时 disabled，Task 15 接动画）。

- [ ] **Step 3: 组件形态测试**

```js
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const read = (p) => readFileSync(new URL(p, import.meta.url), 'utf8')
const statsBar = read('../src/components/wayline-planner/MissionStatsBar.vue')
const toolbar = read('../src/components/wayline-planner/PlannerToolbar.vue')
const drawer = read('../src/components/wayline-planner/WaypointParamDrawer.vue')
const routeList = read('../src/components/wayline-planner/RouteListPanel.vue')

test('stats bar uses shared computeRouteStats', () => {
  assert.match(statsBar, /computeRouteStats/)
  assert.match(statsBar, /planner-utils\.mjs/)
})
test('toolbar wires undo/clear/save/execute/simulation', () => {
  assert.match(toolbar, /removeWaypoint/)
  assert.match(toolbar, /clearWaypoints/)
  assert.match(toolbar, /startSimulation/)
  assert.match(toolbar, /executing/)
})
test('drawer uses antd2 visible prop (not 4.x open)', () => {
  assert.match(drawer, /:visible=/)
  assert.doesNotMatch(drawer, /v-model:open=/)
})
test('route list keeps mission monitor', () => {
  assert.match(routeList, /WaylineMissionMonitor/)
})
```

Run: `node frontend/scripts/planner-components.test.mjs`
Expected: pass

- [ ] **Step 4: 编译 + 冒烟 + Commit**

Run: `cd frontend && npm run build 2>&1 | tail -5 && node frontend/scripts/planner-components.test.mjs`

```bash
git add -A frontend
git commit -m "feat(planner): 统计胶囊条 + 底部工具栏"
```

---

## Part D：地图层

### Task 11: GMap 航线页默认卫星 + RoadNet 路网叠加

**Files:**
- Modify: `frontend/src/components/GMap.vue`（L868-905 区域）

- [ ] **Step 1: ensureSatelliteLayer 增加 RoadNet；setWaylineMapLayer 卫星模式叠加路网**

在 `ensureSatelliteLayer`（L877）旁增加：

```ts
let roadNetLayer: any = null
function ensureRoadNetLayer () {
  const AMap = root?.$aMap
  if (!AMap || roadNetLayer) return roadNetLayer
  roadNetLayer = new AMap.TileLayer.RoadNet()
  return roadNetLayer
}
```

`setWaylineMapLayer`（L884）卫星分支 `map.setLayers([standard, satellite])` 改为 `map.setLayers([standard, satellite, roadNet].filter(Boolean))`（`const roadNet = ensureRoadNetLayer()`）。

- [ ] **Step 2: 航线页默认卫星**

找到地图初始化完成点（`grep -n "complete\|initMap\|\$map =" frontend/src/components/GMap.vue` 定位），加：

```ts
import { useRoute } from 'vue-router' // 已有则复用
// 地图就绪后：
if (String(route.name || route.path).toLowerCase().includes('wayline')) {
  setWaylineMapLayer('satellite')
}
```

（路由名先 `grep -n "wayline" frontend/src/router/index.ts` 确认；其他页面不受影响——只有 wayline 路由默认切卫星，按钮仍可切回。）

- [ ] **Step 3: 编译 + 冒烟 + Commit**

Run: `cd frontend && npm run build 2>&1 | tail -5`
冒烟：航线页进入即卫星图+路网标注；tsa 页仍默认矢量；「卫星/矢量」按钮可往返切换。

```bash
git add frontend/src/components/GMap.vue
git commit -m "feat(map): 航线页默认卫星图层并叠加路网标注"
```

### Task 12: 覆盖物渲染抽离 use-planner-overlays.ts（行为等价搬家）

**Files:**
- Create: `frontend/src/hooks/use-planner-overlays.ts`
- Modify: `frontend/src/components/GMap.vue`（L756-1040 区域瘦身）

- [ ] **Step 1: 新建模块并整体平移**

把 GMap.vue 中规划覆盖物的全部代码搬入新模块（**含**飞行位置/轨迹：`planningMarkers/planningPolyline/flightPositionMarker/flightTrackPolyline` 变量、`waypointMarkerContent` :806、`renderPlanningWaypoints` :758、`rebuildPlanningOverlays` :817、`updateFlightPositionOverlay` :946、相关 watch 与地图点击注册 ~L770-800、`setAircraftView`/`locateAircraftPosition` :856-866）。模块签名：

```ts
// 规划/飞行覆盖物渲染（从 GMap.vue 抽出）。持有 map/AMap 引用，渲染逻辑行为等价搬家。
import { watch } from 'vue'
import { addWaypointGcj, getPlanningStateRaw, selectWaypoint } from '/@/hooks/use-wayline-planning'
import { getPlannerUiRaw } from '/@/hooks/use-planner-ui'

export function usePlannerOverlays (getMap: () => any, getAMap: () => any) {
  // …平移进来的全部状态与函数…
  return { setAircraftView, locateAircraftPosition }
}
```

GMap.vue 对应区域替换为：

```ts
import { usePlannerOverlays } from '/@/hooks/use-planner-overlays'
const { setAircraftView, locateAircraftPosition } = usePlannerOverlays(() => root?.$map, () => root?.$aMap)
```

（GMap 原有对这两个函数的调用点——recenter watcher 等——保持可用。）

- [ ] **Step 2: 页签联动（设计决策：航线覆盖物随页签，飞机/轨迹常显）**

新模块内对规划覆盖物渲染加守卫：`getPlannerUiRaw().activeTab === 'monitor'` 才渲染 M4T 规划航线覆盖物（watch `activeTab` 触发 rebuild/clear）；`updateFlightPositionOverlay` **不加**页签守卫（跨页签常显）。

- [ ] **Step 3: 编译 + 行为等价冒烟 + Commit**

Run: `cd frontend && npm run build 2>&1 | tail -5 && node frontend/scripts/wayline-aircraft-position.test.mjs 2>/dev/null || node frontend/src/pages/page-web/projects/__tests__/wayline-aircraft-position.test.mjs`
（该测试实际路径先 `ls frontend/src/pages/page-web/projects/__tests__/` 确认。）
冒烟清单：点选加点画线、选中高亮、飞机图标与轨迹、一键居中（recenter）全部与重构前一致；切到投放页签航线消失、飞机仍在。

```bash
git add -A frontend
git commit -m "refactor(map): 规划/飞行覆盖物抽离至 use-planner-overlays（行为等价）"
```

### Task 13: S2 信息常显样式 + 距离标签 + H 标记 + 方向箭头

**Files:**
- Modify: `frontend/src/hooks/use-planner-overlays.ts`
- Modify(样式): `frontend/src/components/GMap.vue` 或全局样式文件（标记 content 的 class 样式现在哪就改哪：`grep -rn "wayline-planning-marker" frontend/src`）

- [ ] **Step 1: 航点 marker 升级 S2 信息牌**

`waypointMarkerContent` 改为序号圆点 + 信息牌：

```ts
function waypointMarkerContent (idx: number, wp: PlannedWaypoint) {
  const isSelected = planningState.selectedWaypointId === wp.id
  const speed = wp.speed || planningState.maxSpeed
  const acts = (wp.actions || []).map(a => ACTION_LABELS[a.actuatorFunc] || a.actuatorFunc).join('·')
  const info = `${wp.height}m · ${speed}m/s${acts ? ' · ' + acts : ''}`
  return `
    <div class="planner-wp ${isSelected ? 'planner-wp--selected' : ''}">
      <div class="planner-wp-dot">${idx + 1}</div>
      <div class="planner-wp-card">${info}</div>
    </div>`
}
```

CSS（深色、绿描边，对照 mockup `map-style.html` S2）：

```scss
.planner-wp { display: flex; align-items: center; gap: 4px; }
.planner-wp-dot {
  width: 22px; height: 22px; border-radius: 50%;
  background: #10243a; border: 2px solid #43d675; color: #d7ffe8;
  font-size: 11px; font-weight: 600; display: flex; align-items: center; justify-content: center;
}
.planner-wp-card {
  background: rgba(10, 18, 30, .85); color: #bcd; border-radius: 3px;
  padding: 1px 6px; font-size: 11px; white-space: nowrap;
}
.planner-wp--selected .planner-wp-dot { background: #43d675; color: #06270f; transform: scale(1.2); }
.planner-wp--selected .planner-wp-card { border: 1px solid #43d675; color: #d7ffe8; }
.planner-wp--mini .planner-wp-card { display: none; } /* 低缩放隐藏信息牌 */
```

缩放显隐：监听 `map.on('zoomend')`，`map.getZoom() < 15` 时给 content 加 `planner-wp--mini`（重渲 markers 或 toggle class）。

- [ ] **Step 2: Polyline 绿色+方向箭头 + 航段距离标签**

`planningPolyline` 选项改：`strokeColor: '#43d675', strokeWeight: 6, showDir: true`（showDir 需线宽≥6 箭头才清晰）。
距离标签：相邻航点中点放 `AMap.Text`（或 content Marker），文本 `haversineMeters(...)` 取整 + 'm'（>=1000 转 km），样式深色胶囊，`zoom < 15` 隐藏，存入 `distanceLabels: any[]` 数组随 rebuild 清理。距离函数从 `./../components/wayline-planner/planner-utils.mjs` import（同一实现勿复制）。

- [ ] **Step 3: H 起飞点标记（分模式，设计 v2 决策）**

```ts
function rebuildHomeMarker () {
  clearHomeMarker()
  const ui = getPlannerUiRaw()
  let pos: { gcjLng: number, gcjLat: number } | null = null
  if (ui.activeTab === 'monitor') {
    // M4T：飞机当前位置近似（无位置不画）
    pos = planningState.flightPosition
  }
  // FC100 页签的 H：投放航线预览数据中带 takeoff 坐标时绘制；
  // 数据可得性先核实（grep delivery API 的 takeoff 字段），拿不到就本期跳过 FC100 H。
  if (!pos || planningState.waypoints.length === 0) return
  homeMarker = new AMap.Marker({ position: [pos.gcjLng, pos.gcjLat], content: H_CONTENT, zIndex: 105 })
  homeGuideLine = new AMap.Polyline({
    path: [[pos.gcjLng, pos.gcjLat], [wp0.gcjLng, wp0.gcjLat]],
    strokeColor: '#8a93a3', strokeWeight: 2, strokeStyle: 'dashed',
  })
  map.add([homeMarker, homeGuideLine])
}
const H_CONTENT = '<div class="planner-home">H</div>'
// .planner-home: 黄描边圆形深底，对照 mockup
```

- [ ] **Step 4: 编译 + 视觉冒烟 + Commit**

Run: `cd frontend && npm run build 2>&1 | tail -5`
冒烟对照 `final-design.html`：信息牌、绿线箭头、距离标签、选中态、低缩放收纳、H+虚线（选中飞机时）。

```bash
git add -A frontend
git commit -m "feat(map): S2 信息常显航点样式+距离标签+H起飞点+方向箭头"
```

### Task 14: 拖拽改位 / 中点插点 / 右键删点

**Files:**
- Modify: `frontend/src/hooks/use-wayline-planning.ts`（新增 2 个 store 函数）
- Modify: `frontend/src/hooks/use-planner-overlays.ts`
- Test: `frontend/scripts/planner-store-edit.test.mjs`

- [ ] **Step 1: 写失败测试（源码形态断言——store 是 TS，无法 node 直跑）**

```js
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const store = readFileSync(new URL('../src/hooks/use-wayline-planning.ts', import.meta.url), 'utf8')
const overlays = readFileSync(new URL('../src/hooks/use-planner-overlays.ts', import.meta.url), 'utf8')

test('store exposes drag-update and midpoint-insert with wgs sync and exec guard', () => {
  assert.match(store, /export function updateWaypointPositionGcj/)
  assert.match(store, /export function insertWaypointAfterGcj/)
  // 两个函数体内都必须: 执行中禁改 + gcj→wgs 同步 + persistDraft
  const upd = store.slice(store.indexOf('export function updateWaypointPositionGcj'))
  assert.match(upd.slice(0, 600), /state\.executing/)
  assert.match(upd.slice(0, 600), /gcj02towgs84/)
  assert.match(upd.slice(0, 600), /persistDraft/)
  const ins = store.slice(store.indexOf('export function insertWaypointAfterGcj'))
  assert.match(ins.slice(0, 800), /state\.executing/)
  assert.match(ins.slice(0, 800), /gcj02towgs84/)
  assert.match(ins.slice(0, 800), /persistDraft/)
})

test('overlays wire draggable markers, midpoint ghosts, rightclick delete', () => {
  assert.match(overlays, /draggable:\s*true/)
  assert.match(overlays, /dragend/)
  assert.match(overlays, /updateWaypointPositionGcj/)
  assert.match(overlays, /insertWaypointAfterGcj/)
  assert.match(overlays, /rightclick/)
  assert.match(overlays, /removeWaypoint/)
})
```

Run: `node frontend/scripts/planner-store-edit.test.mjs` → Expected: FAIL

- [ ] **Step 2: store 函数（加在 `removeWaypoint` L541 之后，风格对齐相邻函数）**

```ts
export function updateWaypointPositionGcj (id: string, gcjLng: number, gcjLat: number) {
  if (state.executing) return
  const wp = state.waypoints.find(w => w.id === id)
  if (!wp || !Number.isFinite(gcjLng) || !Number.isFinite(gcjLat)) return
  const [wgsLng, wgsLat] = gcj02towgs84(gcjLng, gcjLat)
  wp.gcjLng = gcjLng
  wp.gcjLat = gcjLat
  wp.wgsLng = wgsLng
  wp.wgsLat = wgsLat
  persistDraft()
}

export function insertWaypointAfterGcj (afterId: string, gcjLng: number, gcjLat: number): PlannedWaypoint | null {
  if (state.executing) return null
  const idx = state.waypoints.findIndex(w => w.id === afterId)
  if (idx < 0 || !Number.isFinite(gcjLng) || !Number.isFinite(gcjLat)) return null
  const [wgsLng, wgsLat] = gcj02towgs84(gcjLng, gcjLat)
  const prev = state.waypoints[idx]
  const wp: PlannedWaypoint = {
    id: uuidv4(),
    gcjLng, gcjLat, wgsLng, wgsLat,
    height: prev.height,
  }
  state.waypoints.splice(idx + 1, 0, normalizePlannedWaypoint(wp))
  state.selectedWaypointId = wp.id
  persistDraft()
  return wp
}
```

（间距限制：`addWaypointGcj` L515-539 有 `MIN_WAYPOINT_SPACING_M` 校验——读它的实现，插点函数做同样校验，与前后两点都需 ≥ 最小间距，不满足时 `message.warning` 并返回 null，行为与现有加点一致。）

- [ ] **Step 3: overlays 接交互**

- marker 构造加 `draggable: true`（执行中 `planningState.executing` 时 rebuild 为不可拖）；`marker.on('dragend', e => updateWaypointPositionGcj(wp.id, e.lnglat.getLng(), e.lnglat.getLat()))`
- `marker.on('rightclick', () => removeWaypoint(wp.id))`
- 中点幽灵点：rebuild 时相邻航点中点放半透明 "+" marker（class `planner-wp-ghost`，虚线描边样式对照 mockup），`click` → `insertWaypointAfterGcj(prevWp.id, midGcjLng, midGcjLat)`；存入 `ghostMarkers: any[]` 随 rebuild 清理；`zoom < 15` 或执行中不渲染。
- store 函数 import 进 overlays。

- [ ] **Step 4: 测试 + 编译 + 冒烟 + Commit**

Run: `node frontend/scripts/planner-store-edit.test.mjs && cd frontend && npm run build 2>&1 | tail -5`
冒烟：拖航点松手线随动、刷新后位置保留（localStorage draft）；点 "+" 插点序号重排；右键删点；执行中三种操作均禁用。

```bash
git add -A frontend
git commit -m "feat(planner): 航点拖拽/中点插点/右键删点"
```

---

## Part E：剖面、预演与收尾

### Task 15: 高程 API 封装 + ElevationProfile.vue

**Files:**
- Create: `frontend/src/api/terrain.ts`
- Create: `frontend/src/components/wayline-planner/ElevationProfile.vue`
- Modify: `frontend/src/pages/page-web/projects/wayline.vue`（挂载）
- Test: `frontend/scripts/elevation-profile.test.mjs`

- [ ] **Step 1: api/terrain.ts（前缀对齐现有 API：先 `grep -n "manage/api/v1\|HTTP_PREFIX" frontend/src/api/wayline.ts frontend/src/api/http/request.ts` 确认常量写法，下面以字面量为例）**

```ts
import request, { IWorkspaceResponse } from '/@/api/http/request'

const PREFIX = '/manage/api/v1' // 以 grep 确认的现有写法为准

export interface ElevationPoint { lat: number; lng: number }

export const getTerrainElevations = async function (points: ElevationPoint[]): Promise<(number | null)[]> {
  const resp: IWorkspaceResponse<(number | null)[]> = await request.post(`${PREFIX}/terrain/elevations`, points)
  if (resp.code !== 0 || !Array.isArray(resp.data)) {
    throw new Error(resp.message || 'terrain elevations failed')
  }
  return resp.data
}
```

（`resp.code !== 0` 的成功码按 `request.ts` 现状核对——看 `HttpResultResponse` 成功码映射；`IWorkspaceResponse` 形态参照 `api/wayline.ts` 现有用法。）

- [ ] **Step 2: ElevationProfile.vue**

```vue
<template>
  <div class="elevation-profile" v-if="plannerUi.profileOpen && planningState.waypoints.length >= 2">
    <div class="ep-head">
      <span>高度剖面 <small class="ep-dim">绿=航线 棕=地形(含植被冠层)</small></span>
      <span class="ep-dim" v-if="baselineLabel">{{ baselineLabel }}</span>
      <span class="ep-dim ep-warn" v-if="terrainMissing">地形数据缺失</span>
      <a-button type="text" size="small" @click="setProfileOpen(false)">收起</a-button>
    </div>
    <svg :viewBox="`0 0 ${W} ${H}`" preserveAspectRatio="none" class="ep-svg">
      <polygon v-if="terrainPath" :points="terrainPolygon" fill="rgba(146,116,77,.3)" />
      <polyline v-if="terrainPath" :points="terrainPath" fill="none" stroke="#92744d" stroke-width="1.5" />
      <polyline :points="routePath" fill="none" stroke="#43d675" stroke-width="2" />
      <circle v-for="p in routeDots" :key="p.idx" :cx="p.x" :cy="p.y" r="3" fill="#43d675" />
    </svg>
  </div>
  <a-button v-else-if="planningState.waypoints.length >= 2" class="ep-reopen" size="small" @click="setProfileOpen(true)">高度剖面</a-button>
</template>

<script lang="ts" setup>
import { computed, ref, watch } from 'vue'
import { usePlanningState } from '/@/hooks/use-wayline-planning'
import { usePlannerUi, setProfileOpen } from '/@/hooks/use-planner-ui'
import { sampleRoutePoints, computeRouteStats } from './planner-utils.mjs'
import { getTerrainElevations } from '/@/api/terrain'

const W = 800
const H = 120
const PAD = 8

const { planningState } = usePlanningState()
const plannerUi = usePlannerUi()

const terrainElevations = ref<(number | null)[]>([])
const sampledPoints = ref<ReturnType<typeof sampleRoutePoints>>([])
const baselineElev = ref<number | null>(null)
const baselineLabel = ref('')
const terrainMissing = ref(false)
let debounceTimer: number | null = null

watch(() => planningState.waypoints.map(w => `${w.wgsLng},${w.wgsLat},${w.height}`).join('|'),
  () => {
    if (debounceTimer) window.clearTimeout(debounceTimer)
    debounceTimer = window.setTimeout(refreshTerrain, 500)
  }, { immediate: true })

async function refreshTerrain () {
  const wps = planningState.waypoints
  if (wps.length < 2) { sampledPoints.value = []; terrainElevations.value = []; return }
  const samples = sampleRoutePoints(wps, { maxPoints: 499, minStepM: 30 })
  // 基准点：M4T 优先飞机当前位置，否则 1 号航点正下方（设计 v2 决策）
  const fp = planningState.flightPosition
  const basePoint = fp && fp.wgsLng != null
    ? { lat: fp.wgsLat as number, lng: fp.wgsLng as number }
    : { lat: wps[0].wgsLat, lng: wps[0].wgsLng }
  baselineLabel.value = fp && fp.wgsLng != null ? '基准: 飞机当前位置(近似)' : '基准: 1号航点地面'
  try {
    const elevations = await getTerrainElevations([
      basePoint,
      ...samples.map(s => ({ lat: s.wgsLat, lng: s.wgsLng })),
    ])
    baselineElev.value = elevations[0]
    terrainElevations.value = elevations.slice(1)
    sampledPoints.value = samples
    terrainMissing.value = elevations.slice(1).every(e => e === null)
  } catch {
    terrainElevations.value = []
    sampledPoints.value = samples
    terrainMissing.value = true
  }
}

/** 纵轴: 相对基准高度(m)。横轴: 里程。 */
const scale = computed(() => {
  const wps = planningState.waypoints
  const totalM = computeRouteStats(wps, planningState.maxSpeed).distanceM || 1
  const heights = wps.map(w => w.height)
  const terrainRel = relTerrain()
  const all = [...heights, ...terrainRel.filter((v): v is number => v !== null), 0]
  const maxY = Math.max(...all) + 10
  const minY = Math.min(...all) - 5
  return {
    x: (distM: number) => PAD + (distM / totalM) * (W - 2 * PAD),
    y: (val: number) => H - PAD - ((val - minY) / (maxY - minY)) * (H - 2 * PAD),
    totalM,
  }
})

function relTerrain (): (number | null)[] {
  const base = baselineElev.value
  if (base == null) return terrainElevations.value.map(() => null)
  return terrainElevations.value.map(e => (e == null ? null : e - base))
}

const routePath = computed(() => {
  // 航点投影到采样点的里程轴（waypointIndex>=0 的采样点即航点本身）
  const wps = planningState.waypoints
  const out: string[] = []
  sampledPoints.value.forEach(s => {
    if (s.waypointIndex >= 0) {
      const wp = wps[s.waypointIndex]
      if (wp) out.push(`${scale.value.x(s.distM)},${scale.value.y(wp.height)}`)
    }
  })
  return out.join(' ')
})

const routeDots = computed(() =>
  sampledPoints.value
    .filter(s => s.waypointIndex >= 0)
    .map(s => ({
      idx: s.waypointIndex,
      x: scale.value.x(s.distM),
      y: scale.value.y(planningState.waypoints[s.waypointIndex]?.height ?? 0),
    })))

const terrainPath = computed(() => {
  const rel = relTerrain()
  if (rel.every(v => v === null)) return ''
  return sampledPoints.value
    .map((s, i) => (rel[i] == null ? null : `${scale.value.x(s.distM)},${scale.value.y(rel[i] as number)}`))
    .filter(Boolean)
    .join(' ')
})

const terrainPolygon = computed(() => {
  if (!terrainPath.value) return ''
  const first = sampledPoints.value[0]
  const last = sampledPoints.value[sampledPoints.value.length - 1]
  return `${scale.value.x(first.distM)},${H - PAD} ${terrainPath.value} ${scale.value.x(last.distM)},${H - PAD}`
})
</script>

<style lang="scss" scoped>
.elevation-profile {
  position: absolute;
  left: 12px; right: 12px; bottom: 12px; height: 150px;
  background: rgba(13, 17, 23, .93); border: 1px solid #2c3a4f; border-radius: 6px;
  padding: 6px 10px; pointer-events: auto; color: #cfd8e3;
  .ep-head { display: flex; justify-content: space-between; align-items: center; font-size: 13px; }
  .ep-dim { color: #7d8ca0; font-size: 11px; }
  .ep-warn { color: #d4a017; }
  .ep-svg { width: 100%; height: calc(100% - 26px); }
}
.ep-reopen { position: absolute; bottom: 12px; left: 12px; pointer-events: auto; }
</style>
```

（实现时删掉 `routePath` 里的占位循环，直接用采样点法那段；plan 保留两段是为了说明取舍——以采样点 `waypointIndex` 投影为准。）

- [ ] **Step 3: 测试 + 编译 + 联调 + Commit**

```js
// frontend/scripts/elevation-profile.test.mjs
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const profile = readFileSync(new URL('../src/components/wayline-planner/ElevationProfile.vue', import.meta.url), 'utf8')
const api = readFileSync(new URL('../src/api/terrain.ts', import.meta.url), 'utf8')

test('profile: 自适应采样≤500点、防抖、降级、双基准', () => {
  assert.match(profile, /maxPoints:\s*499/)
  assert.match(profile, /setTimeout\(refreshTerrain,\s*500\)/)
  assert.match(profile, /地形数据缺失/)
  assert.match(profile, /flightPosition/)
  assert.match(profile, /1号航点地面/)
  assert.match(profile, /含植被冠层/)
})
test('terrain api posts to /terrain/elevations', () => {
  assert.match(api, /terrain\/elevations/)
})
```

Run: `node frontend/scripts/elevation-profile.test.mjs && cd frontend && npm run build 2>&1 | tail -5`
联调（需后端跑着且 dem-dir 有瓦片，没瓦片则验证降级提示）：画 3 个航点，剖面出现绿线；有 DEM 时棕色地形线出现。

```bash
git add -A frontend
git commit -m "feat(planner): 高度剖面（航点折线+DEM地形线+降级）"
```

### Task 16: SimulationBar + 预演动画

**Files:**
- Create: `frontend/src/components/wayline-planner/SimulationBar.vue`
- Modify: `frontend/src/hooks/use-planner-overlays.ts`（幻影飞机 marker）
- Test: `frontend/scripts/planner-simulation.test.mjs`

- [ ] **Step 1: SimulationBar.vue**

```vue
<template>
  <div class="simulation-bar" v-if="plannerUi.simulating">
    <a-button size="small" @click="togglePlay">{{ playing ? '暂停' : '播放' }}</a-button>
    <a-slider class="sim-slider" :min="0" :max="timeline.totalS" :step="0.1"
      :value="plannerUi.simulationTimeS" :tip-formatter="formatT"
      @change="(v: number) => setSimulationTime(v)" />
    <a-select size="small" :value="plannerUi.simulationSpeedX" style="width: 64px"
      @change="(v: number) => setSimulationSpeed(v)">
      <a-select-option v-for="x in [1, 2, 4, 8]" :key="x" :value="x">{{ x }}x</a-select-option>
    </a-select>
    <span class="sim-time">{{ formatT(plannerUi.simulationTimeS) }} / {{ formatT(timeline.totalS) }}</span>
    <a-button size="small" danger @click="onExit">退出预演</a-button>
  </div>
</template>

<script lang="ts" setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { usePlanningState } from '/@/hooks/use-wayline-planning'
import { usePlannerUi, setSimulationTime, setSimulationSpeed, stopSimulation } from '/@/hooks/use-planner-ui'
import { buildSimulationTimeline } from './planner-utils.mjs'

const { planningState } = usePlanningState()
const plannerUi = usePlannerUi()
const playing = ref(false)
let raf = 0
let lastTs = 0

const timeline = computed(() => buildSimulationTimeline(planningState.waypoints, planningState.maxSpeed))

function tick (ts: number) {
  if (!playing.value) return
  const dt = lastTs ? (ts - lastTs) / 1000 : 0
  lastTs = ts
  const next = plannerUi.simulationTimeS + dt * plannerUi.simulationSpeedX
  if (next >= timeline.value.totalS) {
    setSimulationTime(timeline.value.totalS)
    playing.value = false
    return
  }
  setSimulationTime(next)
  raf = requestAnimationFrame(tick)
}

function togglePlay () {
  playing.value = !playing.value
  lastTs = 0
  if (playing.value) raf = requestAnimationFrame(tick)
}

function onExit () {
  playing.value = false
  stopSimulation()
}

function formatT (s: number) {
  const m = Math.floor(s / 60)
  return `${m}:${String(Math.round(s % 60)).padStart(2, '0')}`
}

watch(() => plannerUi.simulating, (on) => { if (!on) { playing.value = false; cancelAnimationFrame(raf) } })
onBeforeUnmount(() => cancelAnimationFrame(raf))
</script>

<style lang="scss" scoped>
.simulation-bar {
  position: absolute; bottom: 172px; left: 50%; transform: translateX(-50%);
  display: flex; align-items: center; gap: 10px;
  background: rgba(13, 17, 23, .93); border: 1px solid #2c3a4f; border-radius: 6px;
  padding: 6px 12px; pointer-events: auto; color: #cfd8e3;
  .sim-slider { width: 240px; margin: 0; }
  .sim-time { font-size: 12px; color: #7d8ca0; }
}
</style>
```

- [ ] **Step 2: overlays 增加幻影飞机**

`use-planner-overlays.ts` 内 watch `[plannerUi.simulating, plannerUi.simulationTimeS]`：simulating 时按 `positionAtTime(buildSimulationTimeline(...), simulationTimeS)` 更新幻影 marker（半透明飞机图标 content，class `planner-sim-ghost`，zIndex 120）；退出时移除。`startSimulation` 入口在 PlannerToolbar 已留（Task 10），`planningState.executing === true` 时按钮 disabled（双保险：overlays 内 executing 变 true 时强制 `stopSimulation()`）。

- [ ] **Step 3: 测试 + 编译 + 冒烟 + Commit**

```js
// frontend/scripts/planner-simulation.test.mjs
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const bar = readFileSync(new URL('../src/components/wayline-planner/SimulationBar.vue', import.meta.url), 'utf8')
const overlays = readFileSync(new URL('../src/hooks/use-planner-overlays.ts', import.meta.url), 'utf8')
const toolbar = readFileSync(new URL('../src/components/wayline-planner/PlannerToolbar.vue', import.meta.url), 'utf8')

test('simulation: 时间轴驱动 + 倍速 + 执行互斥', () => {
  assert.match(bar, /buildSimulationTimeline/)
  assert.match(bar, /requestAnimationFrame/)
  assert.match(bar, /simulationSpeedX/)
  assert.match(overlays, /positionAtTime/)
  assert.match(overlays, /stopSimulation/)
  assert.match(toolbar, /executing/)
})
```

Run: `node frontend/scripts/planner-simulation.test.mjs && cd frontend && npm run build 2>&1 | tail -5`
冒烟：3 航点（一个带悬停动作）→ 预演：匀速飞行、悬停点停留、倍速生效、拖进度条跳转、退出清理幻影。

```bash
git add -A frontend
git commit -m "feat(planner): 2D 模拟预演（幻影飞机+播放控制条）"
```

### Task 17: 回归收尾（测试修订 + lint + build + 全量验证）

**Files:**
- Modify: 受影响的既有测试（`frontend/scripts/fc100-delivery-ui.test.mjs`、`frontend/src/pages/page-web/projects/__tests__/wayline-aircraft-position.test.mjs` 等——以实际红掉的为准）

- [ ] **Step 1: 全量跑前端测试，列出红名单**

Run: `for f in frontend/scripts/*.test.mjs frontend/src/pages/page-web/projects/__tests__/*.test.mjs frontend/src/components/cockpit/__tests__/*.test.mjs; do echo "== $f"; node "$f" > /dev/null 2>&1 && echo PASS || echo FAIL; done`
Expected: 列出 FAIL 清单。逐个判断：**断言 DOM/源文件位置的**→ 把 `read(...)` 路径与正则指向新组件文件，断言数量不得减少；**断言 store/API 契约的**→ 必须不改测试就绿，红了说明重构破坏了契约，回去修实现。

- [ ] **Step 2: 后端全量测试**

Run: `cd backend && JAVA_HOME=/usr/local/opt/openjdk@11 mvn -pl uavfire test 2>&1 | tail -15`
Expected: 全绿（含 Part A 新增的 3 个测试类）

- [ ] **Step 3: lint + build**

Run: `cd frontend && npm run lint && npm run build 2>&1 | tail -5`
Expected: 0 error（warning 与现状持平）；build 成功

- [ ] **Step 4: 端到端冒烟清单（手动，对照设计验收）**

- 进入航线页：默认卫星+路网、深色面板、页签可切
- 点选加点→信息牌航点+绿线箭头+距离标签；拖拽/插点/删点
- 统计条数值正确（手算一条 2 点航线验证）
- 剖面：绿线+地形线（或降级提示）
- 预演完整流程
- 保存→列表出现→下发执行入口可用；执行中：编辑禁用、监控显示、轨迹绘制
- 投放页签：FC100 设备列表/任务全流程可用；切回监测页签飞机仍可见

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "test(planner): 重构后测试修订与全量回归"
```

---

## 验收对照（计划自检）

| 设计要求 | 落点 |
|---|---|
| 卫星底图+路网+深色 UI | Task 11 + 各组件样式 |
| 三段式布局+页签 | Task 7/8 |
| 航点列表+参数抽屉 | Task 8/9 |
| 任务级参数区 | Task 9 |
| 统计胶囊 | Task 10 + Task 5 |
| S2 信息牌/距离标签/H 分模式/箭头 | Task 13 |
| 拖拽/插点/删点 | Task 14 |
| 高度剖面（DEM+降级+双基准） | Task 1-4 + 15 |
| 2D 模拟预演 | Task 5 + 16 |
| Monitor/断点续飞保留 | Task 8 |
| FC100 平移边界 | Task 7 |
| 飞机跨页签常显 | Task 12 |
| 回归保护分类 | Task 17 |
