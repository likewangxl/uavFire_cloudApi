package com.yx.uavfire.common.config;

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
