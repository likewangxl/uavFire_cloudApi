package com.yx.uavfire.manage.service.impl;

import com.yx.uavfire.common.config.CaptchaConfig;
import com.yx.uavfire.manage.model.dto.CaptchaDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CaptchaServiceImplTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;

    @InjectMocks private CaptchaServiceImpl service;

    @BeforeEach
    void setup() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    @Test
    void generate_returnsTokenAndBase64Image_andWritesRedisWithTtl() {
        CaptchaDTO dto = service.generate();

        assertNotNull(dto.getToken());
        assertTrue(dto.getToken().length() >= 16, "token should be uuid-like");
        assertNotNull(dto.getImageBase64());
        assertTrue(dto.getImageBase64().length() > 100, "base64 PNG should be non-trivial");

        verify(valueOps).set(
            argThat((String k) -> k.startsWith(CaptchaConfig.REDIS_KEY_PREFIX)),
            argThat((String v) -> Pattern.compile("^[" + CaptchaConfig.CHARSET + "]{" + CaptchaConfig.LENGTH + "}$").matcher(v).matches()),
            eq(CaptchaConfig.TTL_SECONDS),
            eq(TimeUnit.SECONDS)
        );
    }

    @Test
    void verifyAndConsume_returnsTrue_whenMatchCaseInsensitive_andConsumesAtomically() {
        when(redisTemplate.execute(any(DefaultRedisScript.class), eq(Collections.singletonList("captcha:tok1"))))
            .thenReturn("ABCD");

        assertTrue(service.verifyAndConsume("tok1", "abcd"));

        verify(redisTemplate).execute(any(DefaultRedisScript.class), eq(Collections.singletonList("captcha:tok1")));
    }

    @Test
    void verifyAndConsume_returnsFalse_whenTokenMissing() {
        when(redisTemplate.execute(any(DefaultRedisScript.class), eq(Collections.singletonList("captcha:gone"))))
            .thenReturn(null);

        assertFalse(service.verifyAndConsume("gone", "ABCD"));
    }

    @Test
    void verifyAndConsume_returnsFalse_whenMismatch_andStillConsumes() {
        when(redisTemplate.execute(any(DefaultRedisScript.class), eq(Collections.singletonList("captcha:tok2"))))
            .thenReturn("ABCD");

        assertFalse(service.verifyAndConsume("tok2", "WXYZ"));

        verify(redisTemplate).execute(any(DefaultRedisScript.class), eq(Collections.singletonList("captcha:tok2")));
    }

    @Test
    void verifyAndConsume_returnsFalse_whenTokenIsNull_withoutTouchingRedis() {
        assertFalse(service.verifyAndConsume(null, "ABCD"));

        verifyNoInteractions(redisTemplate);
    }

    @Test
    void verifyAndConsume_returnsFalse_whenUserInputIsNull_withoutTouchingRedis() {
        assertFalse(service.verifyAndConsume("tok", null));

        verifyNoInteractions(redisTemplate);
    }
}
