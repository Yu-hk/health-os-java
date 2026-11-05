package com.kangheng.healthos.controller.classics;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.kangheng.healthos.controller.auth.CurrentUser;
import com.kangheng.healthos.contract.classics.ClassicContract;
import com.kangheng.healthos.service.classics.ClassicArtifactService;
import com.kangheng.healthos.service.classics.ClassicDocumentService;
import com.kangheng.healthos.common.BizException;
import com.kangheng.healthos.common.PageResult;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 典籍接口。所有接口都需要登录（{@code @CurrentUser} 触发会话校验）。
 */
@RestController
@RequestMapping("/api/v1/classics")
public class ClassicController {

    private final ClassicDocumentService documentService;
    private final ClassicArtifactService artifactService;

    public ClassicController(ClassicDocumentService documentService,
                             ClassicArtifactService artifactService) {
        this.documentService = documentService;
        this.artifactService = artifactService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ClassicContract.UploadResponse upload(@CurrentUser String username,
                                                 @RequestParam("file") MultipartFile file,
                                                 @RequestParam(value = "title", required = false) String title,
                                                 @RequestHeader(value = "Idempotency-Key", required = false)
                                                 String idempotencyKey) {
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException ex) {
            throw BizException.badRequest("读取上传文件失败: " + ex.getMessage());
        }
        return documentService.upload(username, title, file.getOriginalFilename(),
                file.getContentType(), content, idempotencyKey);
    }

    @GetMapping
    public PageResult<ClassicContract.DocumentView> list(
            @CurrentUser String username,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return documentService.list(status, page, size);
    }

    @GetMapping("/{documentId}")
    public ClassicContract.DocumentDetail detail(@CurrentUser String username,
                                                 @PathVariable String documentId) {
        return documentService.detail(documentId);
    }

    @GetMapping("/{documentId}/nodes")
    public List<ClassicContract.NodeView> nodes(@CurrentUser String username,
                                                @PathVariable String documentId) {
        return documentService.nodes(documentId);
    }

    @PostMapping("/{documentId}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ClassicContract.RetryResponse retry(@CurrentUser String username,
                                               @PathVariable String documentId) {
        return documentService.retry(documentId, username);
    }

    /**
     * 切片列表。
     *
     * <p>这是"抽取到底抽了什么"的入口：分片边界、页码、字符区间都在这里。
     * 按原文顺序返回，不是按相关度——它回答的是"文档被切成了什么"，不是"哪段最像问题"。
     */
    @GetMapping("/{documentId}/chunks")
    public PageResult<ClassicContract.ChunkView> chunks(
            @CurrentUser String username,
            @PathVariable String documentId,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return artifactService.chunks(documentId, page, size);
    }

    @GetMapping("/{documentId}/chunks/{chunkId}")
    public ClassicContract.ChunkView chunk(@CurrentUser String username,
                                           @PathVariable String documentId,
                                           @PathVariable String chunkId) {
        return artifactService.chunk(documentId, chunkId);
    }

    /** 证据列表：图谱关系与问答引用的共同溯源点。 */
    @GetMapping("/{documentId}/evidence")
    public PageResult<ClassicContract.EvidenceView> evidence(
            @CurrentUser String username,
            @PathVariable String documentId,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return artifactService.evidence(documentId, page, size);
    }

    /** 产物清单：原件、Markdown、抽取结果、manifest。 */
    @GetMapping("/{documentId}/artifacts")
    public List<ClassicContract.ArtifactView> artifacts(@CurrentUser String username,
                                                        @PathVariable String documentId) {
        return artifactService.artifacts(documentId);
    }

    /** 下载产物。原件按上传时的类型下发，中间文件按 Markdown / JSON 下发。 */
    @GetMapping("/{documentId}/artifacts/{artifactId}/download")
    public ResponseEntity<byte[]> download(@CurrentUser String username,
                                           @PathVariable String documentId,
                                           @PathVariable String artifactId) {
        ClassicArtifactService.Download file = artifactService.download(documentId, artifactId);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(file.filename(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.parseMediaType(file.contentType()))
                .contentLength(file.content().length)
                .body(file.content());
    }
}
