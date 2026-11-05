package com.kangheng.healthos.service.classics;

import java.util.List;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import com.kangheng.healthos.common.BizException;
import com.kangheng.healthos.common.PageResult;
import com.kangheng.healthos.contract.classics.ClassicContract;
import com.kangheng.healthos.mapper.classics.ArtifactMapper;
import com.kangheng.healthos.mapper.classics.ClassicDocumentMapper;
import com.kangheng.healthos.mapper.classics.DocumentChunkMapper;
import com.kangheng.healthos.mapper.graph.EvidenceMapper;
import com.kangheng.healthos.model.classics.ArtifactEntity;
import com.kangheng.healthos.model.classics.ClassicDocument;
import com.kangheng.healthos.model.classics.DocumentChunkEntity;
import com.kangheng.healthos.model.graph.EvidenceEntity;
import com.kangheng.healthos.spi.storage.ObjectStorage;

import org.springframework.stereotype.Service;

/**
 * 典籍流水线的<b>产物查询</b>：切片、证据、原件与中间文件。
 *
 * <p>补的是原工程 {@code /chunks}、{@code /evidence}、{@code /artifacts/{id}/download}
 * 那几条接口。在此之前本工程的流水线是"能跑但看不见"：五节点跑完，抽取到了什么、
 * 引用了原文哪一段、原始 PDF 在哪，都只能进数据库翻。
 *
 * <p>三条语义：
 * <ul>
 *   <li><b>产物属于文档</b>：{@code chunkId} / {@code artifactId} 不属于该文档时返回 404，
 *       不做"能查到就给你"——那等于把别的文档的产物泄漏出去；</li>
 *   <li><b>下载就是原件</b>：不做转换、不做重排，字节与写入时一致（可用 {@code sha256} 校验）；</li>
 *   <li><b>切片带坐标</b>：返回 {@code charStart/charEnd/pageFrom}，前端据此回跳原文。</li>
 * </ul>
 */
@Service
public class ClassicArtifactService {

    private final ClassicDocumentMapper documentMapper;
    private final DocumentChunkMapper chunkMapper;
    private final EvidenceMapper evidenceMapper;
    private final ArtifactMapper artifactMapper;
    private final ObjectStorage storage;

    public ClassicArtifactService(ClassicDocumentMapper documentMapper,
                                  DocumentChunkMapper chunkMapper,
                                  EvidenceMapper evidenceMapper,
                                  ArtifactMapper artifactMapper,
                                  ObjectStorage storage) {
        this.documentMapper = documentMapper;
        this.chunkMapper = chunkMapper;
        this.evidenceMapper = evidenceMapper;
        this.artifactMapper = artifactMapper;
        this.storage = storage;
    }

    /** 切片分页。按 chunkIndex 升序——这是原文顺序，不是相关度顺序。 */
    public PageResult<ClassicContract.ChunkView> chunks(String documentId, int page, int size) {
        requireDocument(documentId);
        Page<DocumentChunkEntity> result = chunkMapper.selectPage(
                new Page<>(PageResult.clampPage(page), PageResult.clampSize(size)),
                new LambdaQueryWrapper<DocumentChunkEntity>()
                        .eq(DocumentChunkEntity::getDocumentId, documentId)
                        .orderByAsc(DocumentChunkEntity::getChunkIndex));
        return PageResult.of(result, this::toChunkView);
    }

    public ClassicContract.ChunkView chunk(String documentId, String chunkId) {
        requireDocument(documentId);
        DocumentChunkEntity chunk = chunkMapper.selectById(chunkId);
        if (chunk == null || !documentId.equals(chunk.getDocumentId())) {
            throw BizException.notFound("切片");
        }
        return toChunkView(chunk);
    }

    /** 证据分页。按原文位置升序，便于对照阅读。 */
    public PageResult<ClassicContract.EvidenceView> evidence(String documentId, int page, int size) {
        requireDocument(documentId);
        Page<EvidenceEntity> result = evidenceMapper.selectPage(
                new Page<>(PageResult.clampPage(page), PageResult.clampSize(size)),
                new LambdaQueryWrapper<EvidenceEntity>()
                        .eq(EvidenceEntity::getDocumentId, documentId)
                        .orderByAsc(EvidenceEntity::getCharStart));
        return PageResult.of(result, this::toEvidenceView);
    }

    /** 产物清单。原件在最前，中间文件按生成顺序。 */
    public List<ClassicContract.ArtifactView> artifacts(String documentId) {
        requireDocument(documentId);
        return artifactMapper.selectList(new LambdaQueryWrapper<ArtifactEntity>()
                        .eq(ArtifactEntity::getDocumentId, documentId)
                        .orderByAsc(ArtifactEntity::getCreatedAt))
                .stream().map(this::toArtifactView).toList();
    }

