package com.kangheng.healthos.mapper.outbox;

import java.util.List;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import com.kangheng.healthos.model.outbox.OutboxEventEntity;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface OutboxMapper extends BaseMapper<OutboxEventEntity> {

    /**
     * 原子认领待执行事件。
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} + {@code RETURNING} 保证多实例并发调度时
     * 同一事件只会被一个进程拿到，且不会互相阻塞——这是把原项目
     * "PDB 行锁 + 心跳"换成 PostgreSQL 原生能力的地方。
     */
    @Select("""
            UPDATE outbox_event SET status = 'RUNNING', attempts = attempts + 1, updated_at = now()
            WHERE id IN (
                SELECT id FROM outbox_event
                WHERE status = 'PENDING' AND available_at <= now()
                ORDER BY created_at
                LIMIT #{limit}
                FOR UPDATE SKIP LOCKED
            )
            RETURNING id
            """)
    List<String> claim(@Param("limit") int limit);
}
