package com.kangheng.healthos.service.classics;

import java.time.OffsetDateTime;
import java.util.List;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
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

import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 产物查询的边界：坐标要能回溯、越权要 404、产物缺失不能静默返回空文件。
 */
class ClassicArtifactServiceTest {

    private ClassicDocumentMapper documentMapper;
    private DocumentChunkMapper chunkMapper;
    private EvidenceMapper evidenceMapper;
    private ArtifactMapper artifactMapper;
    private ObjectStorage storage;
    private ClassicArtifactService service;

    @BeforeAll
    static void initMeta() {
        for (Class<?> entity : List.of(ClassicDocument.class, DocumentChunkEntity.class,
                EvidenceEntity.class, ArtifactEntity.class)) {
            TableInfoHelper.initTableInfo(
                    new MapperBuilderAssistant(new MybatisConfiguration(), ""), entity);
        }
    }

    @BeforeEach
    void setUp() {
        documentMapper = mock(ClassicDocumentMapper.class);
        chunkMapper = mock(DocumentChunkMapper.class);
        evidenceMapper = mock(EvidenceMapper.class);
        artifactMapper = mock(ArtifactMapper.class);
        storage = mock(ObjectStorage.class);
        service = new ClassicArtifactService(documentMapper, chunkMapper, evidenceMapper,
                artifactMapper, storage);
        when(documentMapper.selectById("doc_1")).thenReturn(document());
    }

    @Test
    @DisplayName("切片分页：带坐标与长度，按原文顺序")
    void 切片带坐标() {
        Page<DocumentChunkEntity> page = new Page<>(1, 20);
        page.setRecords(List.of(chunk("chk_1", 0, 0, 120, "四君子汤主治脾胃气虚。"),
                chunk("chk_2", 1, 120, 260, "人参、白术、茯苓、甘草。")));
        page.setTotal(2);
        when(chunkMapper.selectPage(any(), any())).thenReturn(page);

        PageResult<ClassicContract.ChunkView> result = service.chunks("doc_1", 1, 20);

        assertThat(result.total()).isEqualTo(2);
        assertThat(result.items()).hasSize(2);
        ClassicContract.ChunkView first = result.items().get(0);
        assertThat(first.chunkIndex()).isEqualTo(0);
        assertThat(first.charStart()).isEqualTo(0);
        assertThat(first.charEnd()).isEqualTo(120);
        assertThat(first.contentLength()).isEqualTo("四君子汤主治脾胃气虚。".length());
    }

    @Test
    @DisplayName("切片不属于该文档 → 404，不泄露别的文档的产物")
    void 切片跨文档不可读() {
        DocumentChunkEntity foreign = chunk("chk_9", 0, 0, 10, "别的文档");
        foreign.setDocumentId("doc_other");
        when(chunkMapper.selectById("chk_9")).thenReturn(foreign);

        assertThatThrownBy(() -> service.chunk("doc_1", "chk_9"))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getStatus().value()).isEqualTo(404));
    }

    @Test
    @DisplayName("文档不存在 → 404，而不是空列表")
    void 文档不存在返回404() {
        assertThatThrownBy(() -> service.chunks("doc_missing", 1, 20))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getStatus().value()).isEqualTo(404));
    }

    @Test
    @DisplayName("下载原件：沿用上传类型，文件名去掉非法字符")
    void 下载原件() {
        ArtifactEntity artifact = artifact("art_1", "SOURCE", "objects/abc.pdf");
        when(artifactMapper.selectById("art_1")).thenReturn(artifact);
        when(storage.get("objects/abc.pdf")).thenReturn("%PDF-1.7".getBytes());

        ClassicArtifactService.Download file = service.download("doc_1", "art_1");

        assertThat(file.contentType()).isEqualTo("application/pdf");
        assertThat(file.filename()).endsWith(".pdf");
        assertThat(file.content()).isNotEmpty();
    }

    @Test
    @DisplayName("中间文件按 Markdown / JSON 下发")
    void 中间文件类型() {
        when(artifactMapper.selectById("art_md"))
                .thenReturn(artifact("art_md", "MARKDOWN", "objects/abc.md"));
        when(artifactMapper.selectById("art_json"))
                .thenReturn(artifact("art_json", "EXTRACTION", "objects/abc.json"));
        when(storage.get(any())).thenReturn("# 正文".getBytes());

        assertThat(service.download("doc_1", "art_md").contentType()).startsWith("text/markdown");
        assertThat(service.download("doc_1", "art_md").filename()).endsWith(".md");
        assertThat(service.download("doc_1", "art_json").contentType()).startsWith("application/json");
        assertThat(service.download("doc_1", "art_json").filename()).endsWith(".json");
    }

    @Test
    @DisplayName("对象丢了要报错，不能返回空文件")
    void 产物内容缺失必须报错() {
        when(artifactMapper.selectById("art_1"))
                .thenReturn(artifact("art_1", "SOURCE", "objects/gone.pdf"));
        when(storage.get("objects/gone.pdf")).thenReturn(new byte[0]);

        assertThatThrownBy(() -> service.download("doc_1", "art_1"))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getCode())
                        .isEqualTo("ARTIFACT_CONTENT_MISSING"));
    }

    private ClassicDocument document() {
        ClassicDocument document = new ClassicDocument();
        document.setId("doc_1");
        document.setTitle("伤寒论/卷一");
        document.setContentType("application/pdf");
        document.setStatus("READY");
        document.setCreatedAt(OffsetDateTime.now());
        return document;
    }

    private DocumentChunkEntity chunk(String id, int index, int start, int end, String content) {
        DocumentChunkEntity chunk = new DocumentChunkEntity();
        chunk.setId(id);
        chunk.setDocumentId("doc_1");
        chunk.setChunkIndex(index);
        chunk.setCharStart(start);
        chunk.setCharEnd(end);
        chunk.setPageFrom(1);
        chunk.setContent(content);
        return chunk;
    }

    private ArtifactEntity artifact(String id, String kind, String objectKey) {
        ArtifactEntity artifact = new ArtifactEntity();
        artifact.setId(id);
        artifact.setDocumentId("doc_1");
        artifact.setJobId("job_1");
        artifact.setKind(kind);
        artifact.setObjectKey(objectKey);
        artifact.setSizeBytes(10L);
        artifact.setCreatedAt(OffsetDateTime.now());
        return artifact;
    }
}
