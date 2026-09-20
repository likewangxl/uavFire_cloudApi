package com.yx.uavfire.miniapp;

import com.yx.uavfire.miniapp.service.MiniAppSessionService;
import com.yx.uavfire.miniapp.web.MiniAppException;
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class MiniAppSessionServiceTest {
    MiniAppTestDatabase db;
    @BeforeEach void setup() throws Exception { db = new MiniAppTestDatabase(); }
    Map<String,Object> approvedLogin() { db.sessions.login("openid-a", "device-12345"); db.approve("openid-a"); return db.sessions.login("openid-a", "device-12345"); }

    @Test void bindingRequiresOneTimeTicketAndApproval() {
        Map<String,Object> login = db.sessions.login("openid-a", "device-12345");
        assertEquals("BINDING_REQUIRED", login.get("loginStatus")); assertFalse(login.containsKey("accessToken"));
        String ticket = (String) login.get("bindingTicket");
        assertEquals("PENDING_APPROVAL", db.sessions.requestBinding(ticket,"leader-a","测试姓名","测试组织").get("loginStatus"));
        assertThrows(MiniAppException.class, () -> db.sessions.requestBinding(ticket,"leader-a","测试","测试"));
        assertEquals("PENDING_APPROVAL", db.sessions.login("openid-a","device-12345").get("loginStatus"));
        db.approve("openid-a");
        assertEquals("ws-a", db.sessions.authenticate((String) db.sessions.login("openid-a","device-12345").get("accessToken")).getWorkspaceId());
    }
    @Test void tokensAreHashedRotateAndLogoutRevokesRefresh() {
        Map<String,Object> login=approvedLogin(); String access=(String)login.get("accessToken"), refresh=(String)login.get("refreshToken");
        assertEquals(MiniAppSessionService.hash(access),db.jdbc.queryForObject("SELECT access_hash FROM miniapp_session WHERE session_id=?",String.class,login.get("sessionId")));
        Map<String,Object> rotated=db.sessions.refresh(refresh,"device-12345");
        assertThrows(MiniAppException.class,()->db.sessions.authenticate(access));
        assertThrows(MiniAppException.class,()->db.sessions.refresh(refresh,"device-12345"));
        db.sessions.logout((String)rotated.get("accessToken"));
        assertThrows(MiniAppException.class,()->db.sessions.refresh((String)rotated.get("refreshToken"),"device-12345"));
    }
    @Test void concurrentRefreshHasExactlyOneWinner() throws Exception {
        String refresh=(String)approvedLogin().get("refreshToken");
        ExecutorService pool=Executors.newFixedThreadPool(2); CountDownLatch start=new CountDownLatch(1);
        Callable<Boolean> job=()->{start.await(); try {db.sessions.refresh(refresh,"device-12345");return true;}catch(MiniAppException ex){return false;}};
        try {Future<Boolean> a=pool.submit(job),b=pool.submit(job);start.countDown();assertNotEquals(a.get(5,TimeUnit.SECONDS),b.get(5,TimeUnit.SECONDS));}finally{pool.shutdownNow();}
    }
    @Test void changedWorkspaceAndDisabledBindingImmediatelyDenyExistingSessions() {
        String access=(String)approvedLogin().get("accessToken");
        db.jdbc.update("UPDATE manage_user SET workspace_id='ws-b' WHERE user_id='user-a'");
        assertThrows(MiniAppException.class,()->db.sessions.authenticate(access));
        db.jdbc.update("UPDATE manage_user SET workspace_id='ws-a' WHERE user_id='user-a'");
        db.jdbc.update("UPDATE miniapp_identity SET binding_status='DISABLED'");
        assertThrows(MiniAppException.class,()->db.sessions.authenticate(access));
        assertThrows(MiniAppException.class,()->db.sessions.login("openid-a","device-12345"));
    }
    @Test void rejectsLegacyJwtWrongDeviceAndExpiredAccess() {
        Map<String,Object> login=approvedLogin();
        assertThrows(MiniAppException.class,()->db.sessions.authenticate("eyJlegacy.management.jwt"));
        assertThrows(MiniAppException.class,()->db.sessions.refresh((String)login.get("refreshToken"),"other-device"));
        db.jdbc.update("UPDATE miniapp_session SET access_expires_at=0");
        assertThrows(MiniAppException.class,()->db.sessions.authenticate((String)login.get("accessToken")));
    }
    @Test void sameDeviceLoginRevokesOldSessionButOtherDeviceRemains() {
        String first=(String)approvedLogin().get("accessToken");
        String other=(String)db.sessions.login("openid-a","device-other").get("accessToken");
        db.sessions.login("openid-a","device-12345");
        assertThrows(MiniAppException.class,()->db.sessions.authenticate(first));
        assertNotNull(db.sessions.authenticate(other));
    }
}
