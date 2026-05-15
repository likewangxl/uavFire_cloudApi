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
        String stored = redisTemplate.opsForValue().getAndDelete(key);
        if (stored == null) return false;
        return Objects.equals(stored.toUpperCase(), userInput.toUpperCase());
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

        g.setColor(new Color(245, 248, 252));
        g.fillRect(0, 0, CaptchaConfig.IMAGE_WIDTH, CaptchaConfig.IMAGE_HEIGHT);

        for (int i = 0; i < 6; i++) {
            g.setColor(new Color(180 + RNG.nextInt(60), 180 + RNG.nextInt(60), 180 + RNG.nextInt(60)));
            g.drawLine(RNG.nextInt(CaptchaConfig.IMAGE_WIDTH), RNG.nextInt(CaptchaConfig.IMAGE_HEIGHT),
                       RNG.nextInt(CaptchaConfig.IMAGE_WIDTH), RNG.nextInt(CaptchaConfig.IMAGE_HEIGHT));
        }

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
