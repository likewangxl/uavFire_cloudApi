package com.yx.uavfire.miniapp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yx.uavfire.common.model.CustomClaim;
import com.yx.uavfire.miniapp.web.*;
import com.yx.uavfire.msdk.service.MsdkDeviceStateService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.sql.ResultSetMetaData;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;
import com.dji.sdk.cloudapi.device.DeviceDomainEnum;

/** Allowlisted read models: every lookup includes the approved workspace. */
@Service
public class MiniAppReadService {
    private static final String TASK_COLUMNS = "planned_wayline_id AS task_id,name,aircraft_model_key,aircraft_sn,status,task_status,task_status_reason,task_progress,last_progress_time,executed_time,prepared_time,publish_time,create_time,update_time";
    private static final String EVENT_COLUMNS = "event_id,source,device_sn,fire_level,confidence,status,confirmed_status,lat,lng,alt,geo_quality,geo_method,geo_error_radius_m,geo_source_ts,event_timestamp,create_time,update_time";
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final MsdkDeviceStateService devices;
    private final com.yx.uavfire.flightreport.FlightReportService flightReports;
    public MiniAppReadService(JdbcTemplate jdbc, ObjectMapper mapper, MsdkDeviceStateService devices) {
        this.jdbc = jdbc; this.mapper = mapper; this.devices = devices;
        this.flightReports = new com.yx.uavfire.flightreport.FlightReportService(jdbc, mapper);
    }

    public Map<String, Object> me(CustomClaim claim) {
        String workspace = workspace(claim);
        List<Map<String, Object>> rows = query("SELECT workspace_id,workspace_name AS name FROM manage_workspace WHERE workspace_id=?", workspace);
        if (rows.size() != 1) throw missing();
        return Map.of("user", Map.of("userId", claim.getId(), "displayName", claim.getUsername(), "roles", List.of("VIEWER"),
                        "permissions", List.of("task:read", "event:read", "report:read")),
                "workspace", rows.get(0), "bindingStatus", "APPROVED",
                "capabilities", Map.of("flightControlEnabled", false,
                        "reportSource", "TASK_RECORD", "reportSignatureEnabled", false, "mediaPlaybackEnabled", false));
    }

