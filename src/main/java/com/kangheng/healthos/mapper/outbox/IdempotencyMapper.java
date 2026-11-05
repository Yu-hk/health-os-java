package com.kangheng.healthos.mapper.outbox;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import com.kangheng.healthos.model.outbox.IdempotencyRecord;

import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface IdempotencyMapper extends BaseMapper<IdempotencyRecord> {
}
