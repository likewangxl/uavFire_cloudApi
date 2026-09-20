package com.yx.uavfire.miniapp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yx.uavfire.common.model.CustomClaim;
import com.yx.uavfire.miniapp.service.MiniAppReadService;
import com.yx.uavfire.miniapp.web.MiniAppException;
import com.yx.uavfire.msdk.service.MsdkDeviceStateService;
import com.yx.uavfire.msdk.model.MsdkDeviceStateDTO;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MiniAppReadServiceTest {
    MiniAppTestDatabase db; MiniAppReadService reads; MsdkDeviceStateService devices;
    CustomClaim a=new CustomClaim("user-a","leader-a",1,"ws-a"),b=new CustomClaim("user-b","leader-b",1,"ws-b");
    @BeforeEach void setup() throws Exception {
        db=new MiniAppTestDatabase();devices=new MsdkDeviceStateService(System::currentTimeMillis);reads=new MiniAppReadService(db.jdbc,new ObjectMapper(),devices);
        db.jdbc.update("INSERT INTO planned_wayline(id,planned_wayline_id,workspace_id,name,task_status,executed_time,create_time,update_time) VALUES(1,'task-a','ws-a','巡检 A','executing',?,?,?),(2,'task-b','ws-b','巡检 B','completed',?,?,?)",1L,1L,2L,1L,1L,2L);
        db.jdbc.update("INSERT INTO fire_event(id,event_id,workspace_id,status,deleted,update_time) VALUES(1,'event-a','ws-a','CANDIDATE',0,1),(2,'event-b','ws-b','NEW',0,2),(3,'deleted-a','ws-a','NEW',1,3)");
    }
    @Test void everyListAndDetailAreWorkspaceScoped() {
        assertEquals(1L,reads.tasks(a,1,20,"","",false,false).get("total"));
        assertEquals(1L,reads.events(a,1,20,"").get("total"));
        assertThrows(MiniAppException.class,()->reads.task(a,"task-b"));
        assertThrows(MiniAppException.class,()->reads.event(a,"event-b"));
        assertThrows(MiniAppException.class,()->reads.event(a,"deleted-a"));
        assertThrows(MiniAppException.class,()->reads.report(a,"task-b"));
        assertEquals("ws-a",((Map<?,?>)reads.me(a).get("workspace")).get("workspaceId"));
    }
    @Test void bookmarksArePersistentUserScopedAndCannotWriteForeignReport() {
        assertThrows(MiniAppException.class,()->reads.saveReport(a,"task-b",true));
        reads.saveReport(a,"task-a",true); assertEquals(true,reads.report(a,"task-a").get("saved"));
        assertEquals(1L,reads.tasks(a,1,20,"","",true,true).get("total"));
        assertEquals(0L,reads.tasks(b,1,20,"","",true,true).get("total"));
        reads.saveReport(a,"task-a",false); assertEquals(0L,reads.tasks(a,1,20,"","",true,true).get("total"));
    }
    @Test void missingOrStaleDeviceDataIsUnknownAndForeignDeviceIsExcluded() {
        db.jdbc.update("INSERT INTO manage_device VALUES('sn-a','ws-a','设备 A',0,TRUE),('sn-b','ws-b','设备 B',0,TRUE)");
        devices.upsert(new MsdkDeviceStateDTO().setAircraftSn("sn-b").setOnline(true).setUpdatedAt(System.currentTimeMillis()));
        assertEquals(1,reads.devices(a).size()); assertNull(reads.devices(a).get(0).get("online"));
        Map<?,?> summary=reads.summary(a); assertNull(((Map<?,?>)summary.get("metrics")).get("onlineAircraft"));
        assertEquals("STALE",reads.task(a,"task-a").get("dataStatus"));
    }
    @Test void validatesPaginationAndTreatsSearchAsLiteral() {
        assertThrows(MiniAppException.class,()->reads.tasks(a,0,20,"","",false,false));
        assertThrows(MiniAppException.class,()->reads.events(a,1,101,""));
        assertEquals(0L,reads.tasks(a,1,20,"%' OR 1=1 --","",false,false).get("total"));
    }
    @Test void onlineAircraftExcludesControllersAndRequiresCompleteInventory() {
        db.jdbc.update("INSERT INTO manage_device VALUES('drone-a','ws-a','飞机',0,TRUE),('controller-a','ws-a','遥控器',2,TRUE)");
        devices.upsert(new MsdkDeviceStateDTO().setAircraftSn("drone-a").setOnline(true).setUpdatedAt(System.currentTimeMillis()));
        devices.upsert(new MsdkDeviceStateDTO().setAircraftSn("controller-a").setOnline(true).setUpdatedAt(System.currentTimeMillis()));
        assertEquals(1L,((Map<?,?>)reads.summary(a).get("metrics")).get("onlineAircraft"));
        for (int i=0;i<500;i++) db.jdbc.update("INSERT INTO manage_device VALUES(?,'ws-a','飞机',0,TRUE)","extra-"+i);
        assertNull(((Map<?,?>)reads.summary(a).get("metrics")).get("onlineAircraft"));
    }
    @Test void notificationPreferencesDoNotGrantWechatSubscription() {
        reads.savePreferences(a,Map.of("urgentEvents",true));
        assertEquals(true,reads.preferences(a).get("urgentEvents"));assertEquals(false,reads.preferences(b).get("urgentEvents"));
        assertEquals(false,reads.preferences(a).get("wechatSubscriptionEnabled"));
        assertThrows(MiniAppException.class,()->reads.savePreferences(a,Map.of("flightControlEnabled",true)));
    }
    @Test void mobileFilterGroupsIncludeAllMatchingStatesWithoutCrossingWorkspaces() {
        db.jdbc.update("INSERT INTO planned_wayline(id,planned_wayline_id,workspace_id,name,task_status,update_time) VALUES(3,'done-a','ws-a','A','completed',3),(4,'finished-a','ws-a','B','finished',4)");
        assertEquals(2L,reads.tasks(a,1,20,"","completed",false,false).get("total"));
        assertEquals(1L,reads.tasks(a,1,20,"","finished",false,false).get("total"));
        db.jdbc.update("INSERT INTO fire_event(id,event_id,workspace_id,status,deleted,update_time) VALUES(4,'new-a','ws-a','NEW',0,4),(5,'low-a','ws-a','LOW_CONFIDENCE',0,5),(6,'ignored-a','ws-a','IGNORED',0,6)");
        assertEquals(3L,reads.events(a,1,20,"PENDING_REVIEW").get("total"));
        assertEquals(1L,reads.events(b,1,20,"PENDING_REVIEW").get("total"));
    }
}
