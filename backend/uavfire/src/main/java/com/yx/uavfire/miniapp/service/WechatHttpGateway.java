package com.yx.uavfire.miniapp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yx.uavfire.miniapp.configuration.MiniAppProperties;
import com.yx.uavfire.miniapp.web.MiniAppErrorCode;
import com.yx.uavfire.miniapp.web.MiniAppException;
import com.yx.uavfire.miniapp.web.MiniAppRequestIds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import okhttp3.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import java.util.concurrent.TimeUnit;

@Service
public class WechatHttpGateway implements WechatCodeGateway {
    private static final Logger log = LoggerFactory.getLogger(WechatHttpGateway.class);
    private final MiniAppProperties properties;
    private final ObjectMapper mapper;
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false).build();

    public WechatHttpGateway(MiniAppProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
    }

    @Override
    public String exchange(String code) {
        MiniAppProperties.Wechat wx = properties.getWechat();
        if (!wx.isEnabled() || !StringUtils.hasText(wx.getAppId()) || !StringUtils.hasText(wx.getAppSecret())) {
            recordFailure("CONFIGURATION_MISSING", null, null, null);
            throw unavailable();
        }
        // Fixed official HTTPS endpoint, no redirects, logging interceptor or blind code replay.
        HttpUrl url = HttpUrl.get("https://api.weixin.qq.com/sns/jscode2session").newBuilder()
                .addQueryParameter("appid", wx.getAppId()).addQueryParameter("secret", wx.getAppSecret())
                .addQueryParameter("js_code", code).addQueryParameter("grant_type", "authorization_code").build();
        try (Response response = client.newCall(new Request.Builder().url(url).get().build()).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                recordFailure("HTTP_RESPONSE", null, response.code(), null);
                throw unavailable();
            }
            JsonNode body = mapper.readTree(response.body().string());
            return readOpenId(body);
        } catch (MiniAppException exception) {
            throw exception;
        } catch (Exception exception) {
            // Never retain an upstream exception that may contain the secret-bearing request URL.
            recordFailure("TRANSPORT_OR_JSON", null, null, exception.getClass().getSimpleName());
            throw unavailable();
        }
    }

    static String readOpenId(JsonNode body) {
        if (body == null) {
            recordFailure("EMPTY_RESPONSE", null, null, null);
            throw unavailable();
        }
        int error = body.path("errcode").asInt(0);
        if (error != 0) recordFailure("WECHAT_API_ERROR", error, null, null);
        if (error == 40029 || error == 40163) {
            throw new MiniAppException(HttpStatus.UNAUTHORIZED, MiniAppErrorCode.WECHAT_CODE_INVALID,
                    "微信登录凭证已失效，请重新登录");
        }
        if (error == 45011 || error == 45009) {
            throw new MiniAppException(HttpStatus.TOO_MANY_REQUESTS, MiniAppErrorCode.RATE_LIMITED,
                    "微信登录请求过于频繁，请稍后重试");
        }
        String openId = body.path("openid").asText("");
        if (error != 0) throw unavailable();
        if (openId.isBlank() || openId.length() > 128) {
            recordFailure("MISSING_IDENTITY", null, null, null);
            throw unavailable();
        }
        return openId;
    }

    private static void recordFailure(String reason, Integer upstreamCode, Integer httpStatus, String exceptionType) {
        String requestId = "none";
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes) {
            requestId = MiniAppRequestIds.resolve(((ServletRequestAttributes) RequestContextHolder.getRequestAttributes()).getRequest());
        }
        // Only controlled categories, numeric status and safe request ID. No URL, response body, OpenID or throwable.
        log.warn("WeChat code exchange failed: requestId={}, reason={}, upstreamCode={}, httpStatus={}, exceptionType={}",
                requestId, reason, upstreamCode, httpStatus, exceptionType);
    }

    private static MiniAppException unavailable() {
        return new MiniAppException(HttpStatus.SERVICE_UNAVAILABLE, MiniAppErrorCode.WECHAT_API_UNAVAILABLE,
                "微信登录服务暂不可用，请检查服务端配置或稍后重试");
    }
}
