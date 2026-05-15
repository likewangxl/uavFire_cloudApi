# 登录页重做实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 Web 登录页 (`/project`) 替换为深色玻璃质感登录卡 + 全屏背景图,加图形验证码 + 演示模式 + 记住我 + 忘记密码。

**Architecture:** 后端新增 captcha controller/service 与 demo-login endpoint,Redis 一次性消费 captcha;前端拆 `LoginPage` + `LoginForm` + `CaptchaImage` + `ForgotPasswordModal` + `use-login` 组合,原 `pages/page-web/index.vue` 收缩为容器。

**Tech Stack:** 前端 Vue 3 + Vite + TS + Ant Design Vue;后端 Spring Boot 2.7 + MyBatis + Redis + JWT;测试前端用 `node:test` `.test.mjs` 静态契约 + 可 import 纯 JS,后端用 JUnit + MockMvc。

**Spec:** `docs/superpowers/specs/2026-05-15-login-page-redesign-design.md`

---

## File Structure

### Backend (`backend/uavfire/src/main/java/com/yx/uavfire/`)

| 路径 | 动作 | 职责 |
|---|---|---|
| `manage/model/dto/UserLoginDTO.java` | modify | 加 `captcha` + `captchaToken` 两字段 |
| `manage/model/dto/CaptchaDTO.java` | create | 验证码响应 `{token, imageBase64}` |
| `common/config/CaptchaConfig.java` | create | 字符集/长度/TTL 常量(`@ConfigurationProperties` or `@Component`) |
| `manage/service/ICaptchaService.java` | create | `generate()`, `verify(token, value)` |
| `manage/service/impl/CaptchaServiceImpl.java` | create | ImageIO 画图 + Redis SETEX 60s |
| `manage/controller/CaptchaController.java` | create | `GET /manage/api/v1/captcha` |
| `manage/service/IUserService.java` | modify | `userLogin` 签名加 2 参数,新增 `demoLogin()` |
| `manage/service/impl/UserServiceImpl.java` | modify | userLogin 头部加 captcha 校验;新增 demoLogin |
| `manage/controller/LoginController.java` | modify | 调用新 service 签名;加 `POST /demo-login` |
| `src/test/.../CaptchaServiceImplTest.java` | create | 生成/校验单测 |
| `src/test/.../CaptchaControllerTest.java` | create | MockMvc GET |
| `src/test/.../UserServiceImplLoginTest.java` | create | userLogin 4 个 captcha 分支 + demoLogin |

### Frontend (`frontend/src/`)

| 路径 | 动作 | 职责 |
|---|---|---|
| `types/index.ts` | modify | `ELocalStorageKey` 加 `RememberUsername` |
| `api/manage.ts` | modify | `LoginBody` 加 `captcha` + `captchaToken` |
| `api/captcha.ts` | create | `getCaptcha()` / `demoLogin()` |
| `pages/page-web/login/LoginPage.vue` | create | 整页布局 + 入场动画 + 背景图 |
| `pages/page-web/login/components/LoginForm.vue` | create | 表单 + 两个按钮;emit `submit` / `demo` |
| `pages/page-web/login/components/CaptchaImage.vue` | create | base64 img + 点击刷新;defineExpose `refresh()` |
| `pages/page-web/login/components/ForgotPasswordModal.vue` | create | 固定文案 Modal,`v-model:open` |
| `pages/page-web/login/composables/use-login.ts` | create | `onLogin` / `onDemoLogin` / 记住我读写 |
| `pages/page-web/index.vue` | rewrite | 仅 `<LoginPage/>` 容器 |
| `assets/login-bg.png` | create (user) | Image #1 整张合成图 |
| `scripts/login-page-redesign.test.mjs` | create | 静态契约:文件存在 + 关键代码模式 |

---

## Task 1: 后端 — `UserLoginDTO` 加 captcha 字段

**Files:**
- Modify: `backend/uavfire/src/main/java/com/yx/uavfire/manage/model/dto/UserLoginDTO.java`

- [ ] **Step 1: 改 DTO 加 2 字段**

把当前文件改为:

```java
package com.yx.uavfire.manage.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.NonNull;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class UserLoginDTO {

    @NonNull
    private String username;

    @NonNull
    private String password;

    @NonNull
    private Integer flag;

    private String captcha;

    private String captchaToken;
}
```

不要标 `@NonNull` —— 让 demo 路径与未来兼容,校验留在 service 层。

- [ ] **Step 2: 编译验证**

```bash
cd D:/uavfire/uavFire_cloudApi/backend
JAVA_HOME="C:/Program Files/Java/jdk-11" mvn -pl uavfire -DskipTests compile
```

Expected: `BUILD SUCCESS`

- [ ] **Step 3: Commit**

```bash
cd D:/uavfire/uavFire_cloudApi
git add backend/uavfire/src/main/java/com/yx/uavfire/manage/model/dto/UserLoginDTO.java
git commit -m "feat(login): extend UserLoginDTO with captcha + captchaToken fields"
```

---

## Task 2: 后端 — `CaptchaDTO` 响应模型

**Files:**
- Create: `backend/uavfire/src/main/java/com/yx/uavfire/manage/model/dto/CaptchaDTO.java`

- [ ] **Step 1: 新建 DTO**

```java
package com.yx.uavfire.manage.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class CaptchaDTO {

    private String token;

    private String imageBase64;
}
```

- [ ] **Step 2: 编译**

```bash
cd D:/uavfire/uavFire_cloudApi/backend
JAVA_HOME="C:/Program Files/Java/jdk-11" mvn -pl uavfire -DskipTests compile
```

Expected: `BUILD SUCCESS`

- [ ] **Step 3: Commit**

```bash
cd D:/uavfire/uavFire_cloudApi
git add backend/uavfire/src/main/java/com/yx/uavfire/manage/model/dto/CaptchaDTO.java
git commit -m "feat(login): add CaptchaDTO response model"
```

---

## Task 3: 后端 — `CaptchaConfig` 常量与配置

**Files:**
- Create: `backend/uavfire/src/main/java/com/yx/uavfire/common/config/CaptchaConfig.java`

- [ ] **Step 1: 新建 config**

```java
package com.yx.uavfire.common.config;

import org.springframework.context.annotation.Configuration;

@Configuration
public class CaptchaConfig {

    /** 字符集:排除易混 0/O/1/I/L */
    public static final String CHARSET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";

    /** 长度 */
    public static final int LENGTH = 4;

    /** Redis TTL 秒 */
    public static final long TTL_SECONDS = 60L;

    /** Redis key 前缀 */
    public static final String REDIS_KEY_PREFIX = "captcha:";

    /** 图片宽 */
    public static final int IMAGE_WIDTH = 120;

    /** 图片高 */
    public static final int IMAGE_HEIGHT = 40;
}
```

- [ ] **Step 2: 编译**

```bash
cd D:/uavfire/uavFire_cloudApi/backend
JAVA_HOME="C:/Program Files/Java/jdk-11" mvn -pl uavfire -DskipTests compile
```

Expected: `BUILD SUCCESS`

