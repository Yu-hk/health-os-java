package com.kangheng.healthos.mapper.tracing;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kangheng.healthos.model.tracing.TraceEventEntity;

import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TraceEventMapper extends BaseMapper<TraceEventEntity> {
}
