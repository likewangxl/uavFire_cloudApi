package com.yx.uavfire.fc100.payload.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yx.uavfire.fc100.payload.model.entity.PayloadEventEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PayloadEventMapper extends BaseMapper<PayloadEventEntity> {
}
