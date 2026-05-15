package com.yx.uavfire.fc100.deliverysync.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yx.uavfire.fc100.deliverysync.DeliverySyncException;
import com.yx.uavfire.fc100.deliverysync.config.DeliverySyncProperties;
import com.yx.uavfire.fc100.deliverysync.config.DeliverySyncSecretsValidator;
import com.yx.uavfire.fc100.deliverysync.service.DeliverySyncLogService;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * spec §5.7 — HTTP 模式下所有 Delivery Sync 请求的底层客户端。
 * 签名格式：HmacSHA256(AK+Method+X-DJI-Timestamp+X-DJI-Nonce, SK)，Base64。
 * 重试：纯 Java 循环，backoff 从 props.retry.backoffMs 读取，无需新依赖。
 */
@Component
@ConditionalOnProperty(prefix = "fc100.delivery-sync", name = "mode", havingValue = "http")
@Slf4j
public class DeliverySyncHttpClient {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final DeliverySyncProperties props;
    private final ObjectMapper objectMapper;
    private final DeliverySyncLogService logService;
    private final OkHttpClient httpClient;

    public DeliverySyncHttpClient(DeliverySyncProperties props,
                                   ObjectMapper objectMapper,
                                   DeliverySyncLogService logService) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.logService = logService;
        this.httpClient = new OkHttpClient.Builder()
            .connectTimeout(props.getConnectTimeoutMs(), TimeUnit.MILLISECONDS)
            .readTimeout(props.getReadTimeoutMs(), TimeUnit.MILLISECONDS)
            .build();
    }

    /**
     * POST request with JSON body.
     *
     * @param path      relative path, e.g. "/v1/tasks"
     * @param body      request body object (serialized to JSON)
     * @param respType  response deserialization type
     * @param idemKey   X-Idempotency-Key header value; null to omit
     * @param missionNo for logging correlation
     */
    public <T> T post(String path, Object body, Class<T> respType,
                      String idemKey, String missionNo) throws IOException, DeliverySyncException {
        String requestBody = serialize(body);
        return executeWithRetry("POST", path, null, requestBody, respType, idemKey, missionNo);
    }

    /**
     * GET request with optional query parameters.
     *
     * @param path      relative path, e.g. "/v1/devices"
     * @param query     query params; null or empty for none
     * @param respType  response deserialization type
     * @param idemKey   X-Idempotency-Key header value; null to omit
     * @param missionNo for logging correlation
     */
    public <T> T get(String path, Map<String, String> query, Class<T> respType,
                     String idemKey, String missionNo) throws IOException, DeliverySyncException {
        return executeWithRetry("GET", path, query, "", respType, idemKey, missionNo);
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    private <T> T executeWithRetry(String method, String path, Map<String, String> query,
                                    String requestBody, Class<T> respType,
                                    String idemKey, String missionNo)
            throws IOException, DeliverySyncException {

        List<Integer> backoffs = props.getRetry().getBackoffMs();
        int maxAttempts = props.getRetry().getMaxAttempts();
        IOException lastIo = null;

        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            if (attempt > 0) {
                int delayMs = attempt <= backoffs.size() ? backoffs.get(attempt - 1) : backoffs.get(backoffs.size() - 1);
                log.warn("DeliverySync retry attempt {}/{} after {}ms; path={}", attempt + 1, maxAttempts, delayMs, path);
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted during retry backoff", ie);
                }
            }

            long t0 = System.currentTimeMillis();
            String timestamp = String.valueOf(System.currentTimeMillis());
            String nonce = UUID.randomUUID().toString().replace("-", "");
            String signature = HmacSha256Signer.sign(props.getAk(), props.getSk(), method, timestamp, nonce);

            Request request = buildRequest(method, path, query, requestBody, timestamp, nonce, signature, idemKey);
            String urlStr = request.url().toString();

            try (Response response = httpClient.newCall(request).execute()) {
                int code = response.code();
                String respBody = response.body() != null ? response.body().string() : "";
                int latencyMs = (int) (System.currentTimeMillis() - t0);

                if (code >= 400) {
                    logService.recordFailure(method + " " + path, null, method, urlStr,
                        requestBody, "HTTP " + code + ": " + respBody, idemKey, latencyMs);
                    // 4xx are not retried — throw immediately
                    throw new DeliverySyncException(code, respBody);
                }

                logService.recordSuccess(method + " " + path, null, method, urlStr,
                    requestBody, code, respBody, idemKey, latencyMs);
                return objectMapper.readValue(respBody, respType);

            } catch (DeliverySyncException e) {
                // 4xx: re-throw immediately, no retry
                throw e;
            } catch (IOException e) {
                int latencyMs = (int) (System.currentTimeMillis() - t0);
                log.warn("DeliverySync IO error attempt {}/{}: {}", attempt + 1, maxAttempts, e.getMessage());
                logService.recordFailure(method + " " + path, null, method, urlStr,
                    requestBody, e.getMessage(), idemKey, latencyMs);
                lastIo = e;
                // 5xx or IO: retry
            }
        }
        throw lastIo != null ? lastIo : new IOException("DeliverySync request failed after " + maxAttempts + " attempts");
    }

    private Request buildRequest(String method, String path, Map<String, String> query,
                                  String requestBody, String timestamp, String nonce,
                                  String signature, String idemKey) {
        String baseUrl = props.getBaseUrl();
        HttpUrl.Builder urlBuilder = HttpUrl.parse(baseUrl + path).newBuilder();
        if (query != null) {
            query.forEach(urlBuilder::addQueryParameter);
        }

        Request.Builder rb = new Request.Builder()
            .url(urlBuilder.build())
            .header("Content-Type", "application/json")
            .header("X-DJI-AK", props.getAk())
            .header("X-DJI-Timestamp", timestamp)
            .header("X-DJI-Nonce", nonce)
            .header("X-DJI-Signature", signature);

        if (idemKey != null && !idemKey.isBlank()) {
            rb.header("X-Idempotency-Key", idemKey);
        }

        if ("POST".equals(method)) {
            rb.post(RequestBody.create(requestBody, JSON));
        } else {
            rb.get();
        }

        return rb.build();
    }

    private String serialize(Object obj) {
        if (obj == null) return "{}";
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize request body", e);
        }
    }

    /** Expose AK fingerprint for external logging if needed. */
    public String akFingerprint() {
        return DeliverySyncSecretsValidator.fingerprint(props.getAk());
    }
}
