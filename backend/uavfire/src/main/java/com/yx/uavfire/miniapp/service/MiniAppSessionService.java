package com.yx.uavfire.miniapp.service;

import com.yx.uavfire.common.model.CustomClaim;
import com.yx.uavfire.miniapp.configuration.MiniAppProperties;
import com.yx.uavfire.miniapp.web.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;

@Service
public class MiniAppSessionService {
    public static final long ACCESS_TTL = 15 * 60_000L;
    private static final long REFRESH_TTL = 7 * 24 * 60 * 60_000L;
    private final JdbcTemplate jdbc;
    private final MiniAppProperties properties;
    private final SecureRandom random = new SecureRandom();

    public MiniAppSessionService(JdbcTemplate jdbc, MiniAppProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    @Transactional
    public Map<String, Object> login(String openId, String deviceId) {
        long now = System.currentTimeMillis();
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO miniapp_identity(identity_id,app_id,open_id,binding_status,create_time,update_time) "
                        + "VALUES(?,?,?,'UNBOUND',?,?) ON DUPLICATE KEY UPDATE open_id=VALUES(open_id)",
                id, properties.getWechat().getAppId(), openId, now, now);
        Map<String, Object> identity = jdbc.queryForMap("SELECT * FROM miniapp_identity WHERE app_id=? AND open_id=? FOR UPDATE",
                properties.getWechat().getAppId(), openId);
        id = (String) identity.get("identity_id");
        String state = (String) identity.get("binding_status");
        if ("DISABLED".equals(state)) throw denied();
        if ("APPROVED".equals(state)) {
            requireUser(id);
            jdbc.update("UPDATE miniapp_session SET revoked=TRUE WHERE identity_id=? AND device_id=?", id, deviceId);
            return issue(id, deviceId, now);
        }
        String ticket = token("mpb_");
        jdbc.update("DELETE FROM miniapp_login_ticket WHERE identity_id=? AND device_id=?", id, deviceId);
        jdbc.update("INSERT INTO miniapp_login_ticket(ticket_hash,identity_id,device_id,expires_at) VALUES(?,?,?,?)",
                hash(ticket), id, deviceId, now + 10 * 60_000L);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("loginStatus", "PENDING".equals(state) ? "PENDING_APPROVAL" : "BINDING_REQUIRED");
        result.put("bindingStatus", state);
        result.put("bindingTicket", ticket);
        result.put("expiresIn", 600);
        return result;
    }

