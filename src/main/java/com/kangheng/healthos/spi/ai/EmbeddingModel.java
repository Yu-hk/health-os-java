package com.kangheng.healthos.spi.ai;

import java.util.List;

/**
 * 向量化模型（embedding）抽象。
 *
 * <p>它与 {@link LlmProvider} 分开，因为两者的失败语义不同：模型答不好是"结果问题"，
 * embedding 不可用是"检索降级"——后者必须能悄悄退回词面打分，而不是让问答失败。
 * 因此这里的实现只负责"把文本变成向量"，降级策略放在调用方。
 *
 * <p>约定：<b>返回的向量必须已做 L2 归一化</b>。归一化之后余弦相似度等于点积，
 * 存储与计算都省一半；这件事必须由实现方保证，调用方不该各自再归一化一次。
 */
public interface EmbeddingModel {

    /** 形如 {@code ollama:bge-m3}。它会写进 {@code chunk_vector.model}，换模型等于换一套向量。 */
    String id();

    /** 向量维度。0 表示未启用。 */
    int dimension();

    boolean enabled();

    /**
     * 把一条文本向量化。
     *
     * @throws EmbeddingException 上游不可用、返回为空或维度不符
     */
    float[] embed(String text);

    /**
     * 批量向量化，返回顺序与入参严格一致。
     *
     * <p>批量是为了省往返：一条切片一次 HTTP 会把索引时间从秒级拖到分钟级。
     * 实现可以自行分片，但<b>不允许重排</b>——顺序错了，向量就贴到了别人的正文上，
     * 而且检索照样能出结果，只是全是错的。
     */
    List<float[]> embedBatch(List<String> texts);

    /** 探活。必须廉价：只在 {@code /health/dependencies} 里按需调用。 */
    boolean healthy();

    /** 维度是否与期望一致（期望为 0 时不做校验）。 */
    default boolean dimensionMatches(float[] vector) {
        return vector != null && (dimension() <= 0 || vector.length == dimension());
    }

    /** L2 归一化；零向量原样返回（下游按"无方向"处理，不制造 NaN）。 */
    static float[] normalize(float[] vector) {
        if (vector == null || vector.length == 0) {
            return new float[0];
        }
        double sum = 0;
        for (float value : vector) {
            sum += (double) value * value;
        }
        double norm = Math.sqrt(sum);
        if (norm == 0 || Double.isNaN(norm) || Double.isInfinite(norm)) {
            return vector.clone();
        }
        float[] result = new float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            result[i] = (float) (vector[i] / norm);
        }
        return result;
    }

    /**
     * 余弦相似度，落在 [-1, 1]。
     *
     * <p>入参若已归一化，这里就是点积；仍然按余弦算，是为了让"外部传进来的裸向量"也不会算错。
     * 维度不一致直接返回 0 而不是抛错——换了模型之后旧向量还在表里，这是**预期情况**，
     * 让它们静默出局即可，不该把整个检索打挂。
     */
    static double cosine(float[] left, float[] right) {
        if (left == null || right == null || left.length == 0 || left.length != right.length) {
            return 0;
        }
        double dot = 0;
        double leftNorm = 0;
        double rightNorm = 0;
        for (int i = 0; i < left.length; i++) {
            dot += (double) left[i] * right[i];
            leftNorm += (double) left[i] * left[i];
            rightNorm += (double) right[i] * right[i];
        }
        if (leftNorm == 0 || rightNorm == 0) {
            return 0;
        }
        double value = dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
        if (Double.isNaN(value)) {
            return 0;
        }
        return Math.max(-1d, Math.min(1d, value));
    }
}
