package com.kangheng.healthos.model.outbox;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 幂等记录。上传接口带 Idempotency-Key 时，重复提交返回同一次结果而不是重复建单。
 */
@Data
@TableName("idempotency_record")
public class IdempotencyRecord {

    @TableId(type = IdType.INPUT)
    private String idemKey;
    private String requestHash;
    private String resourceId;
    private OffsetDateTime createdAt;
}
