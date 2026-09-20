package com.yx.uavfire.miniapp.service;

public interface WechatCodeGateway {
    /** Returns only OpenID; session_key must never reach the application client. */
    String exchange(String code);
}
