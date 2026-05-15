package com.yx.uavfire.manage.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yx.uavfire.manage.model.dto.CaptchaDTO;
import com.yx.uavfire.manage.service.ICaptchaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CaptchaControllerTest {

    private MockMvc mockMvc;
    private ICaptchaService captchaService;

    @BeforeEach
    void setUp() {
        CaptchaController controller = new CaptchaController();
        captchaService = mock(ICaptchaService.class);
        ReflectionTestUtils.setField(controller, "captchaService", captchaService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(new ObjectMapper()))
                .addPlaceholderValue("url.manage.prefix", "manage")
                .addPlaceholderValue("url.manage.version", "/api/v1")
                .build();
    }

    @Test
    void getCaptcha_returns200WithTokenAndBase64() throws Exception {
        when(captchaService.generate())
                .thenReturn(new CaptchaDTO("tok-abc", "iVBORw0KGgoAAAA..."));

        mockMvc.perform(get("/manage/api/v1/captcha"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.code").value(0))
               .andExpect(jsonPath("$.data.token").value("tok-abc"))
               .andExpect(jsonPath("$.data.imageBase64").value("iVBORw0KGgoAAAA..."));
    }
}
