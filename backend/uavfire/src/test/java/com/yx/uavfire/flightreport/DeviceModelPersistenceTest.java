package com.yx.uavfire.flightreport;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.yx.uavfire.wayline.dao.IPlannedWaylineMapper;
import com.yx.uavfire.wayline.model.entity.PlannedWaylineEntity;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;

class DeviceModelPersistenceTest {
    @Test void realMapperStoresOfficialNamesAndDecodesLegacyDispatchKeys() {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:modelnames;MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        var jdbc=new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE planned_wayline(id INT AUTO_INCREMENT PRIMARY KEY,name VARCHAR(256),aircraft_model_key VARCHAR(128),payload_model_key VARCHAR(128),create_time BIGINT,update_time BIGINT)");
        var configuration=new MybatisConfiguration();
        configuration.setEnvironment(new Environment("test",new JdbcTransactionFactory(),ds));
        configuration.addMapper(IPlannedWaylineMapper.class);
        try(var session=new MybatisSqlSessionFactoryBuilder().build(configuration).openSession(true)) {
            var mapper=session.getMapper(IPlannedWaylineMapper.class);
            var entity=PlannedWaylineEntity.builder().name("M300 巡检").aircraftModelKey("M300").payloadModelKey("H20T").build();
            mapper.insert(entity);
            assertEquals("DJI Matrice 300 RTK",jdbc.queryForObject("SELECT aircraft_model_key FROM planned_wayline",String.class));
            assertEquals("DJI Zenmuse H20T",jdbc.queryForObject("SELECT payload_model_key FROM planned_wayline",String.class));
            var read=mapper.selectOne(new QueryWrapper<PlannedWaylineEntity>().select("id","name","aircraft_model_key","payload_model_key").eq("id",entity.getId()));
            assertEquals("M300",read.getAircraftModelKey());assertEquals("H20T",read.getPayloadModelKey());assertEquals("DJI Matrice 300 RTK 巡检",read.getName());
            read.setAircraftModelKey("DJI Matrice 4T");mapper.updateById(read);
            assertEquals("DJI Matrice 4T",jdbc.queryForObject("SELECT aircraft_model_key FROM planned_wayline",String.class));
        }
    }
}
