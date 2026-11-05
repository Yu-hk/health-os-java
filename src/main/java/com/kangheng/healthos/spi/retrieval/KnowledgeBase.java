package com.kangheng.healthos.spi.retrieval;

import java.util.List;

/**
 * 知识库抽象：把"文档切片"与"按问题检索"两件事从流水线里解耦。
 *
 * <p>三个实现对应三种现实约束：
 * <ul>
 *   <li>{@code LocalKnowledgeBase}：零外部依赖，切片落 PG，关键词打分。用于本地开发与兜底；</li>
 *   <li>{@code RagflowKnowledgeBase}：真实 RAGFlow 实例，借助其向量/混合检索与重排；</li>
 *   <li>{@code HybridKnowledgeBase}：写两份，检索优先 RAGFlow、失败或空结果回落本地。</li>
 * </ul>
 *
 * <p>接口刻意只包含这四个方法。RAGFlow 的"解析进度轮询"属于实现内部细节，
 * 上层只关心"索引好了没有、切了多少片"。
 */
public interface KnowledgeBase {

    /** local / ragflow / hybrid，写入流水线节点 detail。 */
    String name();

    /**
     * 建立（或重建）某文档的索引。要求幂等：同一文档重复调用结果一致，不产生重复切片。
     */
    IndexOutcome index(String documentId, String title, String markdown);

    /** 删除外部索引；本地实现无需处理。 */
    default void delete(String documentId) {
    }

    /**
     * 按问题检索相关切片，返回按相关度降序的前 {@code topK} 条。
     *
     * <p>{@code page/charStart/charEnd} 允许为 null——外部知识库不保证返回原文偏移，
     * 由上层决定是否回填。绝不允许的是"编造偏移"。
     */
    List<RetrievedChunk> retrieve(String documentId, String question, int topK);

    /**
     * 列出外部知识库里现有的数据集。
     *
     * <p>只有"自带目录"的实现才返回非空（目前是 RAGFlow）。本地实现返回空列表——
     * 本地没有"库"这个概念，本地切片是跟着文档走的。
     */
    default List<DatasetInfo> datasets() {
        return List.of();
    }

    /**
     * 在<b>指定的一组数据集</b>上检索，不限定单个文档。
     *
     * <p>与 {@link #retrieve} 的区别是检索范围：前者是"这篇典籍的第几段最相关"，
     * 这里是"这几个库里的哪些内容最相关"。患者绑定的资料库走的是这条路径。
     *
     * <p>默认实现直接抛不支持：宁可在调用处显式降级并留痕，也不要返回空列表
     * 让上层以为"查过了，确实没有"。
     */
    default List<RetrievedChunk> retrieveDatasets(List<String> datasetIds, String question, int topK) {
        throw new KnowledgeBaseException(name() + " 不支持按数据集检索", false);
    }

    default boolean healthy() {
        return true;
    }

    /**
     * 外部知识库的一个数据集。
     *
     * @param chunkCount    已解析出的切片数，用于判断"库建了但还没解析完"
     * @param documentCount 文档数
     */
    record DatasetInfo(String id, String name, int chunkCount, int documentCount) {
    }

    /**
     * @param externalRef 外部系统内的引用（RAGFlow 的 datasetId），便于排查"索引打到了哪个库"
     * @param note        非致命异常说明。hybrid 模式下 RAGFlow 失败但本地成功时写在这里，
     *                    不吞掉失败信息，也不因为降级成功就让它消失
     */
    record IndexOutcome(String engine, int chunks, String externalRef, long latencyMs, String note) {

        public IndexOutcome(String engine, int chunks, String externalRef, long latencyMs) {
            this(engine, chunks, externalRef, latencyMs, null);
        }
    }

    /**
     * 一条召回片段。
     *
     * <p><b>为什么带 {@code documentId}</b>：跨库检索（患者绑定的知识库）会把多个文档的片段
     * 混在同一批结果里。若只回 {@code chunkId}，调用方无从知道这条出自哪份文档，
     * 引用就变成了"查得到内容、点不进原文"。RAGFlow 的检索响应本来就带 {@code document_id}，
     * 丢掉它等于凭空降低可追溯性。
     *
     * @param chunkId    切片 ID（引擎内唯一；不同引擎之间不通用）
     * @param documentId 该切片所属文档 ID；引擎未返回时为 {@code null}，不猜
     * @param page       页码，补不上就 {@code null}
     */
    record RetrievedChunk(String chunkId, String documentId, String content, Integer page,
                          Integer charStart, Integer charEnd, double score) {

        /** 兼容不带文档归属的调用点：明确表示"不知道出自哪份文档"。 */
        public RetrievedChunk(String chunkId, String content, Integer page,
                              Integer charStart, Integer charEnd, double score) {
            this(chunkId, null, content, page, charStart, charEnd, score);
        }
    }
}
