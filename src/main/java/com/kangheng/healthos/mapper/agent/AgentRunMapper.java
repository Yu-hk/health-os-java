package com.kangheng.healthos.mapper.agent;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import com.kangheng.healthos.model.agent.AgentRun;

import org.apache.ibatis.annotations.Mapper;

/** 协同运行的数据访问。 */
@Mapper
public interface AgentRunMapper extends BaseMapper<AgentRun> {
}
