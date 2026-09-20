package com.yx.uavfire.miniapp.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yx.uavfire.miniapp.web.*;
import org.junit.jupiter.api.Test;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import static org.junit.jupiter.api.Assertions.*;
class MiniAppWechatGatewayTest {
    ObjectMapper mapper=new ObjectMapper();
    @Test void successfulResponseReturnsOnlyOpenId() throws Exception {
        assertEquals("wx-user",WechatHttpGateway.readOpenId(mapper.readTree("{\"openid\":\"wx-user\",\"session_key\":\"never-return\",\"unionid\":\"private\"}")));
    }
    @Test void upstreamErrorsAreSanitized() throws Exception {
        MiniAppException invalid=assertThrows(MiniAppException.class,()->WechatHttpGateway.readOpenId(mapper.readTree("{\"errcode\":40029,\"errmsg\":\"secret request url\"}")));
        assertEquals(MiniAppErrorCode.WECHAT_CODE_INVALID,invalid.getCode());assertFalse(invalid.getMessage().contains("secret"));
        assertThrows(MiniAppException.class,()->WechatHttpGateway.readOpenId(mapper.readTree("{}")));
        assertEquals(MiniAppErrorCode.RATE_LIMITED,assertThrows(MiniAppException.class,()->WechatHttpGateway.readOpenId(mapper.readTree("{\"errcode\":45011}"))).getCode());
    }
    @Test void diagnosticsRecordNumericCauseAndRequestIdWithoutCredentialsOrUpstreamText() throws Exception {
        Logger logger=(Logger) LoggerFactory.getLogger(WechatHttpGateway.class);
        ListAppender<ILoggingEvent> appender=new ListAppender<>();appender.start();logger.addAppender(appender);
        MockHttpServletRequest request=new MockHttpServletRequest();request.addHeader("X-Request-Id","login-check-123");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            assertThrows(MiniAppException.class,()->WechatHttpGateway.readOpenId(mapper.readTree("{\"errcode\":40226,\"errmsg\":\"private-upstream-message\",\"openid\":\"private-identity\",\"session_key\":\"private-session-key\"}")));
            String message=appender.list.get(0).getFormattedMessage();
            assertTrue(message.contains("requestId=login-check-123"));assertTrue(message.contains("upstreamCode=40226"));
            assertFalse(message.contains("private-"));assertNull(appender.list.get(0).getThrowableProxy());
        } finally {RequestContextHolder.resetRequestAttributes();logger.detachAppender(appender);appender.stop();}
    }
}
