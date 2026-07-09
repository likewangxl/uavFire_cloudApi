package com.yx.uavfire.fc100.event.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yx.uavfire.fc100.event.model.entity.FireEventEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface FireEventMapper extends BaseMapper<FireEventEntity> {

    @Select("SELECT GET_LOCK(#{name}, #{timeoutSeconds})")
    Integer acquireNamedLock(@Param("name") String name, @Param("timeoutSeconds") int timeoutSeconds);

    @Select("SELECT RELEASE_LOCK(#{name})")
    Integer releaseNamedLock(@Param("name") String name);
}
