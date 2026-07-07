package com.yx.uavfire.fc100.event.service.impl;

import org.junit.jupiter.api.AfterEach;
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

    /** Windows 上 mmap 不解除则 .hgt 文件被锁，@TempDir 清理会失败。 */
    @AfterEach
    void unmapTiles() {
        service.close();
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
