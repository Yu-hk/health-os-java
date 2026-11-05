package com.kangheng.healthos.service.patient;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import com.kangheng.healthos.common.BizException;
import com.kangheng.healthos.common.Ids;
import com.kangheng.healthos.config.HealthOsProperties;
import com.kangheng.healthos.contract.patient.PatientContract;
import com.kangheng.healthos.mapper.patient.PatientObjectMapper;
import com.kangheng.healthos.model.patient.PatientObjectEntity;
import com.kangheng.healthos.spi.storage.ObjectStorage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 患者附件清单。
 *
 * <p>对象键按 {@code patients/<patientId>/<objectId>.<ext>} 组织，而不是内容寻址：
 * 附件归属于某个人，两个人上传同一份 PDF 也必须各存一份，否则删一个人的档案
 * 会把另一个人的文件一起删掉。
 */
@Service
public class PatientObjectService {

    private static final Logger log = LoggerFactory.getLogger(PatientObjectService.class);

    private static final int MAX_FILENAME_LENGTH = 300;

    private final PatientObjectMapper objectMapper;
    private final PatientLifecycleService lifecycle;
    private final ObjectStorage storage;
    private final HealthOsProperties properties;

    public PatientObjectService(PatientObjectMapper objectMapper,
                                PatientLifecycleService lifecycle,
                                ObjectStorage storage,
                                HealthOsProperties properties) {
        this.objectMapper = objectMapper;
        this.lifecycle = lifecycle;
        this.storage = storage;
        this.properties = properties;
    }

    @Transactional
    public PatientContract.ObjectView upload(String patientId, String filename, String contentType,
                                             String kind, byte[] content, String owner) {
        lifecycle.require(patientId, owner);
        if (content == null || content.length == 0) {
            throw BizException.badRequest("附件内容为空");
        }
        long max = properties.getPatient().getMaxObjectBytes();
        if (content.length > max) {
            throw BizException.badRequest("附件超过上限 " + max + " 字节");
        }
        String safeName = sanitizeFilename(filename);
        String objectId = Ids.newId("pob");
        String objectKey = objectKey(patientId, objectId, safeName);
        ObjectStorage.StoredObject stored = storage.putAt(objectKey, content);

        PatientObjectEntity entity = new PatientObjectEntity();
        entity.setId(objectId);
        entity.setPatientId(patientId);
        entity.setOwner(owner);
        entity.setKind(StringUtils.hasText(kind) ? kind.toUpperCase(Locale.ROOT) : "OTHER");
        entity.setObjectKey(stored.objectKey());
        entity.setFilename(safeName);
        entity.setContentType(contentType);
        entity.setSizeBytes((long) content.length);
        entity.setSha256(stored.sha256());
        entity.setStatus("ACTIVE");
        entity.setCreatedAt(OffsetDateTime.now());
        try {
            objectMapper.insert(entity);
        } catch (RuntimeException ex) {
            // 元数据写失败就把刚落的文件删掉，别留一个没人认领的孤儿
            try {
                storage.delete(objectKey);
            } catch (RuntimeException cleanup) {
                log.warn("回滚附件对象失败 key={}", objectKey, cleanup);
            }
            throw ex;
        }
        return toView(entity);
    }

    public List<PatientContract.ObjectView> list(String patientId, String owner) {
        lifecycle.require(patientId, owner);
        return objectMapper.selectList(new LambdaQueryWrapper<PatientObjectEntity>()
                        .eq(PatientObjectEntity::getPatientId, patientId)
                        .eq(PatientObjectEntity::getOwner, owner)
                        .eq(PatientObjectEntity::getStatus, "ACTIVE")
                        .orderByDesc(PatientObjectEntity::getCreatedAt))
                .stream().map(this::toView).toList();
    }

    @Transactional
    public void delete(String patientId, String objectId, String owner) {
        lifecycle.require(patientId, owner);
        PatientObjectEntity entity = objectMapper.selectOne(new LambdaQueryWrapper<PatientObjectEntity>()
                .eq(PatientObjectEntity::getId, objectId)
                .eq(PatientObjectEntity::getPatientId, patientId)
                .eq(PatientObjectEntity::getOwner, owner)
                .last("limit 1"));
        if (entity == null) {
            throw BizException.notFound("附件");
        }
        entity.setStatus("DELETED");
        entity.setDeletedAt(OffsetDateTime.now());
        objectMapper.updateById(entity);
        try {
            storage.delete(entity.getObjectKey());
        } catch (RuntimeException ex) {
            // 元数据已标删除，文件残留交给孤儿扫描兜底，不让整个请求失败
            log.warn("删除附件对象失败 key={}", entity.getObjectKey(), ex);
        }
    }

    private String objectKey(String patientId, String objectId, String filename) {
        String prefix = properties.getPatient().getObjectPrefix();
        String extension = extensionOf(filename);
        return prefix + "/" + patientId + "/" + objectId + extension;
    }

    private static String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1 || filename.length() - dot > 12) {
            return "";
        }
        String ext = filename.substring(dot + 1).toLowerCase(Locale.ROOT);
        return ext.matches("[a-z0-9]+") ? "." + ext : "";
    }

    /** 只保留文件名本身：拒绝 {@code ../} 与前导斜杠，避免写到别人目录下。 */
    private static String sanitizeFilename(String filename) {
        String value = StringUtils.hasText(filename) ? filename : "attachment";
        value = value.replace('\\', '/');
        int slash = value.lastIndexOf('/');
        if (slash >= 0) {
            value = value.substring(slash + 1);
        }
        value = value.strip().replaceAll("[\\p{Cntrl}]", "");
        if (value.isEmpty() || ".".equals(value) || "..".equals(value)) {
            value = "attachment";
        }
        return value.length() > MAX_FILENAME_LENGTH ? value.substring(0, MAX_FILENAME_LENGTH) : value;
    }

    private PatientContract.ObjectView toView(PatientObjectEntity entity) {
        return new PatientContract.ObjectView(entity.getId(), entity.getPatientId(), entity.getKind(),
                entity.getFilename(), entity.getContentType(),
                entity.getSizeBytes() == null ? 0 : entity.getSizeBytes(),
                entity.getSha256(), entity.getStatus(), entity.getCreatedAt());
    }
}