    /** 下载产物。 */
    public Download download(String documentId, String artifactId) {
        ClassicDocument document = requireDocument(documentId);
        ArtifactEntity artifact = artifactMapper.selectById(artifactId);
        if (artifact == null || !documentId.equals(artifact.getDocumentId())) {
            throw BizException.notFound("流程产物");
        }
        byte[] content;
        try {
            content = storage.get(artifact.getObjectKey());
        } catch (RuntimeException ex) {
            // 产物记录在、对象没了：这是数据不一致，必须明确报错而不是返回空文件。
            throw new BizException("ARTIFACT_CONTENT_MISSING",
                    "产物内容不可读取（objectKey=" + artifact.getObjectKey() + "）: " + ex.getMessage(),
                    org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR);
        }
        if (content == null || content.length == 0) {
            throw new BizException("ARTIFACT_CONTENT_MISSING",
                    "产物内容为空（objectKey=" + artifact.getObjectKey() + "）",
                    org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR);
        }
        String extension = extensionOf(artifact, document);
        String filename = safeName(document.getTitle()) + "-" + artifact.getKind().toLowerCase()
                + "." + extension;
        return new Download(filename, contentTypeOf(artifact, document), content);
    }

    public record Download(String filename, String contentType, byte[] content) {
    }

    // ------------------------------------------------------------ 内部

    private ClassicDocument requireDocument(String documentId) {
        ClassicDocument document = documentMapper.selectById(documentId);
        if (document == null) {
            throw BizException.notFound("典籍文档");
        }
        return document;
    }

    private ClassicContract.ChunkView toChunkView(DocumentChunkEntity chunk) {
        String content = chunk.getContent() == null ? "" : chunk.getContent();
        return new ClassicContract.ChunkView(chunk.getId(),
                chunk.getChunkIndex() == null ? 0 : chunk.getChunkIndex(),
                chunk.getCharStart() == null ? 0 : chunk.getCharStart(),
                chunk.getCharEnd() == null ? 0 : chunk.getCharEnd(),
                chunk.getPageFrom(), content, content.length());
    }

    private ClassicContract.EvidenceView toEvidenceView(EvidenceEntity evidence) {
        return new ClassicContract.EvidenceView(evidence.getId(), evidence.getArtifactId(),
                evidence.getQuote(), evidence.getCharStart(), evidence.getCharEnd(),
                evidence.getPageFrom(), evidence.getPageTo(),
                evidence.getConfidence() == null ? 1.0d : evidence.getConfidence().doubleValue(),
                evidence.getCreatedAt());
    }

    private ClassicContract.ArtifactView toArtifactView(ArtifactEntity artifact) {
        return new ClassicContract.ArtifactView(artifact.getId(), artifact.getJobId(),
                artifact.getKind(), artifact.getObjectKey(), artifact.getSha256(),
                artifact.getSizeBytes() == null ? 0L : artifact.getSizeBytes(),
                artifact.getCreatedAt());
    }

    private String contentTypeOf(ArtifactEntity artifact, ClassicDocument document) {
        String kind = artifact.getKind() == null ? "" : artifact.getKind().toUpperCase();
        return switch (kind) {
            case "SOURCE" -> document.getContentType() == null
                    ? "application/octet-stream" : document.getContentType();
            case "MARKDOWN" -> "text/markdown; charset=utf-8";
            case "EXTRACTION", "MANIFEST" -> "application/json; charset=utf-8";
            default -> "application/octet-stream";
        };
    }

    private String extensionOf(ArtifactEntity artifact, ClassicDocument document) {
        String kind = artifact.getKind() == null ? "" : artifact.getKind().toUpperCase();
        if ("MARKDOWN".equals(kind)) {
            return "md";
        }
        if ("EXTRACTION".equals(kind) || "MANIFEST".equals(kind)) {
            return "json";
        }
        // 原件沿用上传时的扩展名，避免下载下来打不开。
        String objectKey = artifact.getObjectKey();
        if (objectKey != null) {
            int dot = objectKey.lastIndexOf('.');
            if (dot > 0 && dot < objectKey.length() - 1) {
                String ext = objectKey.substring(dot + 1).toLowerCase();
                if (ext.matches("[a-z0-9]{1,8}")) {
                    return ext;
                }
            }
        }
        return switch (document.getContentType() == null ? "" : document.getContentType()) {
            case "application/pdf" -> "pdf";
            case "text/markdown" -> "md";
            case "text/plain" -> "txt";
            default -> "bin";
        };
    }

    /** 文件名里去掉路径分隔符与控制字符：Content-Disposition 不接受这些。 */
    private String safeName(String title) {
        if (title == null || title.isBlank()) {
            return "artifact";
        }
        String cleaned = title.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", "_").strip();
        return cleaned.length() > 60 ? cleaned.substring(0, 60) : cleaned;
    }
}
