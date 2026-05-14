package com.dji.sample.manage.service.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

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
        Path fakeFfmpeg = tempDir.resolve("fake-ffmpeg.sh");
        Files.writeString(fakeFfmpeg,
                "#!/bin/sh\n" +
                        "printf x >> '" + counter + "'\n" +
                        "exit 1\n",
                StandardCharsets.UTF_8);
        assertTrue(fakeFfmpeg.toFile().setExecutable(true));

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
}
