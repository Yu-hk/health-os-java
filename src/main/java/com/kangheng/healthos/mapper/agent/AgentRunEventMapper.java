package com.kangheng.healthos.mapper.agent;

import java.util.List;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import com.kangheng.healthos.model.agent.AgentRunEvent;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Agent Run 事件的数据访问。 */
@Mapper
public interface AgentRunEventMapper extends BaseMapper<AgentRunEvent> {

    /**
     * 原子分配本 run 的下一个事件序号。
     *
     * <p>不能「读出来 +1 再写回」：两个 worker 并发写事件会发出重复 seq，
     * 而 seq 是增量轮询与 SSE 断点续传的唯一依据——重号意味着有一批事件永远补不上。
     */
    @Select("""
            UPDATE agent_run SET event_seq = event_seq + 1
            WHERE id = #{runId}
            RETURNING event_seq
            """)
    Integer nextSeq(@Param("runId") String runId);

    /** 增量拉取：只取 seq 大于 afterSeq 的事件，按 seq 升序。 */
    @Select("""
            SELECT * FROM agent_run_event
            WHERE run_id = #{runId} AND seq > #{afterSeq}
            ORDER BY seq ASC
            LIMIT #{limit}
            """)
    List<AgentRunEvent> listAfter(@Param("runId") String runId,
                                  @Param("afterSeq") int afterSeq,
                                  @Param("limit") int limit);
}
