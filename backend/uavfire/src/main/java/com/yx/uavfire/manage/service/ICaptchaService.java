package com.yx.uavfire.manage.service;

import com.yx.uavfire.manage.model.dto.CaptchaDTO;

public interface ICaptchaService {

    /** 生成新验证码,写入 Redis(TTL 见 CaptchaConfig),返回 token + base64 图片 */
    CaptchaDTO generate();

    /** 校验。命中则 DEL(一次性消费),返回 true;未命中或过期返回 false。大小写不敏感 */
    boolean verifyAndConsume(String token, String userInput);
}
