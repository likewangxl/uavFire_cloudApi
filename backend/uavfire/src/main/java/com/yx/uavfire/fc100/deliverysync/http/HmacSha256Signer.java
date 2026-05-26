package com.yx.uavfire.fc100.deliverysync.http;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * spec §0.1 + Apifox 文档：
 *   HmacSHA256("AK+Method+X-DJI-Timestamp+X-DJI-Nonce", SK)，hex 编码。
 * Webhook 验签格式不同：参考 spec 附录 A。
 */
public final class HmacSha256Signer {

    private HmacSha256Signer() {}

    public static String sign(String ak, String sk, String method,
                               String timestamp, String nonce) {
        String content = ak + method + timestamp + nonce;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(sk.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] bytes = mac.doFinal(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("HMAC-SHA256 sign failed", e);
        }
    }
}
