package com.yx.uavfire.flightreport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yx.uavfire.common.model.DeviceModelNames;
import com.yx.uavfire.miniapp.web.*;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;

/** Read-only evidence report. Planned limits are never substituted for measured flight metrics. */
@Service
public class FlightReportService {
    private final JdbcTemplate jdbc;private final ObjectMapper mapper;
    public FlightReportService(JdbcTemplate jdbc,ObjectMapper mapper){this.jdbc=jdbc;this.mapper=mapper;}

    public Map<String,Object> report(String workspace,String taskId,String requestedRun,int page,int size,Long snapshot) {
        if(page<1||page>100000||size<1||size>100)throw invalid();
        List<Map<String,Object>> tasks=jdbc.queryForList("SELECT * FROM planned_wayline WHERE workspace_id=? AND planned_wayline_id=?",workspace,taskId);
        if(tasks.size()!=1)throw missing();Map<String,Object> task=tasks.get(0);
        Map<String,Object> out=new LinkedHashMap<>();out.put("version",1);out.put("taskId",taskId);
        out.put("taskName",DeviceModelNames.text(str(task,"name")));out.put("aircraftName",DeviceModelNames.full(str(task,"aircraft_model_key")));
        out.put("aircraftSn",first(task.get("aircraft_sn"),task.get("drone_sn")));out.put("workspaceId",workspace);
        out.put("taskStatus",first(task.get("task_status"),task.get("status")));
        out.put("planned",planned(task));out.put("recordedMilestones",milestones(task));
        List<Map<String,Object>> runs;
        try {runs=jdbc.queryForList("SELECT run_id,flight_id,aircraft_sn,aircraft_name,task_snapshot_json,first_record_at,last_record_at,closed_at FROM flight_report_run WHERE workspace_id=? AND task_id=? ORDER BY first_record_at DESC,run_id DESC",workspace,taskId);}
        catch(DataAccessException e){return noRecords(out,"记录存储暂不可读取；不能确认是否存在执行历史");}
        out.put("runs",camelRows(runs.stream().map(row->{Map<String,Object> copy=new LinkedHashMap<>(row);copy.remove("task_snapshot_json");return copy;}).collect(java.util.stream.Collectors.toList())));
        if(runs.isEmpty()) { if(requestedRun!=null&&!requestedRun.isBlank())throw missing();return noRecords(out,"该任务没有持久化飞行记录；历史未采集的数据无法补算"); }
        Map<String,Object> run=runs.stream().filter(r->requestedRun==null||requestedRun.isBlank()||requestedRun.equals(r.get("run_id"))).findFirst().orElseThrow(FlightReportService::missing);
        try{Map<String,Object> captured=mapper.readValue(str(run,"task_snapshot_json"),Map.class);out.put("planned",planned(captured));out.put("recordedMilestones",milestones(captured));out.put("plannedSource","EXECUTION_SNAPSHOT");}catch(Exception ignored){out.put("plannedSource","CURRENT_TASK");}
        String runId=str(run,"run_id");out.put("runId",runId);out.put("flightId",run.get("flight_id"));out.put("aircraftName",DeviceModelNames.full(str(run,"aircraft_name")));out.put("aircraftSn",run.get("aircraft_sn"));
        long max=jdbc.queryForObject("SELECT COALESCE(MAX(record_id),0) FROM flight_report_record WHERE workspace_id=? AND run_id=?",Long.class,workspace,runId);
        long limit=snapshot==null?max:Math.min(max,Math.max(0,snapshot));out.put("snapshotRecordId",limit);
        Accumulator metrics=new Accumulator(mapper);
        jdbc.query("SELECT record_id,observed_at,source,kind,latitude,longitude,relative_height_m,altitude_m,speed_ms,vertical_speed_ms,battery_percent,flying,flight_mode,wind_speed_ms,gps_count,position_fixed,payload_json FROM flight_report_record WHERE workspace_id=? AND run_id=? AND record_id<=? ORDER BY observed_at,record_id",rs->{
            Map<String,Object> row=new LinkedHashMap<>();for(int i=1;i<=rs.getMetaData().getColumnCount();i++)row.put(rs.getMetaData().getColumnLabel(i).toLowerCase(Locale.ROOT),rs.getObject(i));metrics.add(row);
        },workspace,runId,limit);
        out.put("metrics",metrics.result());out.put("quality",metrics.quality());out.put("series",metrics.series());
        long count=metrics.count;List<Map<String,Object>> records=jdbc.queryForList("SELECT record_id,source,kind,observed_at,received_at,latitude,longitude,relative_height_m,altitude_m,speed_ms,vertical_speed_ms,battery_percent,flying,flight_mode,wind_speed_ms,gps_count,position_fixed,payload_json FROM flight_report_record WHERE workspace_id=? AND run_id=? AND record_id<=? ORDER BY observed_at,record_id LIMIT ? OFFSET ?",workspace,runId,limit,size,(page-1)*size);
        out.put("records",Map.of("items",recordRows(records),"total",count,"page",page,"pageSize",size,"hasMore",(long)page*size<count));
        out.put("timeline",metrics.timeline);
        out.put("relatedEvents",relatedEvents(workspace,str(run,"aircraft_sn"),metrics.first,metrics.last));
        out.put("media",media(workspace,str(run,"flight_id")));
        out.put("generatedAt",System.currentTimeMillis());return out;
    }

