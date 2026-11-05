package com.kangheng.healthos.mapper.patient;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import com.kangheng.healthos.model.patient.MemoryAgentNote;

import org.apache.ibatis.annotations.Mapper;

/** 记忆 Agent 摘录候选的数据访问。@Mapper 不能漏。 */
@Mapper
public interface MemoryAgentNoteMapper extends BaseMapper<MemoryAgentNote> {
}