- [ ] **Step 3: Commit**

```bash
cd D:/uavfire/uavFire_cloudApi
git add backend/uavfire/src/main/java/com/yx/uavfire/common/config/CaptchaConfig.java
git commit -m "feat(login): add CaptchaConfig with charset/length/ttl constants"
```

---

## Task 4: 后端 — `ICaptchaService` 接口

**Files:**
- Create: `backend/uavfire/src/main/java/com/yx/uavfire/manage/service/ICaptchaService.java`

- [ ] **Step 1: 新建接口**

```java
package com.yx.uavfire.manage.service;

import com.yx.uavfire.manage.model.dto.CaptchaDTO;

public interface ICaptchaService {

    /** 生成新验证码,写入 Redis(TTL 见 CaptchaConfig),返回 token + base64 图片 */
    CaptchaDTO generate();

    /** 校验。命中则 DEL(一次性消费),返回 true;未命中或过期返回 false。大小写不敏感 */
    boolean verifyAndConsume(String token, String userInput);
}
```

- [ ] **Step 2: 编译**

```bash
cd D:/uavfire/uavFire_cloudApi/backend
JAVA_HOME="C:/Program Files/Java/jdk-11" mvn -pl uavfire -DskipTests compile
```

Expected: `BUILD SUCCESS`

- [ ] **Step 3: Commit**

```bash
cd D:/uavfire/uavFire_cloudApi
git add backend/uavfire/src/main/java/com/yx/uavfire/manage/service/ICaptchaService.java
git commit -m "feat(login): add ICaptchaService interface"
```

---

## Task 5: 后端 — TDD `CaptchaServiceImpl`

**Files:**
- Test: `backend/uavfire/src/test/java/com/yx/uavfire/manage/service/impl/CaptchaServiceImplTest.java`
- Create: `backend/uavfire/src/main/java/com/yx/uavfire/manage/service/impl/CaptchaServiceImpl.java`

- [ ] **Step 1: 写失败的测试**

```java
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
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
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
    void verifyAndConsume_returnsTrueAndDeletes_whenMatchCaseInsensitive() {
        when(valueOps.get("captcha:tok1")).thenReturn("ABCD");

        assertTrue(service.verifyAndConsume("tok1", "abcd"));

        verify(redisTemplate).delete("captcha:tok1");
    }

    @Test
    void verifyAndConsume_returnsFalse_whenTokenMissing() {
        when(valueOps.get("captcha:gone")).thenReturn(null);

        assertFalse(service.verifyAndConsume("gone", "ABCD"));

        verify(redisTemplate, never()).delete(anyString());
    }

    @Test
    void verifyAndConsume_returnsFalse_whenMismatch() {
        when(valueOps.get("captcha:tok2")).thenReturn("ABCD");

        assertFalse(service.verifyAndConsume("tok2", "WXYZ"));

        verify(redisTemplate, never()).delete(anyString());
    }
}
```

- [ ] **Step 2: 运行测试,确认失败**

```bash
cd D:/uavfire/uavFire_cloudApi/backend
JAVA_HOME="C:/Program Files/Java/jdk-11" mvn -pl uavfire test -Dtest=CaptchaServiceImplTest
```

Expected: FAIL — `CaptchaServiceImpl` 类不存在,编译错。

- [ ] **Step 3: 实现 service**

```java
package com.yx.uavfire.manage.service.impl;

import com.yx.uavfire.common.config.CaptchaConfig;
import com.yx.uavfire.manage.model.dto.CaptchaDTO;
import com.yx.uavfire.manage.service.ICaptchaService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class CaptchaServiceImpl implements ICaptchaService {

    private static final SecureRandom RNG = new SecureRandom();

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Override
    public CaptchaDTO generate() {
        String code = randomCode();
        String token = UUID.randomUUID().toString();

        redisTemplate.opsForValue().set(
            CaptchaConfig.REDIS_KEY_PREFIX + token,
            code,
            CaptchaConfig.TTL_SECONDS,
            TimeUnit.SECONDS
        );

        return new CaptchaDTO(token, renderBase64(code));
    }

    @Override
    public boolean verifyAndConsume(String token, String userInput) {
        if (token == null || userInput == null) return false;
        String key = CaptchaConfig.REDIS_KEY_PREFIX + token;
        String stored = redisTemplate.opsForValue().get(key);
        if (stored == null) return false;
        if (!Objects.equals(stored.toUpperCase(), userInput.toUpperCase())) return false;
        redisTemplate.delete(key);
        return true;
    }

    private String randomCode() {
        StringBuilder sb = new StringBuilder(CaptchaConfig.LENGTH);
        for (int i = 0; i < CaptchaConfig.LENGTH; i++) {
            sb.append(CaptchaConfig.CHARSET.charAt(RNG.nextInt(CaptchaConfig.CHARSET.length())));
        }
        return sb.toString();
    }

    private String renderBase64(String code) {
        BufferedImage img = new BufferedImage(CaptchaConfig.IMAGE_WIDTH, CaptchaConfig.IMAGE_HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // 背景
        g.setColor(new Color(245, 248, 252));
        g.fillRect(0, 0, CaptchaConfig.IMAGE_WIDTH, CaptchaConfig.IMAGE_HEIGHT);

        // 干扰线
        for (int i = 0; i < 6; i++) {
            g.setColor(new Color(180 + RNG.nextInt(60), 180 + RNG.nextInt(60), 180 + RNG.nextInt(60)));
            g.drawLine(RNG.nextInt(CaptchaConfig.IMAGE_WIDTH), RNG.nextInt(CaptchaConfig.IMAGE_HEIGHT),
                       RNG.nextInt(CaptchaConfig.IMAGE_WIDTH), RNG.nextInt(CaptchaConfig.IMAGE_HEIGHT));
        }

        // 字符
        g.setFont(new Font("SansSerif", Font.BOLD, 28));
        for (int i = 0; i < code.length(); i++) {
            g.setColor(new Color(20 + RNG.nextInt(80), 50 + RNG.nextInt(100), 120 + RNG.nextInt(80)));
            int x = 12 + i * 25 + RNG.nextInt(6);
            int y = 30 + RNG.nextInt(4);
            g.drawString(String.valueOf(code.charAt(i)), x, y);
        }
        g.dispose();

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(img, "png", out);
            return Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encode captcha image", e);
        }
    }
}
```

- [ ] **Step 4: 跑测试,确认通过**

```bash
cd D:/uavfire/uavFire_cloudApi/backend
JAVA_HOME="C:/Program Files/Java/jdk-11" mvn -pl uavfire test -Dtest=CaptchaServiceImplTest
```

Expected: 4 tests PASS,BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
cd D:/uavfire/uavFire_cloudApi
git add backend/uavfire/src/main/java/com/yx/uavfire/manage/service/impl/CaptchaServiceImpl.java \
        backend/uavfire/src/test/java/com/yx/uavfire/manage/service/impl/CaptchaServiceImplTest.java
