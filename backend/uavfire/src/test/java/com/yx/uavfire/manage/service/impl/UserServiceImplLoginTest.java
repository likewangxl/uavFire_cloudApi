package com.yx.uavfire.manage.service.impl;

import com.auth0.jwt.algorithms.Algorithm;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.yx.uavfire.common.util.JwtUtil;
import com.yx.uavfire.manage.dao.IUserMapper;
import com.yx.uavfire.manage.model.dto.WorkspaceDTO;
import com.yx.uavfire.manage.model.entity.UserEntity;
import com.yx.uavfire.manage.model.enums.UserTypeEnum;
import com.yx.uavfire.manage.service.ICaptchaService;
import com.yx.uavfire.manage.service.IWorkspaceService;
import com.dji.sdk.common.HttpResultResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceImplLoginTest {

    @Mock private IUserMapper mapper;
    @Mock private ICaptchaService captchaService;
    @Mock private IWorkspaceService workspaceService;

    @InjectMocks private UserServiceImpl service;

    private UserEntity adminPC;
    private UserEntity pilot;
    private WorkspaceDTO workspace;
    private Algorithm originalAlgorithm;

    @BeforeEach
    void setup() {
        // Initialise JwtUtil static algorithm so createToken doesn't throw.
        originalAlgorithm = JwtUtil.algorithm;
        JwtUtil.algorithm = Algorithm.HMAC256("test-secret");

        adminPC = new UserEntity();
        adminPC.setUserId("uid-1");
        adminPC.setUsername("adminPC");
        adminPC.setPassword("adminPC");
        adminPC.setUserType(1);
        adminPC.setWorkspaceId("ws-1");

        pilot = new UserEntity();
        pilot.setUserId("uid-2");
        pilot.setUsername("pilot");
        pilot.setPassword("pilot123");
        pilot.setUserType(UserTypeEnum.PILOT.getVal());
        pilot.setWorkspaceId("ws-1");

        workspace = WorkspaceDTO.builder()
                .workspaceId("ws-1")
                .workspaceName("test-ws")
                .build();
    }

    @AfterEach
    void teardown() {
        JwtUtil.algorithm = originalAlgorithm;
    }

    @Test
    void userLogin_failsWhenCaptchaTokenMissing() {
        HttpResultResponse r = service.userLogin("adminPC", "adminPC", 1, "ABCD", null);

        assertEquals(HttpStatus.UNAUTHORIZED.value(), r.getCode());
        verifyNoInteractions(mapper);
        verifyNoInteractions(captchaService);
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
        when(mapper.selectOne(any(QueryWrapper.class))).thenReturn(adminPC);
        when(workspaceService.getWorkspaceByWorkspaceId("ws-1")).thenReturn(Optional.of(workspace));

        // Use a spy to avoid the static MqttPropertyConfiguration call.
        UserServiceImpl spySvc = spy(service);
        doReturn("tcp://test:1883").when(spySvc).resolveMqttAddress();

        HttpResultResponse r = spySvc.userLogin("adminPC", "adminPC", 1, "ABCD", "tok");

        assertEquals(0, r.getCode());
        verify(captchaService).verifyAndConsume("tok", "ABCD");
    }

    @Test
    void userLogin_upgradesPlaintextPasswordToBcrypt() {
        when(captchaService.verifyAndConsume("tok", "ABCD")).thenReturn(true);
        when(mapper.selectOne(any(QueryWrapper.class))).thenReturn(adminPC);
        when(workspaceService.getWorkspaceByWorkspaceId("ws-1")).thenReturn(Optional.of(workspace));

        UserServiceImpl spySvc = spy(service);
        doReturn("tcp://test:1883").when(spySvc).resolveMqttAddress();

        HttpResultResponse r = spySvc.userLogin("adminPC", "adminPC", 1, "ABCD", "tok");

        assertEquals(0, r.getCode());
        assertTrue(adminPC.getPassword().startsWith("{bcrypt}"));
        ArgumentCaptor<UpdateWrapper<UserEntity>> update = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(mapper).update(isNull(), update.capture());
        String sql = String.valueOf(update.getValue().getSqlSegment()) + update.getValue().getSqlSet();
        assertTrue(sql.contains("username"));
        assertTrue(sql.contains("password"));
        assertTrue(update.getValue().getParamNameValuePairs().values().stream()
                .anyMatch(value -> value instanceof String && ((String) value).startsWith("{bcrypt}")));
    }

    @Test
    void userLogin_skipsCaptchaForPilotLogin() {
        when(mapper.selectOne(any(QueryWrapper.class))).thenReturn(pilot);
        when(workspaceService.getWorkspaceByWorkspaceId("ws-1")).thenReturn(Optional.of(workspace));

        UserServiceImpl spySvc = spy(service);
        doReturn("tcp://test:1883").when(spySvc).resolveMqttAddress();

        HttpResultResponse r = spySvc.userLogin(
                "pilot",
                "pilot123",
                UserTypeEnum.PILOT.getVal(),
                null,
                null);

        assertEquals(0, r.getCode());
        verifyNoInteractions(captchaService);
    }

    @Test
    void demoLogin_isRejectedWhenDisabled() {
        HttpResultResponse r = service.demoLogin();

        assertEquals(HttpStatus.FORBIDDEN.value(), r.getCode());
        verifyNoInteractions(mapper);
    }

    @Test
    void demoLogin_returnsSuccessForAdminPC() {
        ReflectionTestUtils.setField(service, "demoLoginEnabled", true);
        when(mapper.selectOne(any(QueryWrapper.class))).thenReturn(adminPC);
        when(workspaceService.getWorkspaceByWorkspaceId("ws-1")).thenReturn(Optional.of(workspace));

        UserServiceImpl spySvc = spy(service);
        doReturn("tcp://test:1883").when(spySvc).resolveMqttAddress();

        HttpResultResponse r = spySvc.demoLogin();

        assertEquals(0, r.getCode());
        verifyNoInteractions(captchaService);
    }

    @Test
    void demoLogin_failsWhenAdminPCMissing() {
        ReflectionTestUtils.setField(service, "demoLoginEnabled", true);
        when(mapper.selectOne(any(QueryWrapper.class))).thenReturn(null);

        HttpResultResponse r = service.demoLogin();

        assertEquals(HttpStatus.UNAUTHORIZED.value(), r.getCode());
    }
}
