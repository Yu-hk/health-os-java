package com.kangheng.healthos.service.classics;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import com.kangheng.healthos.contract.classics.ClassicContract;
import com.kangheng.healthos.mapper.classics.ClassicDocumentMapper;
import com.kangheng.healthos.mapper.classics.ClassicJobMapper;
import com.kangheng.healthos.model.classics.ClassicDocument;
import com.kangheng.healthos.model.classics.ClassicJob;
import com.kangheng.healthos.model.classics.PipelineNodeEntity;
import com.kangheng.healthos.common.BizException;
import com.kangheng.healthos.common.Ids;
import com.kangheng.healthos.common.Json;
import com.kangheng.healthos.common.PageResult;
import com.kangheng.healthos.service.outbox.IdempotencyService;
import com.kangheng.healthos.service.outbox.OutboxService;
import com.kangheng.healthos.spi.storage.ObjectStorage;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import com.kangheng.healthos.service.classics.PipelineService;

/**
 * 典籍文档的创建、查询与重试。
 *
 * <p>上传接口只做"落库 + 投递 Outbox"，不在请求线程里跑流水线——
 * 抽取可能持续数分钟，同步执行会拖垮网关超时。
 */
@Service
public class ClassicDocumentService {

    public static final String EVENT_INGEST = "classic.ingest.requested";

    private final ClassicDocumentMapper documentMapper;
    private final ClassicJobMapper jobMapper;
    private final PipelineService pipelineService;
    private final OutboxService outboxService;
    private final IdempotencyService idempotencyService;
    private final ObjectStorage storage;

    public ClassicDocumentService(ClassicDocumentMapper documentMapper,
                                  ClassicJobMapper jobMapper,
                                  PipelineService pipelineService,
                                  OutboxService outboxService,
                                  IdempotencyService idempotencyService,
                                  ObjectStorage storage) {
        this.documentMapper = documentMapper;
        this.jobMapper = jobMapper;
        this.pipelineService = pipelineService;
        this.outboxService = outboxService;
        this.idempotencyService = idempotencyService;
        this.storage = storage;
    }

    @Transactional
    public ClassicContract.UploadResponse upload(String username, String title, String filename,
                                                 String contentType, byte[] content,
                                                 String idempotencyKey) {
        if (content == null || content.length == 0) {
            throw BizException.badRequest("上传文件为空");
        }
        String sha256 = Ids.sha256Hex(content);
        String requestHash = Ids.sha256Hex(
                (username == null ? "" : username) + "|" + (title == null ? "" : title) + "|" + sha256);
        var replay = idempotencyService.findReplay(idempotencyKey, requestHash);
        if (replay.isPresent()) {
            ClassicDocument existing = documentMapper.selectById(replay.get());
            if (existing == null) {
                throw BizException.conflict("幂等记录指向的文档已不存在，请更换 Idempotency-Key 重新提交");
            }
            ClassicJob latest = latestJob(existing.getId());
            return new ClassicContract.UploadResponse(existing.getId(),
                    latest == null ? null : latest.getId(), existing.getStatus());
        }

        String extension = extensionOf(filename);
        ObjectStorage.StoredObject stored = storage.put(content, contentType, extension);

        OffsetDateTime now = OffsetDateTime.now();
        ClassicDocument document = new ClassicDocument();
        document.setId(Ids.newId("doc"));
        document.setTitle(resolveTitle(title, filename));
        document.setSourceObjectKey(stored.objectKey());
        document.setContentType(contentType == null ? "application/octet-stream" : contentType);
        document.setSha256(sha256);
        document.setStatus("UPLOADED");
        document.setCreatedBy(username == null ? "system" : username);
        document.setCreatedAt(now);
        document.setUpdatedAt(now);
        documentMapper.insert(document);

        ClassicJob job = newClassicJob(document.getId(), Map.of(
                "provider", "pipeline-default",
                "submitted_by", username == null ? "unknown" : username));
        outboxService.append("classic_document", document.getId(), EVENT_INGEST,
                Map.of("documentId", document.getId(), "jobId", job.getId()));
        idempotencyService.remember(idempotencyKey, requestHash, document.getId());

        return new ClassicContract.UploadResponse(document.getId(), job.getId(), document.getStatus());
    }

