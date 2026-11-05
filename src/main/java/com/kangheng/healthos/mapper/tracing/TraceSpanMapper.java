package com.kangheng.healthos.mapper.tracing;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kangheng.healthos.model.tracing.TraceSpanEntity;

import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TraceSpanMapper extends BaseMapper<TraceSpanEntity> {
}
