package com.kangheng.healthos.spi.routing;

/**
 * 模型用量计量与配额闸门。
 *
 * <p>两件事必须成对出现，否则只是记录、不是控制：
 * <ul>
 *   <li>{@link #assertWithinQuota} 在<b>调用前</b>问一句"还能不能再花"；</li>
 *   <li>{@link #record} 在<b>调用成功后</b>把上游回报的 token 数记上。</li>
 * </ul>
 *
 * <p><b>为什么配额按主线而不按账号</b>：成本是主线在烧（抽取、问答、记忆发布各一条），
 * 账号只是发起人。按账号限流会让"某个人今天问得多"和"某条主线失控重试"混为一谈，
 * 而后者才是真正会烧穿预算的那种故障。
 *
 * <p><b>记账失败不阻塞业务</b>：调用已经成功、钱已经花了，这时候因为"写不进统计表"
 * 把答案丢掉，等于花钱买了个失败。所以实现必须自己吞掉写库异常并留日志。
 */
public interface ModelUsageMeter {

    /**
     * 配额闸门。
     *
     * @throws ModelQuotaExceededException 当日用量已达上限
     */
    void assertWithinQuota(String scope, String model);

    /** 记一笔用量。{@code providerId}/{@code traceId} 可为 null。 */
    void record(String scope, String providerId, String model, String traceId, TokenUsage usage);

    /**
     * 上游回报的 token 数。
     *
     * <p>{@code total} 缺失时用 prompt+completion 兜底；
     * {@code cachedPromptTokens} 是<b>输入里命中缓存的那部分</b>（含在 {@code promptTokens} 内，
     * 不是额外加的），上游不给就记 0——它的唯一用途是按更便宜的缓存价折算成本。
     */
    record TokenUsage(int promptTokens, int completionTokens, int totalTokens,
                      int cachedPromptTokens) {

        public static TokenUsage of(Integer prompt, Integer completion) {
            return of(prompt, completion, null, null);
        }

        public static TokenUsage of(Integer prompt, Integer completion, Integer total) {
            return of(prompt, completion, total, null);
        }

        public static TokenUsage of(Integer prompt, Integer completion, Integer total,
                                    Integer cachedPrompt) {
            int p = prompt == null ? 0 : Math.max(0, prompt);
            int c = completion == null ? 0 : Math.max(0, completion);
            int t = (total == null || total < 0) ? p + c : total;
            int hit = cachedPrompt == null ? 0 : Math.max(0, cachedPrompt);
            // 缓存命中数不可能超过输入总数；上游算错时按输入总数封顶，免得成本变负
            return new TokenUsage(p, c, t, Math.min(hit, p));
        }

        public boolean empty() {
            return promptTokens <= 0 && completionTokens <= 0 && totalTokens <= 0;
        }
    }
}
