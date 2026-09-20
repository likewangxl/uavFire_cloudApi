package com.yx.uavfire.miniapp;

import com.yx.uavfire.miniapp.configuration.MiniAppProperties;
import com.yx.uavfire.miniapp.service.MiniAppSessionService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import java.nio.file.*;
import java.util.UUID;

public class MiniAppTestDatabase {
    public final DriverManagerDataSource datasource = new DriverManagerDataSource("jdbc:h2:mem:mini" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
    public final JdbcTemplate jdbc = new JdbcTemplate(datasource);
    public final MiniAppProperties properties = new MiniAppProperties();
    public final MiniAppSessionService sessions;
    public MiniAppTestDatabase() throws Exception {
        properties.setEnabled(true); properties.getWechat().setAppId("test-app");
        String migration = Files.readString(Path.of("../sql/migrations/2026-09-15-miniapp-sessions.sql"));
        for (String sql : migration.split(";")) if (!sql.isBlank()) jdbc.execute(sql);
        jdbc.execute("CREATE TABLE manage_workspace(workspace_id VARCHAR(64) PRIMARY KEY,workspace_name VARCHAR(128))");
        jdbc.execute("CREATE TABLE manage_user(user_id VARCHAR(64) PRIMARY KEY,username VARCHAR(128),user_type INT,workspace_id VARCHAR(64))");
        jdbc.execute("CREATE TABLE planned_wayline(id INT PRIMARY KEY,planned_wayline_id VARCHAR(64),workspace_id VARCHAR(64),name VARCHAR(128),aircraft_model_key VARCHAR(32),aircraft_sn VARCHAR(64),status VARCHAR(32),task_status VARCHAR(32),task_status_reason VARCHAR(255),task_progress INT,last_progress_time BIGINT,executed_time BIGINT,prepared_time BIGINT,publish_time BIGINT,create_time BIGINT,update_time BIGINT,waypoints_json TEXT,default_height DOUBLE,total_waypoints INT,current_waypoint_index INT,media_count INT)");
        jdbc.execute("CREATE TABLE fire_event(id INT PRIMARY KEY,event_id VARCHAR(64),workspace_id VARCHAR(64),source VARCHAR(32),device_sn VARCHAR(64),fire_level VARCHAR(32),confidence DOUBLE,status VARCHAR(32),confirmed_status VARCHAR(32),lat DOUBLE,lng DOUBLE,alt DOUBLE,geo_quality VARCHAR(32),geo_method VARCHAR(32),geo_error_radius_m DOUBLE,geo_source_ts BIGINT,event_timestamp BIGINT,create_time BIGINT,update_time BIGINT,deleted INT)");
        jdbc.execute("CREATE TABLE fire_event_history(id INT PRIMARY KEY,event_id VARCHAR(64),workspace_id VARCHAR(64),action VARCHAR(64),decision_reason VARCHAR(255),create_time BIGINT)");
        jdbc.execute("CREATE TABLE manage_device(device_sn VARCHAR(64),workspace_id VARCHAR(64),device_name VARCHAR(64),domain INT,bound_status BOOLEAN)");
        jdbc.update("INSERT INTO manage_workspace VALUES('ws-a','林区 A'),('ws-b','林区 B')");
        jdbc.update("INSERT INTO manage_user VALUES('user-a','leader-a',1,'ws-a'),('user-b','leader-b',1,'ws-b')");
        ProxyFactory proxy = new ProxyFactory(new MiniAppSessionService(jdbc, properties));
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(datasource), new AnnotationTransactionAttributeSource()));
        sessions = (MiniAppSessionService) proxy.getProxy();
    }
    public void approve(String openId) {
        jdbc.update("UPDATE miniapp_identity SET binding_status='APPROVED',user_id='user-a',workspace_id='ws-a' WHERE open_id=?", openId);
    }
}
