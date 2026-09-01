package com.yx.uavfire.wayline.agent.controller;

import com.auth0.jwt.algorithms.Algorithm;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yx.uavfire.common.util.JwtUtil;
import com.yx.uavfire.wayline.agent.model.dto.WaylineAgentTokenRequestDTO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WaylineAgentAuthControllerTest {

    private static final String JWT_SECRET = "test-secret";
    private static final String SHARED_SECRET = "shared-secret-value";

    private MockMvc mockMvc;
    private WaylineAgentAuthController controller;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeAll
    static void initJwt() {
        ReflectionTestUtils.setField(JwtUtil.class, "algorithm", Algorithm.HMAC256(JWT_SECRET));
        ReflectionTestUtils.setField(JwtUtil.class, "age", 3600_000L);
    }

    @BeforeEach
    void setUp() {
        controller = new WaylineAgentAuthController();
        ReflectionTestUtils.setField(controller, "sharedSecret", SHARED_SECRET);
        ReflectionTestUtils.setField(controller, "tokenTtlSeconds", 3600L);
        ReflectionTestUtils.setField(controller, "requireHttps", false);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(new ObjectMapper()))
                .addPlaceholderValue("url.wayline-agent.prefix", "wayline-agent")
                .addPlaceholderValue("url.wayline-agent.version", "/api/v1")
                .build();
    }

    @Test
    void issueToken_requiresHttpsWhenProductionProtectionEnabled() throws Exception {
        ReflectionTestUtils.setField(controller, "requireHttps", true);
        WaylineAgentTokenRequestDTO body = new WaylineAgentTokenRequestDTO();
        body.setDroneSn("SN-A");
        body.setSharedSecret(SHARED_SECRET);

        mockMvc.perform(post("/wayline-agent/api/v1/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isUpgradeRequired());
    }

    @Test
    void issueToken_acceptsContainerVerifiedHttps() throws Exception {
        ReflectionTestUtils.setField(controller, "requireHttps", true);
        WaylineAgentTokenRequestDTO body = new WaylineAgentTokenRequestDTO();
        body.setDroneSn("SN-A");
        body.setSharedSecret(SHARED_SECRET);

        mockMvc.perform(post("/wayline-agent/api/v1/auth/token")
                        .secure(true)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").isNotEmpty());
    }

    @Test
    void issueToken_returnsTokenForValidSharedSecret() throws Exception {
        WaylineAgentTokenRequestDTO body = new WaylineAgentTokenRequestDTO();
        body.setDroneSn("SN-A");
        body.setSharedSecret(SHARED_SECRET);

        mockMvc.perform(post("/wayline-agent/api/v1/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andExpect(jsonPath("$.data.expiresIn").value(3600));
    }

    @Test
    void issueToken_rejectsInvalidSharedSecret() throws Exception {
        WaylineAgentTokenRequestDTO body = new WaylineAgentTokenRequestDTO();
        body.setDroneSn("SN-A");
        body.setSharedSecret("wrong-secret");

        mockMvc.perform(post("/wayline-agent/api/v1/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void issueToken_rejectsMissingDroneSn() throws Exception {
        WaylineAgentTokenRequestDTO body = new WaylineAgentTokenRequestDTO();
        body.setSharedSecret(SHARED_SECRET);

        mockMvc.perform(post("/wayline-agent/api/v1/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void issueToken_rejectsMissingSharedSecret() throws Exception {
        WaylineAgentTokenRequestDTO body = new WaylineAgentTokenRequestDTO();
        body.setDroneSn("SN-A");

        mockMvc.perform(post("/wayline-agent/api/v1/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }
}
