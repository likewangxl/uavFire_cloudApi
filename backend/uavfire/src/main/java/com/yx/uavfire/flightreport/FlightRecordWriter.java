package com.yx.uavfire.flightreport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yx.uavfire.common.model.DeviceModelNames;
import com.yx.uavfire.msdk.model.MsdkDeviceStateDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Evidence ingestion only; it never issues device commands or changes a mission state. */
@Service
public class FlightRecordWriter {
    private static final Logger log=LoggerFactory.getLogger(FlightRecordWriter.class);
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TransactionTemplate transaction;
    public FlightRecordWriter(JdbcTemplate jdbc,ObjectMapper mapper){this.jdbc=jdbc;this.mapper=mapper;this.transaction=new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())));this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);}

    public void captureAgent(String drone,String flight,String method,JsonNode data,String eventId,Long sourceTime){
        if(blank(drone)||blank(flight))return;
        try {
            inTransaction(() -> {
            List<Map<String,Object>> tasks=jdbc.queryForList("SELECT * FROM planned_wayline WHERE flight_id=? AND (aircraft_sn=? OR drone_sn=?)",flight,drone,drone);
            if(tasks.size()!=1)return;
            Map<String,Object> task=tasks.get(0);String run=ensureRun(task,drone,observedTime(sourceTime));
            JsonNode aircraft=field(data,"aircraft");
            insert(run,String.valueOf(task.get("workspace_id")),"WAYLINE_AGENT",method,data,eventId,sourceTime,
                    number(aircraft,"lat","latitude"),number(aircraft,"lng","longitude"),number(aircraft,"alt_relative","altRelative"),number(aircraft,"alt_ellipsoid","altEllipsoid"),number(aircraft,"speed"),null,
                    integer(data,"battery_percent","batteryPercent"),bool(data,"flying"),text(data,"flight_mode","flightMode"),null,null,null);
            });
        }catch(Exception error){log.warn("Flight evidence capture unavailable: source=WAYLINE_AGENT, type={}",error.getClass().getSimpleName());}
    }

    public void captureState(MsdkDeviceStateDTO state){
        if(state==null||blank(state.getAircraftSn())||!Boolean.TRUE.equals(state.getOnline()))return;
        long now=System.currentTimeMillis();Long time=state.getUpdatedAt();
        if(time==null||Math.abs(now-time)>60_000)return;
        try {
            inTransaction(() -> {
            String drone=state.getAircraftSn();
            List<Map<String,Object>> tasks=jdbc.queryForList("SELECT * FROM planned_wayline WHERE (aircraft_sn=? OR drone_sn=?) AND flight_id IS NOT NULL AND flight_id<>'' AND task_status IN ('ready','executing','paused','published','publishing') AND (last_progress_time>? OR executed_time>? OR prepared_time>?)",drone,drone,now-1800000,now-1800000,now-1800000);
            String run,workspace;
            if(tasks.size()==1){run=ensureRun(tasks.get(0),drone,observedTime(time));workspace=String.valueOf(tasks.get(0).get("workspace_id"));}
            else if(tasks.isEmpty()){
                List<Map<String,Object>> runs=jdbc.queryForList("SELECT run_id,workspace_id FROM flight_report_run WHERE aircraft_sn=? AND closed_at IS NULL AND airborne_seen=TRUE AND last_record_at>?",drone,now-1800000);
                if(runs.size()!=1)return;run=String.valueOf(runs.get(0).get("run_id"));workspace=String.valueOf(runs.get(0).get("workspace_id"));
            } else return; // Ambiguous task association is not guessed.
            // The older "elevation" field uses a relative-altitude SDK key; never label it as ellipsoid altitude.
            insert(run,workspace,"MSDK_STATE","telemetry",mapper.valueToTree(state),drone+":"+time,time,state.getLatitude(),state.getLongitude(),state.getHeight(),null,state.getHorizontalSpeed(),state.getVerticalSpeed(),state.getBatteryPercent(),state.getFlying(),state.getFlightMode(),state.getWindSpeed(),state.getGpsCount(),state.getPositionFixed());
            });
        }catch(Exception error){log.warn("Flight evidence capture unavailable: source=MSDK_STATE, type={}",error.getClass().getSimpleName());}
    }

    private String ensureRun(Map<String,Object> task,String drone,long now)throws Exception {
        String id=UUID.randomUUID().toString(),ws=String.valueOf(task.get("workspace_id")),taskId=String.valueOf(task.get("planned_wayline_id")),flight=String.valueOf(task.get("flight_id"));
        Map<String,Object> snapshot=new LinkedHashMap<>();
        for(String key:List.of("name","aircraft_model_key","payload_model_key","default_height","max_speed","rth_altitude","takeoff_security_height","finish_action","rc_lost_action","route_kind","total_waypoints","creator","publisher","create_time","publish_time","prepared_time","executed_time"))snapshot.put(key,task.get(key));
        snapshot.put("aircraft_model_key",DeviceModelNames.full((String)task.get("aircraft_model_key")));
        jdbc.update("INSERT INTO flight_report_run(run_id,workspace_id,task_id,flight_id,aircraft_sn,aircraft_name,task_snapshot_json,first_record_at,last_record_at,airborne_seen) VALUES(?,?,?,?,?,?,?,?,?,FALSE) ON DUPLICATE KEY UPDATE task_id=VALUES(task_id)",id,ws,taskId,flight,drone,DeviceModelNames.full((String)task.get("aircraft_model_key")),mapper.writeValueAsString(snapshot),now,now);
        return jdbc.queryForObject("SELECT run_id FROM flight_report_run WHERE workspace_id=? AND task_id=? AND flight_id=? AND aircraft_sn=?",String.class,ws,taskId,flight,drone);
    }

    private void insert(String run,String workspace,String source,String kind,JsonNode payload,String id,Long timestamp,Double lat,Double lng,Double height,Double altitude,Double speed,Double vertical,Integer battery,Boolean flying,String mode,Double wind,Integer gps,Boolean fixed)throws Exception {
        long received=System.currentTimeMillis(),observed=observedTime(timestamp);
        String json=mapper.writeValueAsString(payload),fingerprint=hash(run+"|"+source+"|"+kind+"|"+(blank(id)?observed+"|"+json:id));
        jdbc.update("INSERT INTO flight_report_record(run_id,workspace_id,event_key,source,kind,observed_at,received_at,latitude,longitude,relative_height_m,altitude_m,speed_ms,vertical_speed_ms,battery_percent,flying,flight_mode,wind_speed_ms,gps_count,position_fixed,payload_json) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE event_key=VALUES(event_key)",
                run,workspace,fingerprint,source,kind,observed,received,finite(lat,-90,90),finite(lng,-180,180),finite(height,-1000,15000),finite(altitude,-1000,25000),finite(speed,0,150),finite(vertical,-100,100),battery!=null&&battery>=0&&battery<=100?battery:null,flying,mode,finite(wind,0,100),gps,fixed,json);
        if(Boolean.FALSE.equals(flying))jdbc.update("UPDATE flight_report_run SET closed_at=? WHERE run_id=? AND airborne_seen=TRUE AND last_record_at<=? AND closed_at IS NULL",observed,run,observed);
        jdbc.update("UPDATE flight_report_run SET first_record_at=LEAST(first_record_at,?),last_record_at=GREATEST(last_record_at,?),airborne_seen=CASE WHEN ?=TRUE THEN TRUE ELSE airborne_seen END WHERE run_id=?",observed,observed,Boolean.TRUE.equals(flying),run);
    }
    private static Double finite(Double value,double min,double max){return value!=null&&Double.isFinite(value)&&value>=min&&value<=max?value:null;}
    private static long observedTime(Long timestamp){long now=System.currentTimeMillis();return timestamp!=null&&timestamp>946684800000L&&timestamp<=now+5000?timestamp:now;}
    private interface WriteAction { void run() throws Exception; }
    private void inTransaction(WriteAction action){transaction.execute(status->{try{action.run();return null;}catch(Exception e){throw new IllegalStateException("Flight record transaction failed",e);}});}
    private static boolean blank(String value){return value==null||value.isBlank();}
    private static JsonNode field(JsonNode n,String...names){if(n!=null)for(String name:names)if(n.hasNonNull(name))return n.get(name);return null;}
    private static Double number(JsonNode n,String...keys){JsonNode v=field(n,keys);return v!=null&&v.isNumber()?v.doubleValue():null;}
    private static Integer integer(JsonNode n,String...keys){JsonNode v=field(n,keys);return v!=null&&v.isIntegralNumber()?v.intValue():null;}
    private static Boolean bool(JsonNode n,String...keys){JsonNode v=field(n,keys);return v!=null&&v.isBoolean()?v.booleanValue():null;}
    private static String text(JsonNode n,String...keys){JsonNode v=field(n,keys);return v==null?null:v.asText();}
    private static String hash(String value)throws Exception{byte[] bytes=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format("%02x",b));return out.toString();}
}
