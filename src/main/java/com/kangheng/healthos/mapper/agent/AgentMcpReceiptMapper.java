package com.kangheng.healthos.mapper.agent;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import com.kangheng.healthos.model.agent.AgentMcpReceipt;

import org.apache.ibatis.annotations.Mapper;

/** Agent Lab 协议回执的数据访问。当日指标、最近一次 probe 都从这里查。 */
@Mapper
public interface AgentMcpReceiptMapper extends BaseMapper<AgentMcpReceipt> {
}