    @Transactional
    public Map<String, Object> requestBinding(String ticket, String username, String name, String organization) {
        validate(ticket, 16, 256); validate(username, 1, 128); validate(name, 1, 80); validate(organization, 1, 160);
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM miniapp_login_ticket WHERE ticket_hash=? AND expires_at>? FOR UPDATE",
                hash(ticket), System.currentTimeMillis());
        if (rows.isEmpty()) throw expired();
        String id = (String) rows.get(0).get("identity_id");
        int updated = jdbc.update("UPDATE miniapp_identity SET requested_username=?,display_name=?,organization=?,binding_status='PENDING',update_time=? "
                        + "WHERE identity_id=? AND binding_status IN ('UNBOUND','REJECTED')",
                username.trim(), name.trim(), organization.trim(), System.currentTimeMillis(), id);
        if (updated != 1) throw new MiniAppException(HttpStatus.CONFLICT, MiniAppErrorCode.BINDING_PENDING, "申请已提交或账号状态已变化，请重新登录查看");
        jdbc.update("DELETE FROM miniapp_login_ticket WHERE ticket_hash=?", hash(ticket));
        jdbc.update("INSERT INTO miniapp_binding_audit(audit_id,identity_id,action,actor,reason,create_time) VALUES(?,?,'REQUESTED',?,'用户申请绑定',?)",
                UUID.randomUUID().toString(), id, id, System.currentTimeMillis());
        return Map.of("loginStatus", "PENDING_APPROVAL", "bindingStatus", "PENDING");
    }

    public CustomClaim authenticate(String accessToken) {
        if (accessToken == null || !accessToken.matches("mpa_[A-Za-z0-9_-]{43}")) throw expired();
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT identity_id FROM miniapp_session WHERE access_hash=? AND revoked=FALSE AND access_expires_at>?",
                hash(accessToken), System.currentTimeMillis());
        if (rows.isEmpty()) throw expired();
        Map<String, Object> user = requireUser((String) rows.get(0).get("identity_id"));
        return new CustomClaim((String) user.get("user_id"), (String) user.get("username"),
                ((Number) user.get("user_type")).intValue(), (String) user.get("workspace_id"));
    }

    @Transactional
    public Map<String, Object> refresh(String refreshToken, String deviceId) {
        validate(refreshToken, 16, 256); validate(deviceId, 8, 128);
        long now = System.currentTimeMillis();
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM miniapp_session WHERE refresh_hash=? AND device_id=? AND revoked=FALSE AND refresh_expires_at>? FOR UPDATE",
                hash(refreshToken), deviceId, now);
        if (rows.isEmpty()) throw expired();
        Map<String, Object> row = rows.get(0);
        requireUser((String) row.get("identity_id"));
        String access = token("mpa_"); String refresh = token("mpr_");
        long remaining = ((Number) row.get("refresh_expires_at")).longValue() - now;
        long accessTtl = Math.min(ACCESS_TTL, remaining);
        jdbc.update("UPDATE miniapp_session SET access_hash=?,refresh_hash=?,access_expires_at=? WHERE session_id=?",
                hash(access), hash(refresh), now + accessTtl, row.get("session_id"));
        return tokens(access, refresh, (String) row.get("session_id"), accessTtl, remaining);
    }

    public void logout(String accessToken) {
        jdbc.update("UPDATE miniapp_session SET revoked=TRUE WHERE access_hash=?", hash(accessToken));
    }

    private Map<String, Object> issue(String identityId, String deviceId, long now) {
        String id = UUID.randomUUID().toString(); String access = token("mpa_"); String refresh = token("mpr_");
        jdbc.update("INSERT INTO miniapp_session(session_id,identity_id,device_id,access_hash,refresh_hash,access_expires_at,refresh_expires_at,revoked,create_time) VALUES(?,?,?,?,?,?,?,FALSE,?)",
                id, identityId, deviceId, hash(access), hash(refresh), now + ACCESS_TTL, now + REFRESH_TTL, now);
        return tokens(access, refresh, id, ACCESS_TTL, REFRESH_TTL);
    }

    private Map<String, Object> tokens(String access, String refresh, String id, long accessTtl, long refreshTtl) {
        return Map.of("loginStatus", "AUTHENTICATED", "bindingStatus", "APPROVED", "accessToken", access,
                "refreshToken", refresh, "tokenType", "Bearer", "expiresIn", accessTtl / 1000, "sessionId", id,
                "accessTokenExpiresIn", accessTtl / 1000, "refreshTokenExpiresIn", refreshTtl / 1000);
    }

    private Map<String, Object> requireUser(String identityId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT u.user_id,u.username,u.user_type,u.workspace_id FROM miniapp_identity i "
                + "JOIN manage_user u ON u.user_id=i.user_id AND u.workspace_id=i.workspace_id "
                + "JOIN manage_workspace w ON w.workspace_id=u.workspace_id "
                + "WHERE i.identity_id=? AND i.app_id=? AND i.binding_status='APPROVED' AND u.user_type IN (1,2)",
                identityId, properties.getWechat().getAppId());
        if (rows.size() != 1) throw denied();
        return rows.get(0);
    }

    private String token(String prefix) { byte[] bytes = new byte[32]; random.nextBytes(bytes); return prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    public static String hash(String value) {
        try { byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(); for (byte b : bytes) result.append(String.format("%02x", b)); return result.toString();
        } catch (Exception exception) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    public static void validate(String value, int min, int max) {
        if (value == null || value.trim().length() < min || value.length() > max) throw new MiniAppException(HttpStatus.BAD_REQUEST, MiniAppErrorCode.VALIDATION_ERROR, "请求字段缺失或长度不符合要求");
    }
    private static MiniAppException expired() { return new MiniAppException(HttpStatus.UNAUTHORIZED, MiniAppErrorCode.AUTH_TOKEN_EXPIRED, "登录凭证无效或已过期，请重新登录"); }
    private static MiniAppException denied() { return new MiniAppException(HttpStatus.FORBIDDEN, MiniAppErrorCode.ACCOUNT_DISABLED, "账号未获准访问当前工作空间，请联系管理员"); }
}
