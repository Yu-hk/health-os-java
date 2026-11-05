package com.kangheng.healthos.mapper.agent;

import java.util.List;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import com.kangheng.healthos.model.agent.AgentNode;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 运行节点的数据访问。 */
@Mapper
public interface AgentNodeMapper extends BaseMapper<AgentNode> {

    /**
     * 断点续跑：取本 run 已经成功落库的<b>角色</b>节点。
     *
     * <p>单独一条 SQL 而不是复用 selectList(any())：重试时只复用「成功且有
     * outputHash 的角色产出」，校验节点、工具节点一律重跑，否则会把上一轮的
     * 校验结论当成这一轮的证据。
     */
    @Select("""
            SELECT * FROM agent_node
            WHERE run_id = #{runId} AND kind = 'role' AND status = 'succeeded'
            ORDER BY ordinal ASC
            """)
    List<AgentNode> selectSucceededRoles(@Param("runId") String runId);
}
