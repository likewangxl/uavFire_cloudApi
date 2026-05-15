package com.yx.uavfire.fc100.deliverysync;

/**
 * DJI Delivery Sync API 返回 4xx/5xx 时抛出。
 * 由 HttpDeliverySyncAdapter 捕获后转为 Fc100BusinessException。
 */
public class DeliverySyncException extends Exception {

    private final int httpCode;
    private final String body;

    public DeliverySyncException(int httpCode, String body) {
        super("DeliverySync API error: HTTP " + httpCode + " body=" + body);
        this.httpCode = httpCode;
        this.body = body;
    }

    public int getHttpCode() { return httpCode; }
    public String getBody() { return body; }
}
