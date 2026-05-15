package com.yx.uavfire.manage.service.impl;

import com.auth0.jwt.algorithms.Algorithm;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.yx.uavfire.common.util.JwtUtil;
import com.yx.uavfire.manage.dao.IUserMapper;
import com.yx.uavfire.manage.model.dto.WorkspaceDTO;
import com.yx.uavfire.manage.model.entity.UserEntity;
import com.yx.uavfire.manage.service.ICaptchaService;
import com.yx.uavfire.manage.service.IWorkspaceService;
import com.dji.sdk.common.HttpResultResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceImplLoginTest {

    @Mock private IUserMapper mapper;
    @Mock private ICaptchaService captchaService;
    @Mock private IWorkspaceService workspaceService;

    @InjectMocks private UserServiceImpl service;

    private UserEntity adminPC;
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
    void demoLogin_returnsSuccessForAdminPC() {
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
        when(mapper.selectOne(any(QueryWrapper.class))).thenReturn(null);

        HttpResultResponse r = service.demoLogin();

        assertEquals(HttpStatus.UNAUTHORIZED.value(), r.getCode());
    }
}
