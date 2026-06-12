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
