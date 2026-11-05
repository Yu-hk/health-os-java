package com.kangheng.healthos.model.qa;

/**
 * 一条引用：回答里某个结论所依据的原文片段。
 *
 * <p>字段语义：
 * <ul>
 *   <li>{@code evidenceId} 证据/切片 ID，落库后可用于反查；</li>
 *   <li>{@code quote} <b>逐字</b>原文片段，禁止改写后挂出处；</li>
 *   <li>{@code page}/{@code charStart}/{@code charEnd} 定位信息，
 *       补不上就留 {@code null}——猜出来的页码比没有页码更危险；</li>
 *   <li>{@code score} 召回得分，仅用于排序与排查，不参与"是否作答"的判定。</li>
 * </ul>
 *
 * <p><b>为什么在 model 而不在 contract</b>：它同时是<b>扩展点的返回值</b>
 * （{@code spi.qa.BranchResult}）与<b>对外响应的组成部分</b>（{@code QaContract}）。
 * 留在 contract 会让 SPI 反过来依赖对外契约层——接口层不该知道 API 长什么样。
 * 下沉到 model 后，contract 和 spi 都指向它，方向就一致了。
 *
 * <p>JSON 字段名由 record 组件名决定，下沉不改变任何接口返回结构。
 *
 * @param documentId 该引用所属文档 ID，可反查 {@code /classics/{id}/chunks/{chunkId}}；
 *                   引擎未给出时留 {@code null}。跨库检索会把多份文档混在一起，
 *                   没有这个字段就无法回答"这条依据出自哪份文档"
 */
public record Citation(String evidenceId, String quote, Integer page, Integer charStart,
                       Integer charEnd, double score, String documentId) {

    /** 已知出处的调用点走这里；文档归属不明时 {@code documentId} 为 {@code null}。 */
    public Citation(String evidenceId, String quote, Integer page, Integer charStart,
                    Integer charEnd, double score) {
        this(evidenceId, quote, page, charStart, charEnd, score, null);
    }
}