git commit -m "feat(login): implement CaptchaServiceImpl with image render + redis TTL + one-shot consume"
```

---

## Task 6: 后端 — TDD `CaptchaController`

**Files:**
- Test: `backend/uavfire/src/test/java/com/yx/uavfire/manage/controller/CaptchaControllerTest.java`
- Create: `backend/uavfire/src/main/java/com/yx/uavfire/manage/controller/CaptchaController.java`

- [ ] **Step 1: 写失败的测试**

```java
package com.yx.uavfire.manage.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yx.uavfire.manage.model.dto.CaptchaDTO;
import com.yx.uavfire.manage.service.ICaptchaService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = CaptchaController.class)
@TestPropertySource(properties = {
    "url.manage.prefix=manage",
    "url.manage.version=/api/v1"
})
class CaptchaControllerTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @MockBean ICaptchaService captchaService;

    @Test
    void getCaptcha_returns200WithTokenAndBase64() throws Exception {
        when(captchaService.generate())
            .thenReturn(new CaptchaDTO("tok-abc", "iVBORw0KGgoAAAA..."));

        mvc.perform(get("/manage/api/v1/captcha"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.code").value(0))
           .andExpect(jsonPath("$.data.token").value("tok-abc"))
           .andExpect(jsonPath("$.data.imageBase64").value("iVBORw0KGgoAAAA..."));
    }
}
```

- [ ] **Step 2: 跑测试,确认失败(编译错)**

```bash
cd D:/uavfire/uavFire_cloudApi/backend
JAVA_HOME="C:/Program Files/Java/jdk-11" mvn -pl uavfire test -Dtest=CaptchaControllerTest
```

Expected: FAIL — `CaptchaController` 不存在

- [ ] **Step 3: 实现 controller**

```java
package com.yx.uavfire.manage.controller;

import com.dji.sdk.common.HttpResultResponse;
import com.yx.uavfire.manage.service.ICaptchaService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("${url.manage.prefix}${url.manage.version}")
public class CaptchaController {

    @Autowired
    private ICaptchaService captchaService;

    @GetMapping("/captcha")
    public HttpResultResponse getCaptcha() {
        return HttpResultResponse.success(captchaService.generate());
    }
}
```

- [ ] **Step 4: 跑测试,确认通过**

```bash
cd D:/uavfire/uavFire_cloudApi/backend
JAVA_HOME="C:/Program Files/Java/jdk-11" mvn -pl uavfire test -Dtest=CaptchaControllerTest
```

Expected: 1 test PASS

- [ ] **Step 5: Commit**

```bash
cd D:/uavfire/uavFire_cloudApi
git add backend/uavfire/src/main/java/com/yx/uavfire/manage/controller/CaptchaController.java \
        backend/uavfire/src/test/java/com/yx/uavfire/manage/controller/CaptchaControllerTest.java
git commit -m "feat(login): add GET /manage/api/v1/captcha endpoint"
```

---

## Task 7: 后端 — `IUserService` 扩签名 + `UserServiceImpl` captcha 校验 + demoLogin

**Files:**
- Modify: `backend/uavfire/src/main/java/com/yx/uavfire/manage/service/IUserService.java`
- Modify: `backend/uavfire/src/main/java/com/yx/uavfire/manage/service/impl/UserServiceImpl.java`
- Modify: `backend/uavfire/src/main/java/com/yx/uavfire/manage/controller/LoginController.java`
- Test: `backend/uavfire/src/test/java/com/yx/uavfire/manage/service/impl/UserServiceImplLoginTest.java`

- [ ] **Step 1: 写失败的测试**

```java
package com.yx.uavfire.manage.service.impl;

// (imports: JUnit, Mockito, UserEntity, UserDTO, WorkspaceDTO, ICaptchaService, IWorkspaceService, MqttPropertyConfiguration, etc.)
// 参考既有的 service test 文件(同 package 下其他 *Test.java)了解 fixture pattern

import com.yx.uavfire.manage.model.dto.WorkspaceDTO;
import com.yx.uavfire.manage.model.entity.UserEntity;
import com.yx.uavfire.manage.service.ICaptchaService;
import com.yx.uavfire.manage.service.IWorkspaceService;
import com.yx.uavfire.manage.dao.IUserMapper;
import com.yx.uavfire.component.mqtt.config.MqttPropertyConfiguration;
import com.dji.sdk.common.HttpResultResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceImplLoginTest {

    @Mock private IUserMapper mapper;
    @Mock private ICaptchaService captchaService;
    @Mock private IWorkspaceService workspaceService;
    @Mock private MqttPropertyConfiguration mqttPropertyConfiguration;

    @InjectMocks private UserServiceImpl service;

    private UserEntity adminPC;

    @BeforeEach
    void setup() {
        adminPC = new UserEntity();
        adminPC.setUsername("adminPC");
        adminPC.setPassword("adminPC");
        adminPC.setUserType(1);
        adminPC.setWorkspaceId("ws-1");
        adminPC.setUserId("uid-1");
    }

    @Test
    void userLogin_failsWhenCaptchaTokenMissing() {
        HttpResultResponse r = service.userLogin("adminPC", "adminPC", 1, "ABCD", null);

        assertEquals(HttpStatus.UNAUTHORIZED.value(), r.getCode());
        verifyNoInteractions(mapper);
    }

    @Test
    void userLogin_failsWhenCaptchaWrong() {
        when(captchaService.verifyAndConsume("tok", "WRONG")).thenReturn(false);

        HttpResultResponse r = service.userLogin("adminPC", "adminPC", 1, "WRONG", "tok");

        assertEquals(HttpStatus.UNAUTHORIZED.value(), r.getCode());
        verifyNoInteractions(mapper);
    }

    @Test
    void userLogin_succeedsAndConsumesCaptcha() {
        when(captchaService.verifyAndConsume("tok", "ABCD")).thenReturn(true);
        when(mapper.selectOne(any())).thenReturn(adminPC);
        when(workspaceService.getWorkspaceByWorkspaceId("ws-1"))
            .thenReturn(Optional.of(new WorkspaceDTO()));
        when(mqttPropertyConfiguration.getMqttBrokerWithBasic())
            .thenReturn(java.util.Map.of("address", "tcp://x:1883"));
        // (依实际 mqtt config getter 调整)

        HttpResultResponse r = service.userLogin("adminPC", "adminPC", 1, "ABCD", "tok");

        assertEquals(0, r.getCode());
        verify(captchaService).verifyAndConsume("tok", "ABCD");
    }

    @Test
    void demoLogin_returnsSuccessForAdminPC() {
        when(mapper.selectOne(any())).thenReturn(adminPC);
        when(workspaceService.getWorkspaceByWorkspaceId("ws-1"))
            .thenReturn(Optional.of(new WorkspaceDTO()));

        HttpResultResponse r = service.demoLogin();

        assertEquals(0, r.getCode());
        verifyNoInteractions(captchaService);
    }

    @Test
    void demoLogin_failsWhenAdminPCMissing() {
        when(mapper.selectOne(any())).thenReturn(null);

        HttpResultResponse r = service.demoLogin();

        assertEquals(HttpStatus.UNAUTHORIZED.value(), r.getCode());
    }
}
```

注:**mqtt config 的 getter 方法名以现有 `UserServiceImpl.userLogin` 内调用的为准**(读 `UserServiceImpl.java` 100-140 行)。如果当前实现是 `mqttPropertyConfiguration.getMqttBroker(...)`,测试里要用同名 mock。

- [ ] **Step 2: 跑测试,确认失败**

```bash
cd D:/uavfire/uavFire_cloudApi/backend
JAVA_HOME="C:/Program Files/Java/jdk-11" mvn -pl uavfire test -Dtest=UserServiceImplLoginTest
```

Expected: FAIL — `userLogin` 签名是 3 参不是 5 参;`demoLogin` 方法不存在

- [ ] **Step 3: 改接口签名**

打开 `IUserService.java`,把:
```java
HttpResultResponse userLogin(String username, String password, Integer flag);
```
改为:
```java
HttpResultResponse userLogin(String username, String password, Integer flag, String captcha, String captchaToken);

HttpResultResponse demoLogin();
```

- [ ] **Step 4: 改 `UserServiceImpl.java`**

(a) 注入 captcha service。在字段区域加:
```java
@Autowired
private ICaptchaService captchaService;
```

(b) 把 `userLogin` 方法签名从 3 参改为 5 参,**在方法首部插入 captcha 校验**:

```java
@Override
public HttpResultResponse userLogin(String username, String password, Integer flag,
                                    String captcha, String captchaToken) {
    // captcha 校验(在所有现有逻辑之前)
    if (captchaToken == null || captchaToken.isBlank()
            || captcha == null || captcha.isBlank()) {
        return new HttpResultResponse()
                .setCode(HttpStatus.UNAUTHORIZED.value())
                .setMessage("验证码不能为空");
    }
    if (!captchaService.verifyAndConsume(captchaToken, captcha)) {
        return new HttpResultResponse()
                .setCode(HttpStatus.UNAUTHORIZED.value())
                .setMessage("验证码错误或已过期");
    }

    // ↓↓↓ 以下是原本 userLogin 的全部代码,保留不动 ↓↓↓
    UserEntity userEntity = this.getUserByUsername(username);
    if (userEntity == null) { ... }
    // ... 一直到 return HttpResultResponse.success(userDTO);
}
```

(c) 加 `demoLogin()` 方法,**复制 userLogin 中** 「找到 user 之后 → 构造 CustomClaim → JWT → 返回 payload」的代码段,但写死 username = `"adminPC"`,跳过 flag/password/captcha 校验:

```java
@Override
public HttpResultResponse demoLogin() {
    UserEntity userEntity = this.getUserByUsername("adminPC");
    if (userEntity == null) {
        return new HttpResultResponse()
                .setCode(HttpStatus.UNAUTHORIZED.value())
                .setMessage("演示账号未配置");
    }

    Optional<WorkspaceDTO> workspaceOpt = workspaceService.getWorkspaceByWorkspaceId(userEntity.getWorkspaceId());
    if (workspaceOpt.isEmpty()) {
        return new HttpResultResponse()
                .setCode(HttpStatus.UNAUTHORIZED.value())
                .setMessage("演示工作区无效");
    }

    CustomClaim customClaim = new CustomClaim(userEntity.getUserId(),
            userEntity.getUsername(), userEntity.getUserType(),
            workspaceOpt.get().getWorkspaceId());

    String token = JwtUtil.createToken(customClaim.convertToMap());

    UserDTO userDTO = entityConvertToDTO(userEntity);
    userDTO.setAccessToken(token);
    userDTO.setWorkspaceId(workspaceOpt.get().getWorkspaceId());
    // (其它字段:mqtt 等,按 userLogin 同样写)

    return HttpResultResponse.success(userDTO);
}
```

**注意:** 第二份 `userLogin` payload 构造代码块跟 demoLogin 高度重复 —— 如果觉得 DRY,抽一个 `private HttpResultResponse signAndPack(UserEntity, WorkspaceDTO)` 私有方法,但本任务不强制。

- [ ] **Step 5: 改 `LoginController.java` 调用新签名**

```java
@PostMapping("/login")
public HttpResultResponse login(@RequestBody UserLoginDTO loginDTO) {
    return userService.userLogin(
        loginDTO.getUsername(),
        loginDTO.getPassword(),
        loginDTO.getFlag(),
        loginDTO.getCaptcha(),
        loginDTO.getCaptchaToken()
    );
}

@PostMapping("/demo-login")
public HttpResultResponse demoLogin() {
    return userService.demoLogin();
}
```

- [ ] **Step 6: 跑测试,确认通过**

```bash
cd D:/uavfire/uavFire_cloudApi/backend
JAVA_HOME="C:/Program Files/Java/jdk-11" mvn -pl uavfire test -Dtest=UserServiceImplLoginTest
```

Expected: 5 tests PASS

- [ ] **Step 7: 全量编译 + 现有测试不挂**

```bash
cd D:/uavfire/uavFire_cloudApi/backend
JAVA_HOME="C:/Program Files/Java/jdk-11" mvn -pl uavfire test
```

Expected: BUILD SUCCESS

- [ ] **Step 8: Commit**

```bash
cd D:/uavfire/uavFire_cloudApi
git add backend/uavfire/src/main/java/com/yx/uavfire/manage/service/IUserService.java \
        backend/uavfire/src/main/java/com/yx/uavfire/manage/service/impl/UserServiceImpl.java \
        backend/uavfire/src/main/java/com/yx/uavfire/manage/controller/LoginController.java \
        backend/uavfire/src/test/java/com/yx/uavfire/manage/service/impl/UserServiceImplLoginTest.java
git commit -m "feat(login): enforce captcha in userLogin and add demoLogin endpoint"
```

---

## Task 8: 后端 — 启动联调验证

**Files:**(无代码改动)

- [ ] **Step 1: 重启后端**

```bash
# 先杀掉旧后端进程(如果还在跑)
# Windows PowerShell: Get-Process -Name java | Where-Object {$_.CommandLine -like "*uavfire*"} | Stop-Process

cd D:/uavfire/uavFire_cloudApi/backend
JAVA_HOME="C:/Program Files/Java/jdk-11" mvn -pl uavfire spring-boot:run
```

(后台运行)

- [ ] **Step 2: curl 验证 captcha + login**

```bash
# 1. 拿 captcha
curl -s http://localhost:6789/manage/api/v1/captcha | python -c "import json,sys; d=json.load(sys.stdin)['data']; print('token:', d['token']); print('base64-len:', len(d['imageBase64']))"
# Expected: token: <uuid>;  base64-len: > 1000

# 2. 用错的 captcha 登录
curl -s http://localhost:6789/manage/api/v1/login -X POST -H "Content-Type: application/json" \
  -d '{"username":"adminPC","password":"adminPC","flag":1,"captcha":"XXXX","captchaToken":"fake"}'
# Expected: {"code":401,"message":"验证码错误或已过期"}

# 3. demo-login
curl -s http://localhost:6789/manage/api/v1/demo-login -X POST -H "Content-Type: application/json" -d '{}'
# Expected: {"code":0,"message":"success","data":{... "access_token":"..." ...}}
```

- [ ] **Step 3: 全 PASS 后 commit(本步无文件改动,可跳)**

后端阶段完成,进入前端。

---

## Task 9: 前端 — `types/index.ts` 加 `RememberUsername` 枚举值

**Files:**
- Modify: `frontend/src/types/index.ts`(查 `ELocalStorageKey` 定义,可能在该文件或 `types/enums.ts`)

- [ ] **Step 1: 找枚举定义**

```bash
cd D:/uavfire/uavFire_cloudApi/frontend
grep -rn "ELocalStorageKey" src/types/
```

定位定义文件(可能是 `src/types/index.ts` 或 `src/types/enums.ts`)。

- [ ] **Step 2: 加枚举值**

在 `ELocalStorageKey` 枚举内加一行:
```typescript
RememberUsername = 'remember_username',
```

- [ ] **Step 3: Commit**

```bash
cd D:/uavfire/uavFire_cloudApi
git add frontend/src/types/<定位到的文件>
git commit -m "feat(login): add RememberUsername to ELocalStorageKey"
```

---

## Task 10: 前端 — `api/manage.ts` 扩 `LoginBody`

**Files:**
- Modify: `frontend/src/api/manage.ts`

- [ ] **Step 1: 改 interface**

把:
```typescript
export interface LoginBody {
 username: string,
 password: string,
 flag: number,
}
```
改为:
```typescript
export interface LoginBody {
 username: string,
 password: string,
 flag: number,
 captcha: string,
 captchaToken: string,
}
```

- [ ] **Step 2: 跑现有 frontend 测试,确保无回归**

```bash
cd D:/uavfire/uavFire_cloudApi/frontend
node --test scripts/*.test.mjs
```

Expected: 全 PASS(或与改动前同状态)

- [ ] **Step 3: Commit**

```bash
cd D:/uavfire/uavFire_cloudApi
git add frontend/src/api/manage.ts
git commit -m "feat(login): extend LoginBody with captcha + captchaToken"
```

---

## Task 11: 前端 — `api/captcha.ts` 新建,带静态契约测试

**Files:**
- Create: `frontend/src/api/captcha.ts`
- Create: `frontend/scripts/captcha-api-contract.test.mjs`

- [ ] **Step 1: 写失败的契约测试**

```javascript
// frontend/scripts/captcha-api-contract.test.mjs
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync, existsSync } from 'node:fs'
import { join } from 'node:path'

const root = new URL('..', import.meta.url).pathname
const captchaApiPath = join(root, 'src/api/captcha.ts')

test('src/api/captcha.ts exists', () => {
  assert.ok(existsSync(captchaApiPath))
})

test('captcha api exports getCaptcha calling GET /manage/api/v1/captcha', () => {
  const src = readFileSync(captchaApiPath, 'utf8')
  assert.match(src, /export\s+(const|async\s+function)\s+getCaptcha\b/)
  assert.match(src, /\/manage\/api\/v1\/captcha/)
  assert.match(src, /request\.get/)
})

test('captcha api exports demoLogin calling POST /manage/api/v1/demo-login', () => {
  const src = readFileSync(captchaApiPath, 'utf8')
  assert.match(src, /export\s+(const|async\s+function)\s+demoLogin\b/)
  assert.match(src, /\/manage\/api\/v1\/demo-login/)
  assert.match(src, /request\.post/)
})
```

- [ ] **Step 2: 跑测试,确认失败**

```bash
cd D:/uavfire/uavFire_cloudApi/frontend
node --test scripts/captcha-api-contract.test.mjs
```

Expected: FAIL — 文件不存在

- [ ] **Step 3: 创建 `src/api/captcha.ts`**

```typescript
import request, { IWorkspaceResponse } from '/@/api/http/request'

const HTTP_PREFIX = '/manage/api/v1'

export interface CaptchaResponse {
  token: string,
  imageBase64: string,
}

export const getCaptcha = async function (): Promise<IWorkspaceResponse<CaptchaResponse>> {
  const url = `${HTTP_PREFIX}/captcha`
  const result = await request.get(url)
  return result.data
}

export const demoLogin = async function (): Promise<IWorkspaceResponse<any>> {
  const url = `${HTTP_PREFIX}/demo-login`
  const result = await request.post(url, {})
  return result.data
}
```

- [ ] **Step 4: 跑测试,确认通过**

```bash
cd D:/uavfire/uavFire_cloudApi/frontend
node --test scripts/captcha-api-contract.test.mjs
```

Expected: 3 PASS

- [ ] **Step 5: Commit**

```bash
cd D:/uavfire/uavFire_cloudApi
git add frontend/src/api/captcha.ts frontend/scripts/captcha-api-contract.test.mjs
git commit -m "feat(login): add captcha + demoLogin api client with contract tests"
```

---

## Task 12: 前端 — `CaptchaImage.vue` 组件

**Files:**
- Create: `frontend/src/pages/page-web/login/components/CaptchaImage.vue`

- [ ] **Step 1: 新建组件**

```vue
<template>
  <div class="captcha-image" @click="refresh" :class="{ loading }">
    <img v-if="src" :src="src" alt="验证码,点击刷新" />
    <span v-else class="placeholder">{{ loading ? '加载中…' : '点击刷新' }}</span>
  </div>
</template>

<script lang="ts" setup>
import { ref, onMounted, defineExpose, defineEmits } from 'vue'
import { getCaptcha } from '/@/api/captcha'

const emit = defineEmits<{ (e: 'update:token', token: string): void }>()

const src = ref<string>('')
const loading = ref(false)

async function refresh () {
  if (loading.value) return
  loading.value = true
  try {
    const res = await getCaptcha()
    if (res.code === 0 && res.data) {
      src.value = `data:image/png;base64,${res.data.imageBase64}`
      emit('update:token', res.data.token)
    } else {
      src.value = ''
      emit('update:token', '')
    }
  } catch {
    src.value = ''
    emit('update:token', '')
  } finally {
    loading.value = false
  }
}

defineExpose({ refresh })
onMounted(refresh)
</script>

<style lang="scss" scoped>
.captcha-image {
  width: 120px;
  height: 40px;
  border: 1px solid rgba(64, 158, 255, 0.3);
  border-radius: 4px;
  background: rgba(255, 255, 255, 0.04);
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  overflow: hidden;
  user-select: none;
  transition: box-shadow 150ms ease;
  &:hover { box-shadow: 0 0 8px rgba(24, 144, 255, 0.4); }
  img { width: 100%; height: 100%; display: block; }
  .placeholder {
    color: rgba(255, 255, 255, 0.5);
    font-size: 12px;
  }
}
</style>
```

- [ ] **Step 2: 编译验证**

```bash
cd D:/uavfire/uavFire_cloudApi/frontend
npm run build 2>&1 | tail -20
```

Expected: build 不挂(允许 warning,但不允许 error)

- [ ] **Step 3: Commit**

```bash
cd D:/uavfire/uavFire_cloudApi
git add frontend/src/pages/page-web/login/components/CaptchaImage.vue
git commit -m "feat(login): add CaptchaImage component with click-to-refresh"
```

---

## Task 13: 前端 — `ForgotPasswordModal.vue` 组件

**Files:**
- Create: `frontend/src/pages/page-web/login/components/ForgotPasswordModal.vue`

- [ ] **Step 1: 新建组件**

```vue
<template>
  <a-modal
    :open="open"
    title="忘记密码"
    :footer="null"
    :width="360"
    @cancel="onClose"
    class="forgot-password-modal"
  >
    <p>忘记密码请联系系统管理员。</p>
    <div class="actions">
      <a-button type="primary" @click="onClose">我知道了</a-button>
    </div>
  </a-modal>
</template>

<script lang="ts" setup>
import { defineProps, defineEmits } from 'vue'

defineProps<{ open: boolean }>()
const emit = defineEmits<{ (e: 'update:open', value: boolean): void }>()

function onClose () {
  emit('update:open', false)
}
</script>

<style lang="scss" scoped>
.forgot-password-modal :deep(.ant-modal-content) {
  background: rgba(11, 28, 58, 0.95);
  color: #fff;
}
.actions {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}
</style>
```

- [ ] **Step 2: 编译验证**

```bash
cd D:/uavfire/uavFire_cloudApi/frontend
npm run build 2>&1 | tail -20
```

Expected: 不挂

- [ ] **Step 3: Commit**

```bash
cd D:/uavfire/uavFire_cloudApi
git add frontend/src/pages/page-web/login/components/ForgotPasswordModal.vue
git commit -m "feat(login): add ForgotPasswordModal with v-model:open contract"
```

---

## Task 14: 前端 — `LoginForm.vue` 表单组件

**Files:**
- Create: `frontend/src/pages/page-web/login/components/LoginForm.vue`

- [ ] **Step 1: 新建组件**

```vue
<template>
  <div class="login-form">
    <div class="title-row">
      <span class="line" />
      <h2 class="title">系统登录</h2>
      <span class="line" />
    </div>
    <p class="subtitle">欢迎进入无人机消防指挥平台</p>

    <a-form layout="vertical" :model="form" class="form">
      <a-form-item label="账号">
        <a-input v-model:value="form.username" placeholder="请输入账号" size="large">
          <template #prefix><UserOutlined /></template>
        </a-input>
      </a-form-item>

      <a-form-item label="密码">
        <a-input-password v-model:value="form.password" placeholder="请输入密码" size="large">
          <template #prefix><LockOutlined /></template>
        </a-input-password>
      </a-form-item>

      <a-form-item label="验证码">
        <div class="captcha-row">
          <a-input v-model:value="form.captcha" placeholder="请输入验证码" size="large" :maxlength="8">
            <template #prefix><SafetyOutlined /></template>
          </a-input>
          <CaptchaImage ref="captchaRef" @update:token="(t) => (form.captchaToken = t)" />
        </div>
      </a-form-item>

      <div class="row-between">
        <a-checkbox v-model:checked="form.remember">记住我</a-checkbox>
        <a class="link" @click="forgotOpen = true">忘记密码?</a>
      </div>

      <a-button
        type="primary"
        size="large"
        block
        :loading="submitting"
        :disabled="!canSubmit"
        @click="onSubmit"
        class="btn-primary"
      >登录系统</a-button>

      <a-button
        size="large"
        block
        :loading="demoLoading"
        @click="onDemo"
        class="btn-demo"
      >演示模式</a-button>

      <p class="version">Version 1.0</p>
    </a-form>

    <ForgotPasswordModal v-model:open="forgotOpen" />
  </div>
</template>

<script lang="ts" setup>
import { reactive, ref, computed, defineEmits, defineExpose } from 'vue'
import { UserOutlined, LockOutlined, SafetyOutlined } from '@ant-design/icons-vue'
import CaptchaImage from './CaptchaImage.vue'
import ForgotPasswordModal from './ForgotPasswordModal.vue'

interface FormState {
  username: string,
  password: string,
  captcha: string,
  captchaToken: string,
  remember: boolean,
}

const form = reactive<FormState>({
  username: '',
  password: '',
  captcha: '',
  captchaToken: '',
  remember: false,
})

const submitting = ref(false)
const demoLoading = ref(false)
const forgotOpen = ref(false)
const captchaRef = ref<{ refresh: () => void } | null>(null)

const canSubmit = computed(
  () => !!form.username && !!form.password && !!form.captcha && !!form.captchaToken
)

const emit = defineEmits<{
  (e: 'submit', payload: FormState): void,
  (e: 'demo'): void,
}>()

function onSubmit () {
  if (!canSubmit.value || submitting.value) return
  emit('submit', { ...form })
}

function onDemo () {
  emit('demo')
}

defineExpose({
  setSubmitting: (v: boolean) => (submitting.value = v),
  setDemoLoading: (v: boolean) => (demoLoading.value = v),
  refreshCaptcha: () => captchaRef.value?.refresh(),
  setUsername: (u: string) => { form.username = u; form.remember = true },
})
</script>

<style lang="scss" scoped>
.login-form {
  color: #cfd8e7;
}
.title-row {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 16px;
  margin-bottom: 8px;
  .line { width: 32px; height: 1px; background: #1890ff; }
  .title { margin: 0; color: #fff; font-size: 28px; font-weight: 700; letter-spacing: 2px; }
}
.subtitle {
  text-align: center;
  color: rgba(255, 255, 255, 0.65);
  font-size: 13px;
  margin-bottom: 24px;
  padding-bottom: 12px;
  border-bottom: 1px solid rgba(64, 158, 255, 0.15);
}
.form { color: #cfd8e7; }
.form :deep(.ant-form-item-label > label) { color: #cfd8e7; }
.form :deep(.ant-input),
.form :deep(.ant-input-affix-wrapper),
.form :deep(.ant-input-password) {
  background: rgba(255, 255, 255, 0.04);
  border-color: rgba(64, 158, 255, 0.2);
  color: #fff;
}
.captcha-row {
  display: flex;
  gap: 8px;
  align-items: stretch;
}
.row-between {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin: 8px 0 20px;
  .link { color: #40a9ff; cursor: pointer; font-size: 13px; }
}
.btn-primary {
  margin-bottom: 12px;
  transition: box-shadow 150ms ease;
  &:hover:not(:disabled) { box-shadow: 0 0 16px rgba(24, 144, 255, 0.4); }
}
.btn-demo {
  background: transparent;
  border: 1px solid #1890ff;
  color: #40a9ff;
  transition: box-shadow 150ms ease;
  &:hover { box-shadow: 0 0 16px rgba(24, 144, 255, 0.3); }
}
.version {
  text-align: center;
  color: rgba(255, 255, 255, 0.4);
  font-size: 12px;
  margin-top: 16px;
}
</style>
```

- [ ] **Step 2: 编译验证**

```bash
cd D:/uavfire/uavFire_cloudApi/frontend
npm run build 2>&1 | tail -20
```

Expected: 不挂

- [ ] **Step 3: Commit**

```bash
cd D:/uavfire/uavFire_cloudApi
git add frontend/src/pages/page-web/login/components/LoginForm.vue
git commit -m "feat(login): add LoginForm with captcha + remember + forgot + demo emits"
```

---

## Task 15: 前端 — `use-login.ts` composable

**Files:**
- Create: `frontend/src/pages/page-web/login/composables/use-login.ts`

- [ ] **Step 1: 新建 composable**

```typescript
import { message } from 'ant-design-vue'
import { login, LoginBody } from '/@/api/manage'
import { demoLogin as demoLoginApi } from '/@/api/captcha'
import { getRoot } from '/@/root'
import { ELocalStorageKey, ERouterName, EUserType } from '/@/types'

interface FormSubmitPayload {
  username: string,
  password: string,
  captcha: string,
  captchaToken: string,
  remember: boolean,
}

interface FormHandle {
  setSubmitting: (v: boolean) => void,
  setDemoLoading: (v: boolean) => void,
  refreshCaptcha: () => void,
}

function persistLogin (data: any) {
  localStorage.setItem(ELocalStorageKey.Token, data.access_token)
  localStorage.setItem(ELocalStorageKey.WorkspaceId, data.workspace_id)
  localStorage.setItem(ELocalStorageKey.Username, data.username)
  localStorage.setItem(ELocalStorageKey.UserId, data.user_id)
  localStorage.setItem(ELocalStorageKey.Flag, EUserType.Web.toString())
}

export function readRememberedUsername (): string {
  return localStorage.getItem(ELocalStorageKey.RememberUsername) ?? ''
}

export function useLogin () {

  async function onLogin (payload: FormSubmitPayload, formHandle: FormHandle) {
    formHandle.setSubmitting(true)
    try {
      const body: LoginBody = {
        username: payload.username,
        password: payload.password,
        flag: EUserType.Web,
        captcha: payload.captcha,
        captchaToken: payload.captchaToken,
      }
      const result = await login(body)
      if (result.code === 0) {
        persistLogin(result.data)
        if (payload.remember) {
          localStorage.setItem(ELocalStorageKey.RememberUsername, payload.username)
        } else {
          localStorage.removeItem(ELocalStorageKey.RememberUsername)
        }
        getRoot().$router.push(ERouterName.LEADERSHIP_COCKPIT)
        return
      }
      message.error(result.message ?? '登录失败')
    } catch (e) {
      message.error('网络错误,请重试')
    } finally {
      formHandle.setSubmitting(false)
      formHandle.refreshCaptcha()    // captcha 一次性,失败/成功后都不复用
    }
  }

  async function onDemoLogin (formHandle: FormHandle) {
    formHandle.setDemoLoading(true)
    try {
      const result = await demoLoginApi()
      if (result.code === 0) {
        persistLogin(result.data)
        // 演示模式不写 remember_username
        getRoot().$router.push(ERouterName.LEADERSHIP_COCKPIT)
        return
      }
      message.error(result.message ?? '演示模式不可用')
    } catch (e) {
      message.error('网络错误,请重试')
    } finally {
      formHandle.setDemoLoading(false)
    }
  }

  return { onLogin, onDemoLogin, readRememberedUsername }
}
```

- [ ] **Step 2: 编译验证**

```bash
cd D:/uavfire/uavFire_cloudApi/frontend
npm run build 2>&1 | tail -20
```

Expected: 不挂

- [ ] **Step 3: Commit**

```bash
cd D:/uavfire/uavFire_cloudApi
git add frontend/src/pages/page-web/login/composables/use-login.ts
git commit -m "feat(login): add use-login composable with onLogin/onDemoLogin/remember"
```

---

## Task 16: 前端 — `LoginPage.vue` 整页布局

**Files:**
- Create: `frontend/src/pages/page-web/login/LoginPage.vue`

- [ ] **Step 1: 新建组件**

```vue
<template>
  <div class="login-page">
    <div class="login-card">
      <LoginForm ref="formRef" @submit="onSubmit" @demo="onDemo" />
    </div>
  </div>
</template>

<script lang="ts" setup>
import { ref, onMounted } from 'vue'
import LoginForm from './components/LoginForm.vue'
import { useLogin } from './composables/use-login'

const formRef = ref<any>(null)
const { onLogin, onDemoLogin, readRememberedUsername } = useLogin()

onMounted(() => {
  const remembered = readRememberedUsername()
  if (remembered) formRef.value?.setUsername(remembered)
})

function onSubmit (payload: any) {
  onLogin(payload, {
    setSubmitting: (v: boolean) => formRef.value?.setSubmitting(v),
    setDemoLoading: (v: boolean) => formRef.value?.setDemoLoading(v),
    refreshCaptcha: () => formRef.value?.refreshCaptcha(),
  })
}

function onDemo () {
  onDemoLogin({
    setSubmitting: (v: boolean) => formRef.value?.setSubmitting(v),
    setDemoLoading: (v: boolean) => formRef.value?.setDemoLoading(v),
    refreshCaptcha: () => formRef.value?.refreshCaptcha(),
  })
}
</script>

<style lang="scss" scoped>
.login-page {
  position: fixed;
  inset: 0;
  background-image: url('/@/assets/login-bg.png');
  background-size: cover;
  background-position: center;
  background-repeat: no-repeat;
  min-width: 1366px;
  overflow: hidden;
}
.login-card {
  position: absolute;
  right: 80px;
  top: 50%;
  transform: translateY(-50%);
  width: 400px;
  padding: 32px;
  background: rgba(11, 28, 58, 0.85);
  backdrop-filter: blur(8px);
  -webkit-backdrop-filter: blur(8px);
  border: 1px solid rgba(64, 158, 255, 0.3);
  border-radius: 12px;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.45);
  animation: slideUp 400ms cubic-bezier(0.22, 1, 0.36, 1);
}
@keyframes slideUp {
  from { opacity: 0; transform: translateY(calc(-50% + 20px)); }
  to   { opacity: 1; transform: translateY(-50%); }
}
</style>
```

- [ ] **Step 2: 编译验证**

```bash
cd D:/uavfire/uavFire_cloudApi/frontend
npm run build 2>&1 | tail -20
```

Expected: 不挂(`login-bg.png` 不存在会报 warning,Task 18 解决)

- [ ] **Step 3: Commit**

```bash
cd D:/uavfire/uavFire_cloudApi
git add frontend/src/pages/page-web/login/LoginPage.vue
git commit -m "feat(login): add LoginPage with fixed bg + glass card + slideUp entrance"
```

---

## Task 17: 前端 — 重写 `pages/page-web/index.vue` 入口

**Files:**
- Modify: `frontend/src/pages/page-web/index.vue`

- [ ] **Step 1: 整页替换**

把 `index.vue` 整个文件改成:

```vue
<template>
  <LoginPage />
</template>

<script lang="ts" setup>
import LoginPage from './login/LoginPage.vue'
</script>
```

- [ ] **Step 2: 编译验证**

```bash
cd D:/uavfire/uavFire_cloudApi/frontend
npm run build 2>&1 | tail -20
```

Expected: 不挂

- [ ] **Step 3: Commit**

```bash
cd D:/uavfire/uavFire_cloudApi
git add frontend/src/pages/page-web/index.vue
git commit -m "feat(login): replace legacy login page with new LoginPage container"
```

---

## Task 18: 用户放置背景图资源

**Files:**
- Create: `frontend/src/assets/login-bg.png`

- [ ] **Step 1: 拷贝图片**

用户(或 agent)需要把 Image #1 合成图保存为:
```
D:/uavfire/uavFire_cloudApi/frontend/src/assets/login-bg.png
```

建议长边 ≥ 1920px。

- [ ] **Step 2: 验证存在**

```bash
ls D:/uavfire/uavFire_cloudApi/frontend/src/assets/login-bg.png
```

Expected: 文件存在,大小 ≥ 200KB

- [ ] **Step 3: Commit**

```bash
cd D:/uavfire/uavFire_cloudApi
git add frontend/src/assets/login-bg.png
git commit -m "feat(login): add login background image asset"
```

---

## Task 19: 综合静态契约测试

**Files:**
- Create: `frontend/scripts/login-page-redesign.test.mjs`

- [ ] **Step 1: 写综合测试**

```javascript
import test from 'node:test'
import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'
import { join } from 'node:path'

const root = new URL('..', import.meta.url).pathname
const p = (rel) => join(root, rel)

test('login files all exist', () => {
  for (const rel of [
    'src/api/captcha.ts',
    'src/pages/page-web/index.vue',
    'src/pages/page-web/login/LoginPage.vue',
    'src/pages/page-web/login/components/LoginForm.vue',
    'src/pages/page-web/login/components/CaptchaImage.vue',
    'src/pages/page-web/login/components/ForgotPasswordModal.vue',
    'src/pages/page-web/login/composables/use-login.ts',
    'src/assets/login-bg.png',
  ]) {
    assert.ok(existsSync(p(rel)), `missing ${rel}`)
  }
})

test('LoginBody interface includes captcha + captchaToken', () => {
  const src = readFileSync(p('src/api/manage.ts'), 'utf8')
  assert.match(src, /captcha:\s*string/)
  assert.match(src, /captchaToken:\s*string/)
})

test('page-web/index.vue is collapsed to <LoginPage />', () => {
  const src = readFileSync(p('src/pages/page-web/index.vue'), 'utf8')
  assert.match(src, /<LoginPage\s*\/>/)
  assert.doesNotMatch(src, /<a-input\s+v-model:value="formState\.username"/)
})

test('LoginForm has both 登录系统 and 演示模式 buttons', () => {
  const src = readFileSync(p('src/pages/page-web/login/components/LoginForm.vue'), 'utf8')
  assert.match(src, /登录系统/)
  assert.match(src, /演示模式/)
})

test('use-login persists remember_username only when remember is true', () => {
  const src = readFileSync(p('src/pages/page-web/login/composables/use-login.ts'), 'utf8')
  assert.match(src, /RememberUsername/)
  assert.match(src, /removeItem.*RememberUsername/)
})

test('use-login refreshes captcha in finally', () => {
  const src = readFileSync(p('src/pages/page-web/login/composables/use-login.ts'), 'utf8')
  // 简化:确保 onLogin 函数体里同时有 finally 和 refreshCaptcha
  assert.match(src, /finally\s*\{[\s\S]*?refreshCaptcha/)
})
```

- [ ] **Step 2: 跑测试,确认全 PASS**

```bash
cd D:/uavfire/uavFire_cloudApi/frontend
node --test scripts/login-page-redesign.test.mjs
```

Expected: 6 PASS

- [ ] **Step 3: Commit**

```bash
cd D:/uavfire/uavFire_cloudApi
git add frontend/scripts/login-page-redesign.test.mjs
git commit -m "test(login): add integration contract for new login page wiring"
```

---

## Task 20: 端到端手动验证(浏览器)

**Files:**(无代码改动)

- [ ] **Step 1: 启后端 + 前端**

```bash
# Terminal 1 - 后端
cd D:/uavfire/uavFire_cloudApi/backend
JAVA_HOME="C:/Program Files/Java/jdk-11" mvn -pl uavfire spring-boot:run

# Terminal 2 - 前端
cd D:/uavfire/uavFire_cloudApi/frontend
npm run serve
```

- [ ] **Step 2: 浏览器验证清单**

打开 `http://localhost:8080`,逐项核对:

1. 整页背景显示 Image #1(山林无人机灭火主视觉)
2. 右侧深色玻璃登录卡入场动画 fade + 上移
3. 顶部「系统登录」标题两侧有蓝色短线 + 副标题「欢迎进入无人机消防指挥平台」
4. 账号 / 密码 / 验证码 三个输入框,验证码区域右侧显示一张 4 字符验证码图片
5. 点验证码图片 → 刷新一张新的
6. 不填任何字段 → 「登录系统」按钮 disabled
7. 填 `adminPC` / `adminPC` / (正确 captcha)/ flag=1 → 点登录 → 跳到领导驾驶舱
8. 回到登录页 → 账号框自动填了 `adminPC`(说明记住我从上次成功登录后写入)
9. 输入错误 captcha → 弹错 + 验证码图自动刷新
10. 取消勾选「记住我」再次成功登录 → 下次再回登录页账号框为空
11. 点「忘记密码?」→ 弹 Modal「请联系系统管理员」→ 点「我知道了」关闭
12. 点「演示模式」按钮 → 直接跳到领导驾驶舱(无需账号密码)
13. 浏览器 DevTools Network 看 `/captcha` 是 200 + JSON `{token, imageBase64}`,`/login` 含 captcha + captchaToken
14. 按钮 hover 有轻微 box-shadow 发光
15. 缩窄窗口到 1300px → 出现横向滚动条(min-width 1366 生效)

- [ ] **Step 3: 全部 PASS 则收工**

无文件改动,无需 commit。

---

## Self-Review 备忘

完成所有任务后,跑一次综合验证:

```bash
# 后端测试
cd D:/uavfire/uavFire_cloudApi/backend
JAVA_HOME="C:/Program Files/Java/jdk-11" mvn -pl uavfire test

# 前端测试
cd D:/uavfire/uavFire_cloudApi/frontend
node --test scripts/*.test.mjs

# 前端 build 验证
npm run build
```

三项都绿即视为登录页重做整体完成。

---

## 风险提示

- **`adminPC` 用户被删/改密导致 demo-login 失效** —— spec 已声明:adminPC 是 demo 模式唯一身份,运维不应改其用户名/密码
- **min-width 1366px 在小屏会横向滚** —— 用户已确认接受,后期若想响应式需另提供「无文字版背景图」
- **captcha 字符集有限(31 字符 × 4 位 = 92 万组合)+ 60s TTL** —— 对真机器人攻击防御中等,够用于内网企业场景
- **背景图 PNG 体积可能 1-3MB** —— 首屏加载会感到延迟。可后续优化为 WebP 并预加载
