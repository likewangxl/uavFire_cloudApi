package com.yx.uavfire.manage.service.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamSplitterServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void startSplit_doesNotImmediatelyRelaunchAfterProcessDies() throws Exception {
        Path counter = tempDir.resolve("ffmpeg-count.txt");
        Path fakeFfmpeg = writeFakeFfmpeg(counter);

        StreamSplitterService service = new StreamSplitterService();
        ReflectionTestUtils.setField(service, "ffmpegBinary", fakeFfmpeg.toString());
        ReflectionTestUtils.setField(service, "retryCooldownMillis", 30_000L);

        service.startSplit("DRONE-001", "DRONE-001-0");
        Thread.sleep(200);
        service.startSplit("DRONE-001", "DRONE-001-0");
        Thread.sleep(200);
        service.startSplit("DRONE-001", "DRONE-001-0");
        Thread.sleep(200);

        assertEquals(1, Files.readString(counter, StandardCharsets.UTF_8).length());
    }

    /** ffmpeg 假桩：向 counter 追加一个 x（不带换行）后以非 0 退出。Windows 用 .cmd，其余用 .sh。 */
    private Path writeFakeFfmpeg(Path counter) throws IOException {
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        if (windows) {
            Path script = tempDir.resolve("fake-ffmpeg.cmd");
            Files.writeString(script,
                    "@echo off\r\n" +
                            ">>\"" + counter + "\" <nul set /p dummy=x\r\n" +
                            "exit /b 1\r\n",
                    StandardCharsets.UTF_8);
            return script;
        }
        Path script = tempDir.resolve("fake-ffmpeg.sh");
        Files.writeString(script,
                "#!/bin/sh\n" +
                        "printf x >> '" + counter + "'\n" +
                        "exit 1\n",
                StandardCharsets.UTF_8);
        assertTrue(script.toFile().setExecutable(true));
        return script;
    }
}
