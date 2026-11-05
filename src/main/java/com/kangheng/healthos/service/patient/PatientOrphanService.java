package com.kangheng.healthos.service.patient;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import com.kangheng.healthos.common.BizException;
import com.kangheng.healthos.config.HealthOsProperties;
import com.kangheng.healthos.contract.patient.PatientContract;
import com.kangheng.healthos.mapper.patient.PatientObjectMapper;
import com.kangheng.healthos.model.patient.PatientObjectEntity;
import com.kangheng.healthos.spi.storage.ObjectStorage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 孤儿对象回收：对象存储里有文件、元数据表里没有归属。
 *
 * <p>两条安全边界，缺一条就不敢在生产上开：
 * <ol>
 *   <li><b>扫描只读</b>：{@link #scan()} 一个字节都不删，只给清单；</li>
 *   <li><b>删除要显式点名</b>：{@link #purge(List)} 必须传具体 key，不提供"删掉所有孤儿"这种一键操作。
 *       孤儿判定本来就是推断，凭推断批量删文件的风险不该由一次点击承担。</li>
 * </ol>
 */
@Service
public class PatientOrphanService {

    private static final Logger log = LoggerFactory.getLogger(PatientOrphanService.class);

    private static final int MAX_LISTED = 500;

    private final PatientObjectMapper objectMapper;
    private final ObjectStorage storage;
    private final HealthOsProperties properties;

    public PatientOrphanService(PatientObjectMapper objectMapper,
                                ObjectStorage storage,
                                HealthOsProperties properties) {
        this.objectMapper = objectMapper;
        this.storage = storage;
        this.properties = properties;
    }

    public PatientContract.OrphanScan scan() {
        String prefix = properties.getPatient().getObjectPrefix();
        List<String> keys = storage.listKeys(prefix);
        Set<String> referenced = referencedKeys();
        List<String> orphans = new ArrayList<>();
        int referencedPresent = 0;
        int orphanTotal = 0;
        for (String key : keys) {
            if (referenced.contains(key)) {
                referencedPresent++;
                continue;
            }
            orphanTotal++;
            if (orphans.size() < MAX_LISTED) {
                orphans.add(key);
            }
        }
        return new PatientContract.OrphanScan(prefix, keys.size(), referencedPresent, orphanTotal,
                orphans, orphanTotal > orphans.size());
    }

    public PatientContract.OrphanPurgeResult purge(List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            throw BizException.badRequest("必须显式指定要删除的对象 key");
        }
        String prefix = properties.getPatient().getObjectPrefix() + "/";
        Set<String> referenced = referencedKeys();
        Set<String> skipped = new LinkedHashSet<>();
        int deleted = 0;
        for (String key : new LinkedHashSet<>(keys)) {
            if (!StringUtils.hasText(key) || !key.startsWith(prefix) || key.contains("..")) {
                skipped.add(key);
                continue;
            }
            if (referenced.contains(key)) {
                // 有归属的文件一律不动：孤儿判定可能过期，但"还在用的文件"是硬事实
                skipped.add(key);
                continue;
            }
            try {
                storage.delete(key);
                deleted++;
            } catch (RuntimeException ex) {
                log.warn("删除孤儿对象失败 key={}", key, ex);
                skipped.add(key);
            }
        }
        return new PatientContract.OrphanPurgeResult(keys.size(), deleted, new ArrayList<>(skipped));
    }

    private Set<String> referencedKeys() {
        Set<String> referenced = new HashSet<>();
        for (PatientObjectEntity entity : objectMapper.selectList(
                new LambdaQueryWrapper<PatientObjectEntity>()
                        .eq(PatientObjectEntity::getStatus, "ACTIVE"))) {
            if (StringUtils.hasText(entity.getObjectKey())) {
                referenced.add(entity.getObjectKey());
            }
        }
        return referenced;
    }
}