    private Map<String,Object> noRecords(Map<String,Object> out,String reason){
        out.putIfAbsent("runs",List.of());out.put("metrics",new Accumulator(mapper).result());
        out.put("quality",Map.of("status","NOT_RECORDED","recordCount",0,"warnings",List.of(reason),"missingFields",List.of("起飞/落地时间","飞行时长","实测速度与高度","起飞/返航/落地电量","全程事件与遥测")));
        out.put("records",Map.of("items",List.of(),"total",0,"page",1,"pageSize",50,"hasMore",false));out.put("timeline",List.of());out.put("series",List.of());
        out.put("relatedEvents",Map.of("available",false,"items",List.of(),"association","缺少执行时间范围，未自动关联火情"));out.put("media",Map.of("available",false,"items",List.of()));return out;
    }
    public Map<String,Object> related(String workspace,String taskId,String runId,String kind,int page,int size,Long snapshot){
        if(page<1||page>100000||size<1||size>100||!List.of("events","media").contains(kind))throw invalid();
        List<Map<String,Object>> runs=jdbc.queryForList("SELECT r.* FROM flight_report_run r JOIN planned_wayline t ON t.planned_wayline_id=r.task_id AND t.workspace_id=r.workspace_id WHERE r.workspace_id=? AND r.task_id=? AND r.run_id=?",workspace,taskId,runId);
        if(runs.size()!=1)throw missing();Map<String,Object> run=runs.get(0);List<Map<String,Object>> rows;long total;
        if("events".equals(kind)){
            Map<String,Object> times=jdbc.queryForMap("SELECT MIN(observed_at) AS start_time,MAX(observed_at) AS end_time FROM flight_report_record WHERE workspace_id=? AND run_id=? AND record_id<=?",workspace,runId,snapshot==null?Long.MAX_VALUE:snapshot);
            Object[] args={workspace,run.get("aircraft_sn"),times.get("start_time"),times.get("end_time")};
            String where=" WHERE workspace_id=? AND device_sn=? AND (deleted IS NULL OR deleted=0) AND event_timestamp BETWEEN ? AND ?";
            total=jdbc.queryForObject("SELECT COUNT(*) FROM fire_event"+where,Long.class,args);
            List<Object> paged=new ArrayList<>(Arrays.asList(args));paged.add(size);paged.add((page-1)*size);
            rows=jdbc.queryForList("SELECT event_id,status,fire_level,confidence,event_timestamp,lat,lng,geo_quality FROM fire_event"+where+" ORDER BY event_timestamp,event_id LIMIT ? OFFSET ?",paged.toArray());
        }else{
            total=jdbc.queryForObject("SELECT COUNT(*) FROM media_file WHERE workspace_id=? AND job_id=?",Long.class,workspace,run.get("flight_id"));
            rows=jdbc.queryForList("SELECT file_id,file_name,create_time FROM media_file WHERE workspace_id=? AND job_id=? ORDER BY create_time,file_id LIMIT ? OFFSET ?",workspace,run.get("flight_id"),size,(page-1)*size);
        }
        return Map.of("items",camelRows(rows),"total",total,"page",page,"pageSize",size,"hasMore",(long)page*size<total);
    }
    private Map<String,Object> planned(Map<String,Object> t){
        Map<String,Object> p=new LinkedHashMap<>();for(String key:List.of("default_height","max_speed","rth_altitude","takeoff_security_height","global_transitional_speed","finish_action","exit_on_rc_lost","rc_lost_action","route_kind","total_waypoints","creator","publisher","create_time","publish_time"))p.put(camel(key),t.get(key));
        p.put("payloadName",DeviceModelNames.full(str(t,"payload_model_key")));p.put("note","计划参数，不能作为实际飞行数据");return p;
    }
    private List<Map<String,Object>> milestones(Map<String,Object> task){
        List<Map<String,Object>> result=new ArrayList<>();String[][] fields={{"create_time","创建任务"},{"publish_time","发布航线"},{"prepared_time","任务准备记录"},{"executed_time","执行请求记录"},{"last_progress_time","最近进度记录"}};
        for(String[] f:fields){Long time=lng(task.get(f[0]));if(time!=null&&time>0)result.add(Map.of("time",time,"title",f[1],"source","TASK_RECORD"));}return result;
    }
    private Map<String,Object> relatedEvents(String ws,String sn,Long start,Long end){
        if(start==null||end==null||sn==null)return Map.of("available",false,"items",List.of(),"association","缺少关联条件");
        try {
            long total=jdbc.queryForObject("SELECT COUNT(*) FROM fire_event WHERE workspace_id=? AND device_sn=? AND (deleted IS NULL OR deleted=0) AND event_timestamp BETWEEN ? AND ?",Long.class,ws,sn,start,end);
            List<Map<String,Object>> rows=jdbc.queryForList("SELECT event_id,status,fire_level,confidence,event_timestamp,lat,lng,geo_quality FROM fire_event WHERE workspace_id=? AND device_sn=? AND (deleted IS NULL OR deleted=0) AND event_timestamp BETWEEN ? AND ? ORDER BY event_timestamp,event_id LIMIT 100",ws,sn,start,end);
            return Map.of("available",true,"items",camelRows(rows),"total",total,"hasMore",total>100,"association","同设备、同记录时段的候选关联；不代表已确认任务归属");
        }catch(DataAccessException e){return Map.of("available",false,"items",List.of(),"association","火情记录暂不可读取");}
    }
    private Map<String,Object> media(String ws,String flight){
        try{
            long total=jdbc.queryForObject("SELECT COUNT(*) FROM media_file WHERE workspace_id=? AND job_id=?",Long.class,ws,flight);
            List<Map<String,Object>> rows=jdbc.queryForList("SELECT file_id,file_name,create_time FROM media_file WHERE workspace_id=? AND job_id=? ORDER BY create_time,file_id LIMIT 100",ws,flight);
            return Map.of("available",true,"items",camelRows(rows),"total",total,"hasMore",total>100,"note","仅返回与本次执行编号一致的文件元数据");
        }catch(DataAccessException e){return Map.of("available",false,"items",List.of(),"note","媒体记录暂不可读取");}
    }
    private List<Map<String,Object>> recordRows(List<Map<String,Object>> rows){
        List<Map<String,Object>> result=new ArrayList<>();
        for(Map<String,Object> row:rows){Object raw=row.remove("payload_json");Map<String,Object> item=camelRows(List.of(row)).get(0);
            try{JsonNode p=mapper.readTree(String.valueOf(raw));item.put("eventState",Accumulator.value(p,"businessState","business_state","msdkState","msdk_state"));item.put("eventMessage",Accumulator.value(p,"error","reason","msdkErrorMsg","msdk_error_msg"));item.put("waypointIndex",Accumulator.value(p,"currentWaypointIndex","current_waypoint_index"));item.put("progressPercent",Accumulator.value(p,"percent"));}catch(Exception ignored){}
            result.add(item);
        }return result;
    }
    private static List<Map<String,Object>> camelRows(List<Map<String,Object>> rows){List<Map<String,Object>> result=new ArrayList<>();for(Map<String,Object> row:rows){Map<String,Object> item=new LinkedHashMap<>();row.forEach((k,v)->item.put(camel(k.toLowerCase(Locale.ROOT)),v));result.add(item);}return result;}
    private static String camel(String value){String[] parts=value.split("_");StringBuilder out=new StringBuilder(parts[0]);for(int i=1;i<parts.length;i++)out.append(Character.toUpperCase(parts[i].charAt(0))).append(parts[i].substring(1));return out.toString();}
    private static String str(Map<String,Object> row,String key){return row.get(key)==null?null:String.valueOf(row.get(key));}
    private static Object first(Object a,Object b){return a==null?b:a;}
    private static Long lng(Object v){return v instanceof Number?((Number)v).longValue():null;}
    private static Double num(Object v){return v instanceof Number&&Double.isFinite(((Number)v).doubleValue())?((Number)v).doubleValue():null;}
    private static Boolean bool(Object v){return v instanceof Boolean?(Boolean)v:v instanceof Number?((Number)v).intValue()!=0:null;}
    private static MiniAppException invalid(){return new MiniAppException(HttpStatus.BAD_REQUEST,MiniAppErrorCode.VALIDATION_ERROR,"报告分页参数无效");}
    private static MiniAppException missing(){return new MiniAppException(HttpStatus.NOT_FOUND,MiniAppErrorCode.NOT_FOUND,"任务或执行记录不存在于当前工作空间");}

