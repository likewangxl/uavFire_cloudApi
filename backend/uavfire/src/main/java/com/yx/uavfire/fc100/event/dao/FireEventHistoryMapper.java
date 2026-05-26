package com.yx.uavfire.fc100.event.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yx.uavfire.fc100.event.model.entity.FireEventHistoryEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface FireEventHistoryMapper extends BaseMapper<FireEventHistoryEntity> {
}
