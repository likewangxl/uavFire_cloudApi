package com.yx.uavfire.firedetection;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
public class AiServiceClient {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final RequestBody EMPTY_JSON = RequestBody.create("{}", JSON);

    private final OkHttpClient okHttpClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;

    public AiServiceClient(OkHttpClient okHttpClient,
                           ObjectMapper objectMapper,
                           @Value("${ai-service.base-url:http://127.0.0.1:9000}") String baseUrl) {
        this.okHttpClient = okHttpClient;
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /** 幂等启动一个 dual-stream fire-detection 任务。已存在的同 task_id 任务会被复用。 */
    public boolean startDetection(String taskId, String droneSn, String visibleStreamUrl, String thermalStreamUrl) {
        if (!StringUtils.hasText(taskId) || !StringUtils.hasText(droneSn) || !StringUtils.hasText(visibleStreamUrl)) {
            log.warn("ai-service start skipped, missing fields: task={} drone={} url={}", taskId, droneSn, visibleStreamUrl);
            return false;
        }
        Map<String, Object> body = new HashMap<>();
        body.put("task_id", taskId);
        body.put("drone_sn", droneSn);
        body.put("visible_stream_url", visibleStreamUrl);
        body.put("thermal_stream_url", thermalStreamUrl == null ? "" : thermalStreamUrl);
        try {
            String json = objectMapper.writeValueAsString(body);
            Request create = new Request.Builder()
                    .url(baseUrl + "/api/v1/dual-stream/tasks")
                    .post(RequestBody.create(json, JSON))
                    .build();
            try (Response resp = okHttpClient.newCall(create).execute()) {
                // 201 created OK；409 / 400 (已存在) 也视作可继续 start
                if (!resp.isSuccessful() && resp.code() != 409 && resp.code() != 400) {
                    log.warn("ai-service create task failed code={} task={}", resp.code(), taskId);
                    return false;
                }
            }
            Request start = new Request.Builder()
                    .url(baseUrl + "/api/v1/dual-stream/tasks/" + taskId + "/start")
                    .post(EMPTY_JSON)
                    .build();
            try (Response resp = okHttpClient.newCall(start).execute()) {
                if (!resp.isSuccessful()) {
                    log.warn("ai-service start task failed code={} task={}", resp.code(), taskId);
                    return false;
                }
                log.info("ai-service detection started task={} drone={} url={}", taskId, droneSn, visibleStreamUrl);
                return true;
            }
        } catch (Exception ex) {
            log.warn("ai-service start exception task={}: {}", taskId, ex.getMessage());
            return false;
        }
    }

    public boolean stopDetection(String taskId) {
        if (!StringUtils.hasText(taskId)) {
            return false;
        }
        try {
            Request stop = new Request.Builder()
                    .url(baseUrl + "/api/v1/dual-stream/tasks/" + taskId + "/stop")
                    .post(EMPTY_JSON)
                    .build();
            try (Response resp = okHttpClient.newCall(stop).execute()) {
                if (!resp.isSuccessful() && resp.code() != 404) {
                    log.warn("ai-service stop task failed code={} task={}", resp.code(), taskId);
                    return false;
                }
                log.info("ai-service detection stopped task={}", taskId);
                return true;
            }
        } catch (Exception ex) {
            log.warn("ai-service stop exception task={}: {}", taskId, ex.getMessage());
            return false;
        }
    }

    /** 根据 videoId (deviceSn/cameraIndex/videoIndex) 推导 ZLM RTSP 拉流 URL。 */
    public static String rtspUrlForVideoId(String videoId, String zlmHost, int rtspPort) {
        if (!StringUtils.hasText(videoId)) return "";
        String streamId = videoId.replace('/', '-');
        return "rtsp://" + zlmHost + ":" + rtspPort + "/live/" + streamId;
    }

    /** 主相机默认 video id 约定: <droneSn>/89-0-0 (M4 系列可见光) */
    public static String defaultVideoIdForDrone(String droneSn) {
        if (!StringUtils.hasText(droneSn)) return "";
        return droneSn + "/89-0-0";
    }

    public String fireTaskIdForDrone(String droneSn) {
        return "fire-" + droneSn;
    }
}