    static final class Accumulator {
        final ObjectMapper mapper;long count,telemetryCount,gaps,coveredMs;Long first,last,takeoff,landing,returnAt,previousTime,previousFlyingAt;Boolean previousFlying;Double takeoffBattery,landingBattery,returnBattery,lastBattery,previousSpeed,previousLat,previousLng,maxSpeed,maxHeight,minHeight,maxWind;double speedIntegral,distance;boolean batteryJump;long airborneSamples,trackSegments,takeoffTransitions;Double firstBattery,finalBattery;
        final List<Map<String,Object>> timeline=new ArrayList<>();final TreeMap<Long,Map<String,Object>> buckets=new TreeMap<>();
        Accumulator(ObjectMapper mapper){this.mapper=mapper;}
        void add(Map<String,Object> r){
            count++;Long time=lng(r.get("observed_at"));if(time==null)return;if(first==null)first=time;last=time;
            Double speed=num(r.get("speed_ms")),height=num(r.get("relative_height_m")),battery=num(r.get("battery_percent")),lat=num(r.get("latitude")),lon=num(r.get("longitude")),wind=num(r.get("wind_speed_ms"));Boolean flying=bool(r.get("flying"));
            boolean sample=speed!=null||height!=null||battery!=null||flying!=null;
            if(sample){
                telemetryCount++;if(firstBattery==null&&battery!=null)firstBattery=battery;if(battery!=null)finalBattery=battery;
                if(lastBattery!=null&&battery!=null&&battery-lastBattery>5)batteryJump=true;
                boolean continuous=previousFlyingAt!=null&&time-previousFlyingAt<=15000;
                if(continuous&&Boolean.TRUE.equals(flying)&&Boolean.FALSE.equals(previousFlying)){takeoffTransitions++;if(takeoff==null){takeoff=time;takeoffBattery=battery;}}
                if(continuous&&Boolean.FALSE.equals(flying)&&Boolean.TRUE.equals(previousFlying)&&landing==null&&takeoff!=null){landing=time;landingBattery=battery;}
                String mode=String.valueOf(r.get("flight_mode")).toUpperCase(Locale.ROOT).replace("_","");
                if(returnAt==null&&Boolean.TRUE.equals(flying)&&Set.of("GOHOME","RETURNTOHOME","AUTORETURN").contains(mode)){returnAt=time;returnBattery=battery;}
                if(!Boolean.FALSE.equals(flying)){
                    airborneSamples++;if(speed!=null)maxSpeed=max(maxSpeed,speed);if(height!=null){maxHeight=max(maxHeight,height);minHeight=min(minHeight,height);}if(wind!=null)maxWind=max(maxWind,wind);
                }
                if(previousTime!=null){long dt=time-previousTime;if(dt>15000)gaps++;else if(dt>0&&!Boolean.FALSE.equals(flying)&&!Boolean.FALSE.equals(previousFlying)){
                    if(speed!=null&&previousSpeed!=null){speedIntegral+=(speed+previousSpeed)*.5*dt;coveredMs+=dt;}
                    if(lat!=null&&lon!=null&&previousLat!=null&&previousLng!=null){double segment=meters(previousLat,previousLng,lat,lon);if(segment/(dt/1000.0)<=150){distance+=segment;trackSegments++;}}
                }}
                if(flying!=null){previousFlying=flying;previousFlyingAt=time;}previousTime=time;previousSpeed=speed;previousLat=lat;previousLng=lon;if(battery!=null)lastBattery=battery;
                long bucket=time/30000;Map<String,Object> point=new LinkedHashMap<>();point.put("time",time);point.put("speedMs",speed);point.put("heightM",height);point.put("batteryPercent",battery);buckets.put(bucket,point);
            }
            if(!"telemetry".equals(r.get("kind"))&&!"wayline_progress".equals(r.get("kind"))&&timeline.size()<200){
                Map<String,Object> e=new LinkedHashMap<>();e.put("time",time);e.put("kind",r.get("kind"));e.put("source",r.get("source"));
                try{JsonNode payload=mapper.readTree(String.valueOf(r.get("payload_json")));e.put("state",value(payload,"businessState","business_state","msdkState","msdk_state"));e.put("message",value(payload,"error","reason","msdkErrorMsg","msdk_error_msg"));}catch(Exception ignored){}
                timeline.add(e);
            }
        }
        Map<String,Object> result(){Map<String,Object> m=new LinkedHashMap<>();m.put("firstRecordAt",first);m.put("lastRecordAt",last);m.put("takeoffAt",takeoff);m.put("landingAt",landing);m.put("returnAt",returnAt);m.put("flightDurationSeconds",takeoffTransitions==1&&takeoff!=null&&landing!=null?(landing-takeoff)/1000:null);m.put("observedDurationSeconds",first!=null&&last!=null?(last-first)/1000:null);m.put("takeoffBatteryPercent",takeoffBattery);m.put("returnBatteryPercent",returnBattery);m.put("landingBatteryPercent",landingBattery);m.put("batteryConsumedPercentagePoints",takeoffTransitions==1&&!batteryJump&&takeoffBattery!=null&&landingBattery!=null&&takeoffBattery>=landingBattery?takeoffBattery-landingBattery:null);m.put("firstRecordedBatteryPercent",firstBattery);m.put("lastRecordedBatteryPercent",finalBattery);m.put("averageSpeedMs",coveredMs>0?round(speedIntegral/coveredMs):null);m.put("maximumSpeedMs",maxSpeed);m.put("maximumHeightM",maxHeight);m.put("minimumHeightM",minHeight);m.put("maximumWindSpeedMs",maxWind);m.put("observedDistanceM",trackSegments>0?round(distance):null);m.put("recordCount",count);m.put("telemetryCount",telemetryCount);return m;}
        Map<String,Object> quality(){List<String> warnings=new ArrayList<>(),missing=new ArrayList<>();if(takeoff==null)missing.add("起飞状态与起飞电量");if(landing==null)missing.add("落地状态与落地电量");if(returnAt==null)missing.add("返航模式与返航电量");if(takeoffBattery==null)missing.add("起飞电量");if(landingBattery==null)missing.add("落地电量");if(returnBattery==null)missing.add("返航电量");if(maxSpeed==null)missing.add("速度");if(maxHeight==null)missing.add("高度");if(gaps>0)warnings.add("发现 "+gaps+" 处超过 15 秒的遥测间隙；平均速度与轨迹距离不跨间隙补算");if(takeoffTransitions>1)warnings.add("同一执行记录包含多次起降，未合并计算单次飞行时长和电量消耗，请查看完整记录");if(batteryJump)warnings.add("电量存在上升跳变，可能换电或数据回跳；不计算单次净消耗");if(previousFlying==null)warnings.add("飞行状态未上报，无法确认真实起降时间");warnings.add("起降时间来自飞行状态采样，精度受采样间隔影响；未跨越缺失段推算航迹");warnings.add("电量消耗单位为百分点，并非 Wh；起降电量取状态变化时的采样值");warnings.add("垂直速度使用 NED 坐标，向下为正；相对高度以起飞点为基准");warnings.add("时间线摘要最多 200 项，完整事件与遥测可通过分页记录查询");return Map.of("status",count==0?"NOT_RECORDED":missing.isEmpty()&&gaps==0&&!batteryJump&&takeoffTransitions==1?"OBSERVED":"PARTIAL","recordCount",count,"gapCount",gaps,"warnings",warnings,"missingFields",missing);}
        List<Map<String,Object>> series(){List<Map<String,Object>> values=new ArrayList<>(buckets.values());if(values.size()<=240)return values;List<Map<String,Object>> result=new ArrayList<>();for(int i=0;i<240;i++)result.add(values.get((int)((long)i*(values.size()-1)/239)));return result;}
        private static Object value(JsonNode node,String...keys){for(String key:keys)if(node.hasNonNull(key))return node.get(key).asText();return null;}
        private static Double max(Double a,Double b){return a==null?b:Math.max(a,b);}private static Double min(Double a,Double b){return a==null?b:Math.min(a,b);}private static double round(double v){return Math.round(v*100.0)/100.0;}
        private static double meters(double a,double b,double c,double d){double lat=Math.toRadians(c-a),lon=Math.toRadians(d-b),x=Math.sin(lat/2)*Math.sin(lat/2)+Math.cos(Math.toRadians(a))*Math.cos(Math.toRadians(c))*Math.sin(lon/2)*Math.sin(lon/2);return 6371000*2*Math.atan2(Math.sqrt(x),Math.sqrt(Math.max(0,1-x)));}
    }
}
