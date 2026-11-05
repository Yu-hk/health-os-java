package com.kangheng.healthos.service.classics;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import com.kangheng.healthos.common.Ids;
import com.kangheng.healthos.common.Json;
import com.kangheng.healthos.mapper.classics.ArtifactMapper;
import com.kangheng.healthos.mapper.classics.ClassicDocumentMapper;
import com.kangheng.healthos.mapper.classics.ClassicJobMapper;
import com.kangheng.healthos.mapper.classics.PipelineNodeMapper;
import com.kangheng.healthos.model.classics.ArtifactEntity;
import com.kangheng.healthos.model.classics.ClassicDocument;
import com.kangheng.healthos.model.classics.ClassicJob;
import com.kangheng.healthos.model.classics.PipelineNodeEntity;
import com.kangheng.healthos.model.classics.PipelineNodeSpec;
import com.kangheng.healthos.spi.storage.ObjectStorage;

import org.springframework.stereotype.Service;

/**
 * 流水线节点与产物的持久化操作。
 *
 * <p>刻意<b>不</b>把整条流程包成一个事务：每个节点完成即提交，
 * 这样中途失败时前面节点的检查点仍然有效，重跑可以从断点继续。
 * 这是原项目 {@code run_pipeline} 的行为，也是长任务必须的设计。
 */
@Service
public class PipelineService {

    public static final String NODE_PENDING = "PENDING";
    public static final String NODE_RUNNING = "RUNNING";
    public static final String NODE_SUCCEEDED = "SUCCEEDED";
    public static final String NODE_FAILED = "FAILED";
    public static final String NODE_SKIPPED = "SKIPPED";

    private final PipelineNodeMapper nodeMapper;
    private final ArtifactMapper artifactMapper;
    private final ClassicDocumentMapper documentMapper;
    private final ClassicJobMapper jobMapper;
    private final ObjectStorage storage;

    public PipelineService(PipelineNodeMapper nodeMapper,
                           ArtifactMapper artifactMapper,
                           ClassicDocumentMapper documentMapper,
                           ClassicJobMapper jobMapper,
                           ObjectStorage storage) {
        this.nodeMapper = nodeMapper;
        this.artifactMapper = artifactMapper;
        this.documentMapper = documentMapper;
        this.jobMapper = jobMapper;
        this.storage = storage;
    }

    /** 幂等初始化：已存在节点则跳过，保证重试不会产生重复行。 */
    public void initializeNodes(String documentId, String jobId) {
        Long existing = nodeMapper.selectCount(new LambdaQueryWrapper<PipelineNodeEntity>()
                .eq(PipelineNodeEntity::getJobId, jobId));
        if (existing != null && existing > 0) {
            return;
        }
        for (PipelineNodeSpec spec : PipelineNodeSpec.ordered()) {
            PipelineNodeEntity node = new PipelineNodeEntity();
            node.setId(Ids.newId("pnd"));
            node.setDocumentId(documentId);
            node.setJobId(jobId);
            node.setCode(spec.code());
            node.setLabel(spec.label());
            node.setOrderIndex(spec.orderIndex());
            node.setStatus(NODE_PENDING);
            node.setProgress(BigDecimal.ZERO);
            nodeMapper.insert(node);
        }
    }

    public PipelineNodeEntity node(String jobId, PipelineNodeSpec spec) {
        PipelineNodeEntity node = nodeMapper.selectOne(new LambdaQueryWrapper<PipelineNodeEntity>()
                .eq(PipelineNodeEntity::getJobId, jobId)
                .eq(PipelineNodeEntity::getCode, spec.code()));
        if (node == null) {
            throw new IllegalStateException("流水线节点不存在: " + spec.code());
        }
        return node;
    }

    public List<PipelineNodeEntity> nodes(String jobId) {
        return nodeMapper.selectList(new LambdaQueryWrapper<PipelineNodeEntity>()
                .eq(PipelineNodeEntity::getJobId, jobId)
                .orderByAsc(PipelineNodeEntity::getOrderIndex));
    }

    public boolean reusable(PipelineNodeEntity node) {
        return NODE_SUCCEEDED.equals(node.getStatus());
    }

    public Map<String, Object> detail(PipelineNodeEntity node) {
        return Json.readMap(node.getDetail());
    }

