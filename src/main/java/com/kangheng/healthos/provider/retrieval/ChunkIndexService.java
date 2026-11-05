package com.kangheng.healthos.provider.retrieval;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import com.kangheng.healthos.mapper.classics.DocumentChunkMapper;
import com.kangheng.healthos.model.classics.DocumentChunkEntity;
import com.kangheng.healthos.common.Ids;
import com.kangheng.healthos.config.HealthOsProperties;
import com.kangheng.healthos.text.EvidenceLocator;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.kangheng.healthos.model.classics.Segment;
import com.kangheng.healthos.text.TextSegmenter;

/**
 * 检索切片索引。
 *
 * <p>原项目这一步是把 Markdown 推到 RAGFlow 做外部解析；
 * 未配置 RAGFlow 时该节点本就跳过。本切片落本地切片表，
 * 让问答检索在不依赖外部服务的情况下也能工作，接口语义保持一致。
 */
@Service
public class ChunkIndexService {

    private final DocumentChunkMapper chunkMapper;
    private final int chunkChars;
    private final int chunkOverlap;

    public ChunkIndexService(DocumentChunkMapper chunkMapper, HealthOsProperties properties) {
        this.chunkMapper = chunkMapper;
        this.chunkChars = properties.getRetrieval().getChunkChars();
        this.chunkOverlap = properties.getRetrieval().getChunkOverlap();
    }

    @Transactional
    public int index(String documentId, String markdown) {
        chunkMapper.delete(new LambdaQueryWrapper<DocumentChunkEntity>()
                .eq(DocumentChunkEntity::getDocumentId, documentId));
        List<Segment> segments = TextSegmenter.segment(markdown, chunkChars, chunkOverlap);
        int index = 0;
        for (Segment segment : segments) {
            DocumentChunkEntity chunk = new DocumentChunkEntity();
            chunk.setId(Ids.stableId("chk", documentId, String.valueOf(segment.charStart())));
            chunk.setDocumentId(documentId);
            chunk.setChunkIndex(index++);
            chunk.setCharStart(segment.charStart());
            chunk.setCharEnd(segment.charEnd());
            chunk.setPageFrom(EvidenceLocator.pageAt(markdown, segment.charStart()));
            chunk.setContent(segment.text());
            chunk.setCreatedAt(OffsetDateTime.now());
            chunkMapper.insert(chunk);
        }
        return segments.size();
    }

    public List<DocumentChunkEntity> chunks(String documentId, int limit) {
        return chunkMapper.selectList(new LambdaQueryWrapper<DocumentChunkEntity>()
                .eq(DocumentChunkEntity::getDocumentId, documentId)
                .orderByAsc(DocumentChunkEntity::getChunkIndex)
                .last("LIMIT " + Math.max(1, limit)));
    }

    /**
     * 用文本片段反查它在本文件里的位置。
     *
     * <p>用途：RAGFlow 的检索结果只有文本，没有原文偏移。引用要能给到"第几页第几字"，
     * 就得回到本地切片表比对一次。这里<b>只做确定性匹配</b>（空白归一化后互相包含），
     * 不做模糊相似度——定位错了比定位不到更糟。
     */
    public Optional<DocumentChunkEntity> locate(String documentId, String content) {
        String target = squash(content);
        if (target.isEmpty()) {
            return Optional.empty();
        }
        for (DocumentChunkEntity chunk : chunks(documentId, 500)) {
            String source = squash(chunk.getContent());
            if (source.isEmpty()) {
                continue;
            }
            if (source.contains(target) || target.contains(source) || sharesPrefix(source, target, 40)) {
                return Optional.of(chunk);
            }
        }
        return Optional.empty();
    }

    /** 去掉所有空白，让「换行/缩进差异」不影响匹配。 */
    private static String squash(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("[\\s\\u3000\\f]+", "");
    }

    private static boolean sharesPrefix(String left, String right, int length) {
        int limit = Math.min(length, Math.min(left.length(), right.length()));
        if (limit < length) {
            return false;
        }
        return left.regionMatches(0, right, 0, limit);
    }
}