    @Transactional
    public ClassicContract.RetryResponse retry(String documentId, String username) {
        ClassicDocument document = documentMapper.selectById(documentId);
        if (document == null) {
            throw BizException.notFound("典籍文档");
        }
        ClassicJob running = runningJob(documentId);
        if (running != null) {
            throw BizException.conflict("该文档仍有执行中的任务: " + running.getId());
        }
        ClassicJob job = newClassicJob(documentId, Map.of(
                "provider", "pipeline-default",
                "retried_by", username == null ? "unknown" : username));
        pipelineService.markDocument(documentId, "UPLOADED", null, null, null);
        outboxService.append("classic_document", documentId, EVENT_INGEST,
                Map.of("documentId", documentId, "jobId", job.getId()));
        return new ClassicContract.RetryResponse(documentId, job.getId(), "UPLOADED");
    }

    public PageResult<ClassicContract.DocumentView> list(String status, int page, int size) {
        LambdaQueryWrapper<ClassicDocument> wrapper = new LambdaQueryWrapper<ClassicDocument>()
                .orderByDesc(ClassicDocument::getCreatedAt);
        if (StringUtils.hasText(status)) {
            wrapper.eq(ClassicDocument::getStatus, status.toUpperCase());
        }
        Page<ClassicDocument> result = documentMapper.selectPage(
                new Page<>(PageResult.clampPage(page), PageResult.clampSize(size)), wrapper);
        return PageResult.of(result, this::toView);
    }

    public ClassicContract.DocumentDetail detail(String documentId) {
        ClassicDocument document = documentMapper.selectById(documentId);
        if (document == null) {
            throw BizException.notFound("典籍文档");
        }
        ClassicJob job = latestJob(documentId);
        List<PipelineNodeEntity> nodes = job == null ? List.of() : pipelineService.nodes(job.getId());
        return new ClassicContract.DocumentDetail(
                toView(document),
                job == null ? null : job.getId(),
                job == null ? null : job.getStatus(),
                nodes.stream().map(this::toNodeView).toList());
    }

    public List<ClassicContract.NodeView> nodes(String documentId) {
        return detail(documentId).nodes();
    }

    private ClassicJob newClassicJob(String documentId, Map<String, Object> snapshot) {
        ClassicJob job = new ClassicJob();
        job.setId(Ids.newId("job"));
        job.setDocumentId(documentId);
        job.setStatus("PENDING");
        job.setAttempt(0);
        job.setProviderSnapshot(Json.write(snapshot));
        job.setCreatedAt(OffsetDateTime.now());
        jobMapper.insert(job);
        return job;
    }

    private ClassicJob latestJob(String documentId) {
        return jobMapper.selectOne(new LambdaQueryWrapper<ClassicJob>()
                .eq(ClassicJob::getDocumentId, documentId)
                .orderByDesc(ClassicJob::getCreatedAt)
                .last("LIMIT 1"));
    }

    private ClassicJob runningJob(String documentId) {
        return jobMapper.selectOne(new LambdaQueryWrapper<ClassicJob>()
                .eq(ClassicJob::getDocumentId, documentId)
                .in(ClassicJob::getStatus, List.of("PENDING", "RUNNING"))
                .orderByDesc(ClassicJob::getCreatedAt)
                .last("LIMIT 1"));
    }

    private ClassicContract.DocumentView toView(ClassicDocument document) {
        return new ClassicContract.DocumentView(document.getId(), document.getTitle(),
                document.getStatus(), document.getCurrentNode(), document.getContentType(),
                document.getSha256(), document.getErrorCode(), document.getErrorMessage(),
                document.getCreatedAt());
    }

    private ClassicContract.NodeView toNodeView(PipelineNodeEntity node) {
        return new ClassicContract.NodeView(node.getCode(), node.getLabel(),
                node.getOrderIndex() == null ? 0 : node.getOrderIndex(), node.getStatus(),
                node.getProgress() == null ? 0 : node.getProgress().doubleValue(),
                Json.readMap(node.getDetail()), node.getErrorMessage(),
                node.getStartedAt(), node.getFinishedAt());
    }

    private String resolveTitle(String title, String filename) {
        if (StringUtils.hasText(title)) {
            return title.strip().substring(0, Math.min(title.strip().length(), 380));
        }
        if (StringUtils.hasText(filename)) {
            int dot = filename.lastIndexOf('.');
            String base = dot > 0 ? filename.substring(0, dot) : filename;
            return base.strip().substring(0, Math.min(base.strip().length(), 380));
        }
        return "未命名典籍";
    }

    private String extensionOf(String filename) {
        if (!StringUtils.hasText(filename)) {
            return "bin";
        }
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return "bin";
        }
        String extension = filename.substring(dot + 1).toLowerCase();
        return extension.matches("[a-z0-9]{1,8}") ? extension : "bin";
    }
}