    public void start(PipelineNodeEntity node, PipelineNodeSpec spec) {
        node.setStatus(NODE_RUNNING);
        node.setProgress(new BigDecimal("0.05"));
        node.setStartedAt(OffsetDateTime.now());
        node.setErrorMessage(null);
        nodeMapper.updateById(node);

        ClassicDocument document = new ClassicDocument();
        document.setId(node.getDocumentId());
        document.setStatus("PROCESSING");
        document.setCurrentNode(spec.code());
        document.setUpdatedAt(OffsetDateTime.now());
        documentMapper.updateById(document);
    }

    /** 只更新进度与细节，不改变节点状态（在长节点内部循环时使用）。 */
    public void progress(PipelineNodeEntity node, double progress, Map<String, Object> detail) {
        PipelineNodeEntity update = new PipelineNodeEntity();
        update.setId(node.getId());
        update.setProgress(BigDecimal.valueOf(progress));
        update.setDetail(detail == null ? null : Json.write(detail));
        nodeMapper.updateById(update);
    }

    public void finish(PipelineNodeEntity node, double progress, Map<String, Object> detail) {        PipelineNodeEntity update = new PipelineNodeEntity();
        update.setId(node.getId());
        update.setStatus(NODE_SUCCEEDED);
        update.setProgress(BigDecimal.valueOf(progress));
        update.setDetail(detail == null ? null : Json.write(detail));
        update.setFinishedAt(OffsetDateTime.now());
        nodeMapper.updateById(update);
    }

    public void skip(PipelineNodeEntity node, String reason) {
        PipelineNodeEntity update = new PipelineNodeEntity();
        update.setId(node.getId());
        update.setStatus(NODE_SKIPPED);
        update.setProgress(BigDecimal.ONE);
        update.setDetail(Json.write(Map.of("skipped", true, "reason", reason)));
        update.setFinishedAt(OffsetDateTime.now());
        nodeMapper.updateById(update);
    }

    public void fail(PipelineNodeEntity node, String message) {
        PipelineNodeEntity update = new PipelineNodeEntity();
        update.setId(node.getId());
        update.setStatus(NODE_FAILED);
        update.setErrorMessage(message == null ? null
                : (message.length() <= 990 ? message : message.substring(0, 990)));
        update.setFinishedAt(OffsetDateTime.now());
        nodeMapper.updateById(update);
    }

    public ArtifactEntity storeArtifact(String documentId, String jobId, String kind,
                                        byte[] content, String extension, String contentType) {
        ObjectStorage.StoredObject stored = storage.put(content, contentType, extension);
        ArtifactEntity artifact = new ArtifactEntity();
        artifact.setId(Ids.newId("art"));
        artifact.setDocumentId(documentId);
        artifact.setJobId(jobId);
        artifact.setKind(kind);
        artifact.setObjectKey(stored.objectKey());
        artifact.setSha256(stored.sha256());
        artifact.setSizeBytes(stored.sizeBytes());
        artifact.setCreatedAt(OffsetDateTime.now());
        artifactMapper.insert(artifact);
        return artifact;
    }

    public ArtifactEntity artifact(String artifactId) {
        return artifactId == null ? null : artifactMapper.selectById(artifactId);
    }

    public byte[] readArtifact(String artifactId) {
        ArtifactEntity artifact = artifact(artifactId);
        if (artifact == null) {
            throw new IllegalStateException("产物不存在: " + artifactId);
        }
        return storage.get(artifact.getObjectKey());
    }

    public void markDocument(String documentId, String status, String currentNode,
                             String errorCode, String errorMessage) {
        ClassicDocument update = new ClassicDocument();
        update.setId(documentId);
        update.setStatus(status);
        update.setCurrentNode(currentNode);
        update.setErrorCode(errorCode);
        update.setErrorMessage(errorMessage);
        update.setUpdatedAt(OffsetDateTime.now());
        documentMapper.updateById(update);
    }

    public void markJob(String jobId, String status, String errorCode, String errorMessage) {
        ClassicJob update = new ClassicJob();
        update.setId(jobId);
        update.setStatus(status);
        update.setErrorCode(errorCode);
        update.setErrorMessage(errorMessage);
        update.setCompletedAt(OffsetDateTime.now());
        jobMapper.updateById(update);
    }

    public Map<String, Object> detailOf(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            map.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return map;
    }
}
