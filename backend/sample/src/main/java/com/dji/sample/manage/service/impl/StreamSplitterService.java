package com.dji.sample.manage.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class StreamSplitterService implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(StreamSplitterService.class);

    private final Map<String, SplittableProcess> activeSplits = new ConcurrentHashMap<>();
    private final Map<String, Long> failedSplitAt = new ConcurrentHashMap<>();

    @Value("${ffmpeg.binary-path:ffmpeg}")
    private String ffmpegBinary;

    @Value("${zlm.rtmp-host:localhost}")
    private String zlmRtmpHost;

    @Value("${zlm.rtmp-port:1935}")
    private int zlmRtmpPort;

    @Value("${ffmpeg.retry-cooldown-millis:30000}")
    private long retryCooldownMillis;

    public String startSplit(String droneSn, String sourceStreamId) {
        Long lastFailureAt = failedSplitAt.get(droneSn);
        long now = System.currentTimeMillis();
        if (lastFailureAt != null) {
            long elapsed = now - lastFailureAt;
            if (elapsed < retryCooldownMillis) {
                log.warn("Skipping FFmpeg split restart for drone {} for {} ms after previous failure",
                        droneSn, retryCooldownMillis - elapsed);
                return null;
            }
            failedSplitAt.remove(droneSn);
        }

        SplittableProcess existing = activeSplits.get(droneSn);
        if (existing != null) {
            if (existing.process.isAlive()) {
                return getThermalStreamId(sourceStreamId);
            }
            log.warn("Dead FFmpeg detected for drone {}, waiting for retry cooldown", droneSn);
            activeSplits.remove(droneSn);
            failedSplitAt.put(droneSn, now);
            return null;
        }

        String inputUrl = String.format("rtmp://%s:%d/live/%s", zlmRtmpHost, zlmRtmpPort, sourceStreamId);
        String thermalOutputUrl = String.format("rtmp://%s:%d/live/%s-thermal", zlmRtmpHost, zlmRtmpPort, sourceStreamId);
        String visibleOutputUrl = String.format("rtmp://%s:%d/live/%s-visible", zlmRtmpHost, zlmRtmpPort, sourceStreamId);

        Process process = launchFfmpeg(droneSn, inputUrl, visibleOutputUrl, thermalOutputUrl);
        if (process == null) {
            failedSplitAt.put(droneSn, now);
            return null;
        }

        SplittableProcess fresh = new SplittableProcess(process, System.currentTimeMillis());
        SplittableProcess race = activeSplits.putIfAbsent(droneSn, fresh);
        if (race != null) {
            process.destroy();
            return getThermalStreamId(sourceStreamId);
        }

        log.info("Started FFmpeg split for drone {}: {} -> {}, {}",
                droneSn, sourceStreamId,
                visibleOutputUrl, thermalOutputUrl);
        return getThermalStreamId(sourceStreamId);
    }

    private Process launchFfmpeg(String droneSn, String inputUrl,
                                  String visibleOutputUrl, String thermalOutputUrl) {
        ProcessBuilder pb = new ProcessBuilder(
                ffmpegBinary,
                "-loglevel", "warning",
                "-i", inputUrl,
                "-filter_complex",
                "[0:v]crop=iw/2:ih:0:0[t];[0:v]crop=iw/2:ih:iw/2:0[v]",
                "-map", "[t]", "-c:v", "libx264", "-preset", "ultrafast", "-tune", "zerolatency",
                "-crf", "23", "-f", "flv", thermalOutputUrl,
                "-map", "[v]", "-c:v", "libx264", "-preset", "ultrafast", "-tune", "zerolatency",
                "-crf", "23", "-f", "flv", visibleOutputUrl
        );

        pb.redirectErrorStream(true);
        pb.redirectOutput(new File(System.getProperty("java.io.tmpdir"),
                "ffmpeg-split-" + droneSn + ".log"));

        try {
            return pb.start();
        } catch (Exception e) {
            log.error("Failed to launch FFmpeg split for drone {}", droneSn, e);
            return null;
        }
    }

    public void stopSplit(String droneSn) {
        SplittableProcess entry = activeSplits.remove(droneSn);
        failedSplitAt.remove(droneSn);
        if (entry == null) {
            return;
        }
        Process process = entry.process;
        if (process != null && process.isAlive()) {
            process.destroy();
            long elapsed = System.currentTimeMillis() - entry.startedAt;
            log.info("Stopped FFmpeg split for drone {} (ran {} ms)", droneSn, elapsed);
        }
    }

    public boolean isSplitting(String droneSn) {
        SplittableProcess entry = activeSplits.get(droneSn);
        if (entry == null) {
            return false;
        }
        if (!entry.process.isAlive()) {
            log.warn("FFmpeg split for drone {} died unexpectedly, exit value: {}",
                    droneSn, entry.process.exitValue());
            activeSplits.remove(droneSn);
            failedSplitAt.put(droneSn, System.currentTimeMillis());
            return false;
        }
        return true;
    }

    public static String getThermalStreamId(String sourceStreamId) {
        return sourceStreamId + "-thermal";
    }

    public static String getVisibleStreamId(String sourceStreamId) {
        return sourceStreamId + "-visible";
    }

    @Override
    public void destroy() {
        failedSplitAt.clear();
        if (activeSplits.isEmpty()) {
            return;
        }
        log.info("Shutting down {} active FFmpeg split processes", activeSplits.size());
        for (Map.Entry<String, SplittableProcess> entry : activeSplits.entrySet()) {
            Process process = entry.getValue().process;
            if (process.isAlive()) {
                process.destroy();
            }
        }
        activeSplits.clear();
    }

    private static final class SplittableProcess {
        final Process process;
        final long startedAt;

        SplittableProcess(Process process, long startedAt) {
            this.process = process;
            this.startedAt = startedAt;
        }
    }
}
