package com.kangheng.healthos.service.outbox;

import java.time.OffsetDateTime;
import java.util.Optional;

import com.kangheng.healthos.common.BizException;
import com.kangheng.healthos.mapper.outbox.IdempotencyMapper;
import com.kangheng.healthos.model.outbox.IdempotencyRecord;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 幂等键处理。
 *
 * <p>同一个 Key 携带不同请求体时直接 409——这是原项目"不得伪造成功"约束的落地：
 * 宁可报错让调用方换 Key，也不要把第二次上传当成第一次的重放。
 */
@Service
public class IdempotencyService {

    private final IdempotencyMapper idempotencyMapper;

    public IdempotencyService(IdempotencyMapper idempotencyMapper) {
        this.idempotencyMapper = idempotencyMapper;
    }

    public Optional<String> findReplay(String key, String requestHash) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        IdempotencyRecord record = idempotencyMapper.selectById(key);
        if (record == null) {
            return Optional.empty();
        }
        if (!record.getRequestHash().equals(requestHash)) {
            throw BizException.conflict("幂等键 " + key + " 已用于不同内容的请求，请更换 Idempotency-Key");
        }
        return Optional.of(record.getResourceId());
    }

    public void remember(String key, String requestHash, String resourceId) {
        if (key == null || key.isBlank()) {
            return;
        }
        IdempotencyRecord record = new IdempotencyRecord();
        record.setIdemKey(key);
        record.setRequestHash(requestHash);
        record.setResourceId(resourceId);
        record.setCreatedAt(OffsetDateTime.now());
        try {
            idempotencyMapper.insert(record);
        } catch (DuplicateKeyException ex) {
            // 并发同 Key：保留先到者的结果，本次视作重放
            findReplay(key, requestHash);
        }
    }
}
