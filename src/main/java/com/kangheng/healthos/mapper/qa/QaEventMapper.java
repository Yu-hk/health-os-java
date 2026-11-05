package com.kangheng.healthos.mapper.qa;

import java.util.List;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import com.kangheng.healthos.model.qa.QaEvent;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface QaEventMapper extends BaseMapper<QaEvent> {

    /**
     * 原子分配事件序号。
     *
     * <p>用 {@code UPDATE ... RETURNING} 而不是"读出来 +1 再写回"：
     * 后者在两个 worker 并发写事件时会发出重复的 seq，
     * 而 seq 是 SSE 断点续传的唯一依据——重复就意味着有事件永远补不上。
     */
    @Select("""
            UPDATE qa_run SET event_seq = event_seq + 1, updated_at = now()
            WHERE id = #{runId}
            RETURNING event_seq
            """)
    Integer nextSeq(@Param("runId") String runId);

    /** 增量拉取：只取 seq 大于 afterSeq 的事件，按 seq 升序。 */
    @Select("""
            SELECT * FROM qa_event
            WHERE run_id = #{runId} AND seq > #{afterSeq}
            ORDER BY seq ASC
            LIMIT #{limit}
            """)
    List<QaEvent> listAfter(@Param("runId") String runId,
                             @Param("afterSeq") int afterSeq,
                             @Param("limit") int limit);
}