    public Map<String, Object> tasks(CustomClaim claim, int page, int size, String keyword, String status, boolean reportsOnly, boolean savedOnly) {
        validatePage(page, size); validateFilter(keyword); validateFilter(status);
        StringBuilder where = new StringBuilder(" WHERE workspace_id=?");
        List<Object> args = new ArrayList<>(List.of(workspace(claim)));
        if (reportsOnly) where.append(" AND executed_time IS NOT NULL AND executed_time>0");
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND (name LIKE ? ESCAPE '!' OR planned_wayline_id LIKE ? ESCAPE '!' OR aircraft_model_key LIKE ? ESCAPE '!')");
            String term = "%" + keyword.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
            args.add(term); args.add(term); args.add(term);
        }
        if ("completed".equals(status)) where.append(" AND COALESCE(task_status,status) IN ('completed','finished')");
        else if (status != null && !status.isBlank()) { where.append(" AND COALESCE(task_status,status)=?"); args.add(status); }
        if (savedOnly) {
            where.append(" AND EXISTS (SELECT 1 FROM miniapp_user_state s WHERE s.user_id=? AND s.workspace_id=planned_wayline.workspace_id AND s.state_key=CONCAT('report:',planned_wayline_id) AND s.state_value='true')");
            args.add(claim.getId());
        }
        long count = jdbc.queryForObject("SELECT COUNT(*) FROM planned_wayline" + where, Long.class, args.toArray());
        args.add(size); args.add((page - 1) * size);
        List<Map<String, Object>> items = query("SELECT " + TASK_COLUMNS + " FROM planned_wayline" + where + " ORDER BY update_time DESC,id DESC LIMIT ? OFFSET ?", args.toArray());
        items.forEach(this::taskState);
        if (reportsOnly) items.forEach(item -> { item.put("reportId", item.get("taskId")); item.put("source", "TASK_RECORD"); item.put("name", item.get("name") + " · 执行记录"); });
        return page(items, page, size, count);
    }

    public Map<String, Object> task(CustomClaim claim, String id) {
        List<Map<String, Object>> rows = query("SELECT " + TASK_COLUMNS + ",waypoints_json,default_height,total_waypoints,current_waypoint_index,media_count FROM planned_wayline WHERE workspace_id=? AND planned_wayline_id=?",
                workspace(claim), id);
        if (rows.size() != 1) throw missing();
        Map<String, Object> row = rows.get(0); taskState(row);
        Object json = row.remove("waypointsJson");
        try { row.put("waypoints", json == null ? List.of() : mapper.readTree(json.toString())); }
        catch (Exception exception) { row.put("waypoints", List.of()); row.put("routeWarning", "航线记录无法解析，请在 PC 端核查"); }
        List<Map<String, Object>> timeline = new ArrayList<>();
        addTime(timeline, row.get("createTime"), "创建巡检计划");
        addTime(timeline, row.get("publishTime"), "发布航线");
        addTime(timeline, row.get("preparedTime"), "任务准备记录");
        addTime(timeline, row.get("executedTime"), "任务执行记录");
        addTime(timeline, row.get("lastProgressTime"), "最近执行状态：" + row.get("statusLabel"));
        timeline.sort(Comparator.comparingLong(item -> ((Number) item.get("time")).longValue()));
        row.put("timeline", timeline);
        row.put("flightReport", flightReports.report(workspace(claim), id, null, 1, 50, null));
        return row;
    }

    public Map<String,Object> flightReport(CustomClaim claim,String id,String runId,int page,int pageSize,Long snapshot) {
        return flightReports.report(workspace(claim),id,runId,page,pageSize,snapshot);
    }
    public Map<String,Object> flightRelated(CustomClaim claim,String id,String runId,String kind,int page,int size,Long snapshot){return flightReports.related(workspace(claim),id,runId,kind,page,size,snapshot);}

    public Map<String, Object> events(CustomClaim claim, int page, int size, String status) {
        validatePage(page, size); validateFilter(status);
        String where = " WHERE workspace_id=? AND (deleted IS NULL OR deleted=0)";
        List<Object> args = new ArrayList<>(List.of(workspace(claim)));
        if ("PENDING_REVIEW".equals(status)) where += " AND status IN ('NEW','CANDIDATE','LOW_CONFIDENCE')";
        else if (status != null && !status.isBlank()) { where += " AND status=?"; args.add(status); }
        long count = jdbc.queryForObject("SELECT COUNT(*) FROM fire_event" + where, Long.class, args.toArray());
        args.add(size); args.add((page - 1) * size);
        List<Map<String, Object>> items = query("SELECT " + EVENT_COLUMNS + " FROM fire_event" + where + " ORDER BY update_time DESC,id DESC LIMIT ? OFFSET ?", args.toArray());
        items.forEach(this::eventState); return page(items, page, size, count);
    }

    public Map<String, Object> event(CustomClaim claim, String id) {
        List<Map<String, Object>> rows = query("SELECT " + EVENT_COLUMNS + " FROM fire_event WHERE workspace_id=? AND event_id=? AND (deleted IS NULL OR deleted=0)", workspace(claim), id);
        if (rows.size() != 1) throw missing();
        Map<String, Object> row = rows.get(0); eventState(row);
        row.put("timeline", query("SELECT action,decision_reason,create_time AS time FROM fire_event_history WHERE workspace_id=? AND event_id=? ORDER BY create_time ASC,id ASC LIMIT 200", workspace(claim), id));
        row.put("mediaStatus", "NOT_INTEGRATED"); return row;
    }

    public Map<String, Object> report(CustomClaim claim, String id) {
        Map<String, Object> row = task(claim, id);
        if (!(row.get("executedTime") instanceof Number) || ((Number) row.get("executedTime")).longValue() <= 0) throw missing();
        row.put("reportId", id); row.put("source", "TASK_RECORD");
        row.put("reportNote", "根据当前任务记录实时整理；未生成正式 PDF，未签阅或归档，不自动判断现场是否存在火情。");
        row.put("saved", "true".equals(state(claim, "report:" + id)));
        return row;
    }

    public List<Map<String, Object>> devices(CustomClaim claim) {
        List<Map<String, Object>> rows = query("SELECT device_sn,device_name,domain FROM manage_device WHERE workspace_id=? AND bound_status=TRUE ORDER BY device_sn LIMIT 500", workspace(claim));
        for (Map<String, Object> row : rows) {
            row.put("dataStatus", "UNKNOWN"); row.put("online", null);
            devices.get((String) row.get("deviceSn")).ifPresent(value -> {
                long age = value.getUpdatedAt() == null ? Long.MAX_VALUE : System.currentTimeMillis() - value.getUpdatedAt();
                boolean fresh = age >= -5000 && age <= 30_000;
                row.put("dataStatus", fresh ? "FRESH" : "STALE"); row.put("updatedAt", value.getUpdatedAt());
                row.put("model", com.yx.uavfire.common.model.DeviceModelNames.full(value.getAircraftModelKey()));
                row.put("online", fresh ? value.getOnline() : null);
                row.put("batteryPercent", fresh ? value.getBatteryPercent() : null);
            });
        }
        return rows;
    }

    public Map<String, Object> summary(CustomClaim claim) {
        String ws = workspace(claim);
        long start = LocalDate.now(ZoneId.of("Asia/Shanghai")).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
        long end = start + 24 * 60 * 60_000L;
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("todayTasks", jdbc.queryForObject("SELECT COUNT(*) FROM planned_wayline WHERE workspace_id=? AND executed_time>=? AND executed_time<?", Long.class, ws, start, end));
        long urgent = jdbc.queryForObject("SELECT COUNT(*) FROM fire_event WHERE workspace_id=? AND (deleted IS NULL OR deleted=0) AND status IN ('NEW','CANDIDATE','LOW_CONFIDENCE')", Long.class, ws);
        metrics.put("urgentEvents", urgent);
        List<Map<String, Object>> deviceRows = devices(claim).stream()
                .filter(item -> item.get("domain") instanceof Number && ((Number) item.get("domain")).intValue() == DeviceDomainEnum.DRONE.getDomain())
                .collect(Collectors.toList());
        long aircraftCount = jdbc.queryForObject("SELECT COUNT(*) FROM manage_device WHERE workspace_id=? AND bound_status=TRUE AND domain=?", Long.class, ws, DeviceDomainEnum.DRONE.getDomain());
        metrics.put("onlineAircraft", deviceRows.isEmpty() || aircraftCount != deviceRows.size() || deviceRows.stream().anyMatch(item -> item.get("online") == null) ? null
                : deviceRows.stream().filter(item -> Boolean.TRUE.equals(item.get("online"))).count());
        List<String> warnings = new ArrayList<>();
        if (metrics.get("onlineAircraft") == null) warnings.add("部分航空器尚无所属工作空间内的新鲜遥测，在线数量暂不可确认");
        warnings.add("今日任务按今日开始执行的巡检计划统计；报告为任务执行记录，正式签阅与媒体暂未接入");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("metrics", metrics); data.put("conclusion", Map.of("headline", urgent > 0 ? urgent + " 项事件待复核" : "当前没有待复核事件", "workspaceId", ws));
        data.put("activeTasks", tasks(claim, 1, 3, "", "executing", false, false).get("items"));
        List<Map<String, Object>> urgentItems = query("SELECT " + EVENT_COLUMNS + " FROM fire_event WHERE workspace_id=? AND (deleted IS NULL OR deleted=0) AND status IN ('NEW','CANDIDATE','LOW_CONFIDENCE') ORDER BY update_time DESC,id DESC LIMIT 3", ws);
        urgentItems.forEach(this::eventState);
        data.put("urgentEvents", urgentItems);
        data.put("reports", tasks(claim, 1, 1, "", "", true, false).get("items"));
        data.put("warnings", warnings); data.put("partial", true); data.put("dataStatus", "PARTIAL"); data.put("generatedAt", Instant.now().toString());
        return data;
    }

    public Map<String, Object> preferences(CustomClaim claim) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : List.of("urgentEvents", "taskUpdates", "reportReady")) result.put(key, "true".equals(state(claim, "pref:" + key)));
        result.put("wechatSubscriptionEnabled", false); return result;
    }
    public Map<String, Object> savePreferences(CustomClaim claim, Map<String, Boolean> values) {
        for (String key : values.keySet()) if (!List.of("urgentEvents", "taskUpdates", "reportReady").contains(key) || values.get(key) == null) throw invalid();
        values.forEach((key, value) -> putState(claim, "pref:" + key, value.toString())); return preferences(claim);
    }
    public void saveReport(CustomClaim claim, String id, boolean saved) { report(claim, id); putState(claim, "report:" + id, String.valueOf(saved)); }

    public String state(CustomClaim claim, String key) {
        List<String> rows = jdbc.queryForList("SELECT state_value FROM miniapp_user_state WHERE user_id=? AND workspace_id=? AND state_key=?", String.class, claim.getId(), workspace(claim), key);
        return rows.isEmpty() ? "" : rows.get(0);
    }
    private void putState(CustomClaim claim, String key, String value) {
        if (key.length() > 160) throw invalid();
        jdbc.update("INSERT INTO miniapp_user_state(user_id,workspace_id,state_key,state_value,update_time) VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE state_value=VALUES(state_value),update_time=VALUES(update_time)",
                claim.getId(), workspace(claim), key, value, System.currentTimeMillis());
    }
    private void taskState(Map<String, Object> row) {
        row.put("name", com.yx.uavfire.common.model.DeviceModelNames.text((String)row.get("name")));
        row.put("aircraftModelKey", com.yx.uavfire.common.model.DeviceModelNames.full((String)row.get("aircraftModelKey")));
        String status = String.valueOf(row.get("taskStatus") == null ? row.get("status") : row.get("taskStatus"));
        Map<String, String> labels = Map.ofEntries(Map.entry("executing", "执行中"), Map.entry("paused", "已暂停"), Map.entry("completed", "已完成"), Map.entry("finished", "已完成"), Map.entry("failed", "异常"), Map.entry("canceled", "已取消"), Map.entry("stopped", "已停止"), Map.entry("published", "已发布"), Map.entry("draft", "草稿"), Map.entry("file_generated", "航线已生成"), Map.entry("publishing", "发布中"));
        row.put("statusLabel", labels.getOrDefault(status, status));
        long updated = row.get("lastProgressTime") instanceof Number ? ((Number) row.get("lastProgressTime")).longValue() : 0;
        boolean stale = ("executing".equals(status) || "paused".equals(status)) && (updated == 0 || System.currentTimeMillis() - updated > 60_000);
        row.put("dataStatus", stale ? "STALE" : "RECORDED");
        if (stale) row.put("statusLabel", "状态待更新（最近记录：" + labels.get(status) + "）");
    }
    private void eventState(Map<String, Object> row) {
        row.put("name", "火情事件 " + row.get("eventId"));
        row.put("statusLabel", Map.of("NEW", "待复核", "CANDIDATE", "待复核", "LOW_CONFIDENCE", "低置信度待复核", "MISSION_CREATED", "已关联处置任务", "IGNORED", "已忽略").getOrDefault(String.valueOf(row.get("status")), String.valueOf(row.get("status"))));
        row.put("coordinateReference", "WGS84"); row.put("positionNote", "按原始记录展示坐标与精度，需现场复核");
    }
    private void addTime(List<Map<String, Object>> list, Object time, String title) { if (time instanceof Number && ((Number) time).longValue() > 0) list.add(Map.of("time", time, "title", title)); }
    private List<Map<String, Object>> query(String sql, Object... args) {
        return jdbc.query(sql, (rs, index) -> {
            Map<String, Object> row = new LinkedHashMap<>(); ResultSetMetaData meta = rs.getMetaData();
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                String[] parts = meta.getColumnLabel(i).toLowerCase(Locale.ROOT).split("_"); StringBuilder key = new StringBuilder(parts[0]);
                for (int p = 1; p < parts.length; p++) key.append(Character.toUpperCase(parts[p].charAt(0))).append(parts[p].substring(1));
                row.put(key.toString(), rs.getObject(i));
            }
            return row;
        }, args);
    }
    private static Map<String, Object> page(List<Map<String, Object>> items, int page, int size, long total) { return Map.of("items", items, "page", page, "pageSize", size, "total", total, "hasMore", (long) page * size < total); }
    private static String workspace(CustomClaim claim) { if (claim == null || claim.getWorkspaceId() == null || claim.getWorkspaceId().isBlank()) throw invalid(); return claim.getWorkspaceId(); }
    private static void validatePage(int page, int size) { if (page < 1 || page > 10000 || size < 1 || size > 100) throw invalid(); }
    private static void validateFilter(String value) { if (value != null && value.length() > 128) throw invalid(); }
    private static MiniAppException invalid() { return new MiniAppException(HttpStatus.BAD_REQUEST, MiniAppErrorCode.VALIDATION_ERROR, "查询参数不符合要求"); }
    private static MiniAppException missing() { return new MiniAppException(HttpStatus.NOT_FOUND, MiniAppErrorCode.NOT_FOUND, "记录不存在或不在当前工作空间"); }
}
