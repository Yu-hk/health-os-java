package com.kangheng.healthos.contract.classics;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 典籍域对外契约。集中在一个类里，与源工程的 {@code patient_contract.py} 保持同样的组织方式。
 */
public final class ClassicContract {

    private ClassicContract() {
    }

    public record DocumentView(String id, String title, String status, String currentNode,
                               String contentType, String sha256, String errorCode,
                               String errorMessage, OffsetDateTime createdAt) {
    }

    public record NodeView(String code, String label, int orderIndex, String status,
                           double progress, Object detail, String errorMessage,
                           OffsetDateTime startedAt, OffsetDateTime finishedAt) {
    }

    public record DocumentDetail(DocumentView document, String jobId, String jobStatus,
                                 List<NodeView> nodes) {
    }

    public record UploadResponse(String documentId, String jobId, String status) {
    }

    public record RetryResponse(String documentId, String jobId, String status) {
    }

    /**
     * 检索切片。
     *
     * <p>{@code charStart/charEnd} 与 {@code pageFrom} 是<b>回溯用的坐标</b>：
     * 问答给出的引用必须能回到原文的哪一段、哪一页。没有坐标的切片只能"看起来像原文"，
     * 无法核对——医疗引用不接受这种近似。
     */
    public record ChunkView(String id, int chunkIndex, int charStart, int charEnd,
                            Integer pageFrom, String content, int contentLength) {
    }

    /** 证据：一段原文引用及其精确区间，是图谱关系与问答引用的共同溯源点。 */
    public record EvidenceView(String id, String artifactId, String quote,
                               Integer charStart, Integer charEnd,
                               Integer pageFrom, Integer pageTo, double confidence,
                               OffsetDateTime createdAt) {
    }

    /** 流程产物（SOURCE / MARKDOWN / EXTRACTION / MANIFEST）。 */
    public record ArtifactView(String id, String jobId, String kind, String objectKey,
                               String sha256, long sizeBytes, OffsetDateTime createdAt) {
    }
}
