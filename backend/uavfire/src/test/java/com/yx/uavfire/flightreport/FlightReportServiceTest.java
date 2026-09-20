package com.yx.uavfire.flightreport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yx.uavfire.miniapp.MiniAppTestDatabase;
import com.yx.uavfire.miniapp.web.MiniAppException;
import com.yx.uavfire.msdk.model.MsdkDeviceStateDTO;
import org.junit.jupiter.api.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FlightReportServiceTest {
    MiniAppTestDatabase db;FlightRecordWriter writer;FlightReportService reader;long t;ObjectMapper mapper=new ObjectMapper();
    @BeforeEach void setup()throws Exception{
        db=new MiniAppTestDatabase();t=System.currentTimeMillis()-10000;
        db.jdbc.execute("ALTER TABLE planned_wayline ADD flight_id VARCHAR(128)");
        db.jdbc.execute("ALTER TABLE planned_wayline ADD drone_sn VARCHAR(64)");
        db.jdbc.execute("ALTER TABLE planned_wayline ADD payload_model_key VARCHAR(64)");
        for(String sql:Files.readString(Path.of("../sql/migrations/2026-09-16-flight-report.sql")).split(";"))if(!sql.isBlank())db.jdbc.execute(sql);
        db.jdbc.execute("CREATE TABLE media_file(file_id VARCHAR(64),file_name VARCHAR(128),create_time BIGINT,workspace_id VARCHAR(64),job_id VARCHAR(128))");
        db.jdbc.update("INSERT INTO planned_wayline(id,planned_wayline_id,workspace_id,name,aircraft_model_key,aircraft_sn,task_status,flight_id,executed_time,last_progress_time) VALUES(1,'task-a','ws-a','测试任务','M4T','sn-a','executing','flight-a',?,?),(2,'task-b','ws-b','其他工作空间','M300','sn-b','executing','flight-b',?,?)",t,t,t,t);
        writer=new FlightRecordWriter(db.jdbc,mapper);reader=new FlightReportService(db.jdbc,mapper);
    }
    void sample(long offset,Boolean flying,int battery,double speed,double height,String mode){writer.captureState(new MsdkDeviceStateDTO().setAircraftSn("sn-a").setOnline(true).setUpdatedAt(t+offset).setFlying(flying).setFlightMode(mode).setBatteryPercent(battery).setHorizontalSpeed(speed).setHeight(height).setLatitude(34.0+offset/100000000.0).setLongitude(108.0));}
    @SuppressWarnings("unchecked") Map<String,Object> metrics(Map<String,Object> r){return (Map<String,Object>)r.get("metrics");}
    @Test void recordsRealTakeoffReturnAndLandingAndSurvivesReaderRecreation(){
        sample(0,false,95,0,0,"ON_GROUND");sample(1000,true,90,4,10,"GPS_NORMAL");sample(4000,true,70,6,30,"GO_HOME");sample(8000,false,65,0,0,"ON_GROUND");
        Map<String,Object> result=new FlightReportService(db.jdbc,mapper).report("ws-a","task-a",null,1,50,null),m=metrics(result);
        assertEquals(7L,m.get("flightDurationSeconds"));assertEquals(90.0,m.get("takeoffBatteryPercent"));assertEquals(70.0,m.get("returnBatteryPercent"));assertEquals(65.0,m.get("landingBatteryPercent"));assertEquals(25.0,m.get("batteryConsumedPercentagePoints"));assertEquals(5.0,m.get("averageSpeedMs"));assertEquals(6.0,m.get("maximumSpeedMs"));assertEquals(30.0,m.get("maximumHeightM"));assertEquals("DJI Matrice 4T",result.get("aircraftName"));
        assertEquals(t+8000,db.jdbc.queryForObject("SELECT closed_at FROM flight_report_run",Long.class));
    }
    @Test void missingPhysicalEvidenceIsNotReplacedWithPlannedValues(){
        db.jdbc.update("UPDATE planned_wayline SET default_height=80 WHERE planned_wayline_id='task-a'");
        Map<String,Object> empty=reader.report("ws-a","task-a",null,1,50,null);assertNull(metrics(empty).get("flightDurationSeconds"));assertNull(metrics(empty).get("maximumHeightM"));
        sample(1000,true,80,5,20,"GPS_NORMAL");sample(5000,false,70,0,0,"ON_GROUND");
        Map<String,Object> partial=reader.report("ws-a","task-a",null,1,50,null);assertNull(metrics(partial).get("takeoffAt"));assertNull(metrics(partial).get("flightDurationSeconds"));assertNull(metrics(partial).get("batteryConsumedPercentagePoints"));
    }
    @Test void reportUsesExecutionSnapshotAfterTaskIsEdited(){
        db.jdbc.update("UPDATE planned_wayline SET default_height=80 WHERE planned_wayline_id='task-a'");
        sample(0,false,90,0,0,"GROUND");
        db.jdbc.update("UPDATE planned_wayline SET default_height=120,executed_time=? WHERE planned_wayline_id='task-a'",t+8000);
        var report=reader.report("ws-a","task-a",null,1,50,null);
        assertEquals("EXECUTION_SNAPSHOT",report.get("plannedSource"));assertEquals(80.0,((Map<?,?>)report.get("planned")).get("defaultHeight"));
        assertEquals(t,((Map<?,?>)((List<?>)report.get("recordedMilestones")).get(0)).get("time"));
    }
    @Test void stateChangeAcrossAnObservationGapDoesNotInventTakeoffTime(){
        t=System.currentTimeMillis()-30000;
        sample(0,false,95,0,0,"GROUND");sample(5000,null,93,0,0,"UNKNOWN");sample(10000,null,91,1,2,"UNKNOWN");sample(15000,null,89,2,5,"UNKNOWN");sample(20000,true,85,3,10,"GPS_NORMAL");sample(26000,false,80,0,0,"GROUND");
        var m=metrics(reader.report("ws-a","task-a",null,1,50,null));
        assertNull(m.get("takeoffAt"));assertNull(m.get("flightDurationSeconds"));assertNull(m.get("batteryConsumedPercentagePoints"));
    }
    @Test void rejectsForeignTaskRunAndMismatchedDroneAndDeduplicatesSamples(){
        sample(0,false,90,0,0,"GROUND");sample(0,false,90,0,0,"GROUND");
        assertEquals(1L,db.jdbc.queryForObject("SELECT COUNT(*) FROM flight_report_record",Long.class));
        String run=db.jdbc.queryForObject("SELECT run_id FROM flight_report_run",String.class);
        assertThrows(MiniAppException.class,()->reader.report("ws-b","task-a",null,1,50,null));
        assertThrows(MiniAppException.class,()->reader.report("ws-b","task-b",run,1,50,null));
        writer.captureAgent("wrong-drone","flight-a","wayline_state_change",mapper.createObjectNode(),"x",t);
        assertEquals(1L,db.jdbc.queryForObject("SELECT COUNT(*) FROM flight_report_record",Long.class));
    }
    @Test void paginatedRecordsHaveStableSnapshotWhileNewRecordsArrive(){
        sample(0,false,90,0,0,"GROUND");sample(1000,true,89,3,10,"GPS_NORMAL");
        Map<String,Object> first=reader.report("ws-a","task-a",null,1,1,null);Long snapshot=((Number)first.get("snapshotRecordId")).longValue();
        sample(2000,true,88,4,15,"GPS_NORMAL");
        Map<?,?> page=(Map<?,?>)reader.report("ws-a","task-a",null,2,1,snapshot).get("records");
        assertEquals(2L,page.get("total"));assertEquals(false,page.get("hasMore"));
        assertEquals(3L,((Map<?,?>)reader.report("ws-a","task-a",null,1,50,null).get("records")).get("total"));
    }
    @Test void batteryJumpsDoNotProduceMisleadingConsumption(){
        sample(0,false,90,0,0,"GROUND");sample(1000,true,80,3,10,"GPS_NORMAL");sample(3000,true,95,3,10,"GO_HOME");sample(6000,false,70,0,0,"GROUND");
        assertNull(metrics(reader.report("ws-a","task-a",null,1,50,null)).get("batteryConsumedPercentagePoints"));
    }
    @Test void ingestionFailureRollsBackRunCreationWithoutThrowingIntoControlFlow(){
        db.jdbc.execute("DROP TABLE flight_report_record");assertDoesNotThrow(()->sample(0,false,90,0,0,"GROUND"));
        assertEquals(0L,db.jdbc.queryForObject("SELECT COUNT(*) FROM flight_report_run",Long.class));
    }
    @Test void noCoordinatesAndMultipleTakeoffsDoNotBecomeACompleteFlight(){
        sample(0,false,95,0,0,"GROUND");sample(1000,true,90,3,10,"GPS_NORMAL");sample(2000,false,85,0,0,"GROUND");sample(3000,true,80,3,10,"GPS_NORMAL");sample(4000,false,75,0,0,"GROUND");
        db.jdbc.update("UPDATE flight_report_record SET latitude=NULL,longitude=NULL");
        var report=reader.report("ws-a","task-a",null,1,50,null);
        assertNull(metrics(report).get("observedDistanceM"));assertNull(metrics(report).get("flightDurationSeconds"));assertNull(metrics(report).get("batteryConsumedPercentagePoints"));
    }
    @Test void relatedRecordsBeyondOneHundredRemainAccessibleAndWorkspaceScoped(){
        sample(0,false,90,0,0,"GROUND");sample(5000,true,89,3,10,"GPS_NORMAL");
        var report=reader.report("ws-a","task-a",null,1,50,null);String run=(String)report.get("runId");
        for(int i=0;i<101;i++)db.jdbc.update("INSERT INTO media_file VALUES(?,?,?,?,?)","f"+i,"image"+i,t+i,"ws-a","flight-a");
        db.jdbc.update("INSERT INTO media_file VALUES('foreign','private',?,'ws-b','flight-a')",t);
        var second=reader.related("ws-a","task-a",run,"media",2,100,null);
        assertEquals(101L,second.get("total"));assertEquals(1,((List<?>)second.get("items")).size());assertEquals(false,second.get("hasMore"));
        assertThrows(MiniAppException.class,()->reader.related("ws-b","task-b",run,"media",1,100,null));
    }
}
