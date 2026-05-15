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
