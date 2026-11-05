package com.kangheng.healthos.service.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.kangheng.healthos.config.HealthOsProperties;
import com.kangheng.healthos.mapper.usage.ModelUsageMapper;
import com.kangheng.healthos.model.usage.ModelUsageRecord;
import com.kangheng.healthos.spi.routing.ModelQuotaExceededException;
import com.kangheng.healthos.spi.routing.ModelUsageMeter.TokenUsage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 成本口径与配额闸门。
 *
 * <p>钉住四件事：
 * <ul>
 *   <li>{@code cost_micros = tokens × 单价}（单价是元/百万 token，两者正好抵消）；</li>
 *   <li>没配单价就留空，<b>不猜</b>——猜出来的总额看着像真的，更难发现；</li>
 *   <li>配额只按当天、按主线统计，已达上限才拒；{@code 0} 表示不限且完全不查库；</li>
 *   <li>记账失败不影响业务：钱已经花了，不能因为统计写不进去把答案丢掉。</li>
 * </ul>
 */
class ModelUsageServiceTest {

    private static final String SCOPE = "qa_answer";

    private ModelUsageMapper mapper;
    private HealthOsProperties properties;
    private ModelUsageService service;

    @BeforeEach
    void setUp() {
        mapper = mock(ModelUsageMapper.class);
        properties = new HealthOsProperties();
        HealthOsProperties.ModelUsage config = properties.getModelUsage();
        config.setEnabled(true);
        config.setCurrency("CNY");
        HealthOsProperties.ModelUsage.Price price = new HealthOsProperties.ModelUsage.Price();
        price.setCacheHitInputPerMillion(0.5);
        price.setCacheMissInputPerMillion(2);
        price.setOutputPerMillion(8);
        config.setPrices(new LinkedHashMap<>(Map.of("deepseek-v4-flash", price)));
        service = new ModelUsageService(mapper, properties);
    }

    // ------------------------------------------------------------ 记账

    @Test
    @DisplayName("有单价时 cost_micros = 输入×输入价 + 输出×输出价")
    void recordComputesCostFromPrice() {
        service.record(SCOPE, "deepseek", "deepseek-v4-flash", "tr_1", TokenUsage.of(1000, 500));

        ModelUsageRecord saved = captureInsert();
        assertThat(saved.getScopeKey()).isEqualTo(SCOPE);
        assertThat(saved.getPromptTokens()).isEqualTo(1000);
        assertThat(saved.getCompletionTokens()).isEqualTo(500);
        assertThat(saved.getTotalTokens()).isEqualTo(1500);
        // 1000×2 + 500×8 = 6000 微元 = 0.006 元
        assertThat(saved.getCostMicros()).isEqualTo(6000L);
        assertThat(saved.getCurrency()).isEqualTo("CNY");
        assertThat(saved.getId()).startsWith("muse_");
    }

    @Test
    @DisplayName("模型名大小写不同也能匹配到单价；没配单价则 cost 为空")
    void unpricedModelLeavesCostNull() {
        service.record(SCOPE, "deepseek", "DeepSeek-V4-Flash", null, TokenUsage.of(100, 100));
        assertThat(captureInsert().getCostMicros()).isEqualTo(1000L); // 100×2 + 100×8

        service.record(SCOPE, "unknown", "gpt-unknown", null, TokenUsage.of(100, 100));
        ArgumentCaptor<ModelUsageRecord> captor = ArgumentCaptor.forClass(ModelUsageRecord.class);
        verify(mapper, org.mockito.Mockito.times(2)).insert(captor.capture());
        assertThat(captor.getAllValues().get(1).getCostMicros()).isNull();
    }

    @Test
    @DisplayName("上游没回报 usage 时不写零行；关闭统计时也不写")
    void skipsEmptyUsageAndDisabled() {
        service.record(SCOPE, "deepseek", "deepseek-v4-flash", null, TokenUsage.of(0, 0));
        verify(mapper, never()).insert(any(ModelUsageRecord.class));

        properties.getModelUsage().setEnabled(false);
        service.record(SCOPE, "deepseek", "deepseek-v4-flash", null, TokenUsage.of(10, 10));
        verify(mapper, never()).insert(any(ModelUsageRecord.class));
    }

    @Test
    @DisplayName("记账写库失败不抛异常：这次调用已经成功、钱已经花了")
    void insertFailureDoesNotBreakCall() {
        when(mapper.insert(any(ModelUsageRecord.class)))
                .thenThrow(new IllegalStateException("db down"));

        assertThatCode(() -> service.record(SCOPE, "deepseek", "deepseek-v4-flash", null,
                TokenUsage.of(10, 10))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("总 token 缺失时用 input+output 兜底，不记 0")
    void totalFallsBackToSum() {
        service.record(SCOPE, "deepseek", "deepseek-v4-flash", null,
                TokenUsage.of(300, 200, null));
        assertThat(captureInsert().getTotalTokens()).isEqualTo(500);
    }

    // ------------------------------------------------------------ 闸门

    @Test
    @DisplayName("配额为 0 表示不限，且完全不查库")
    void zeroQuotaSkipsQuery() {
        service.assertWithinQuota(SCOPE, "deepseek-v4-flash");
        verify(mapper, never()).sumScopeSince(anyString(), any());
    }

    @Test
    @DisplayName("token 达上限抛 QUOTA_TOKENS，并带上已用与上限")
    void tokenQuotaExceeded() {
        properties.getModelUsage().setDailyTokenQuota(1000);
        when(mapper.sumScopeSince(eq(SCOPE), any())).thenReturn(sums(1000L, 0L));

        assertThatThrownBy(() -> service.assertWithinQuota(SCOPE, "deepseek-v4-flash"))
                .isInstanceOf(ModelQuotaExceededException.class)
                .satisfies(ex -> {
                    ModelQuotaExceededException quota = (ModelQuotaExceededException) ex;
                    assertThat(quota.code()).isEqualTo(ModelQuotaExceededException.QUOTA_TOKENS);
                    assertThat(quota.used()).isEqualTo(1000L);
                    assertThat(quota.limit()).isEqualTo(1000L);
                });
    }

    @Test
    @DisplayName("成本达上限抛 QUOTA_COST；未达上限不拦")
    void costQuotaExceeded() {
        properties.getModelUsage().setDailyCostQuotaMicros(1_000_000); // 1 元
        when(mapper.sumScopeSince(eq(SCOPE), any()))
                .thenReturn(sums(10L, 500_000L));
        service.assertWithinQuota(SCOPE, "deepseek-v4-flash");

        when(mapper.sumScopeSince(eq(SCOPE), any()))
                .thenReturn(sums(10L, 1_000_000L));
        assertThatThrownBy(() -> service.assertWithinQuota(SCOPE, "deepseek-v4-flash"))
                .isInstanceOf(ModelQuotaExceededException.class)
                .satisfies(ex -> assertThat(((ModelQuotaExceededException) ex).code())
                        .isEqualTo(ModelQuotaExceededException.QUOTA_COST));
    }

    @Test
    @DisplayName("主线为空归到 default，避免 GROUP BY 出现一个看不见的分组")
    void blankScopeFallsBackToDefault() {
        when(mapper.sumScopeSince(eq("default"), any())).thenReturn(sums(0L, 0L));
        properties.getModelUsage().setDailyTokenQuota(1000);

        service.assertWithinQuota("  ", "deepseek-v4-flash");
        verify(mapper).sumScopeSince(eq("default"), any());
    }

    // ------------------------------------------------------------ 看板

    @Test
    @DisplayName("看板汇总：token 与金额相加，并单列没算钱的次数")
    void summaryAggregatesAndExposesUnpriced() {
        Map<String, Object> flash = new LinkedHashMap<>();
        flash.put("model", "deepseek-v4-flash");
        flash.put("calls", 3L);
        flash.put("promptTokens", 1000L);
        flash.put("completionTokens", 500L);
        flash.put("cachedPromptTokens", 400L);
        flash.put("totalTokens", 1500L);
        flash.put("costMicros", 6000L);
        flash.put("unpricedCalls", 0L);
        Map<String, Object> unknown = new LinkedHashMap<>();
        unknown.put("model", "gpt-unknown");
        unknown.put("calls", 2L);
        unknown.put("totalTokens", 400L);
        unknown.put("costMicros", 0L);
        unknown.put("unpricedCalls", 2L);
        when(mapper.summaryByModelSince(any())).thenReturn(List.of(flash, unknown));

        Map<String, Object> scopeRow = new LinkedHashMap<>();
        scopeRow.put("scopeKey", SCOPE);
        scopeRow.put("calls", 5L);
        scopeRow.put("totalTokens", 1900L);
        scopeRow.put("costMicros", 6000L);
        scopeRow.put("unpricedCalls", 2L);
        when(mapper.summaryByScopeSince(any())).thenReturn(List.of(scopeRow));

        Map<String, Object> summary = service.summary(7);

        assertThat(summary).containsEntry("days", 7)
                .containsEntry("totalTokens", 1900L)
                .containsEntry("totalCostMicros", 6000L)
                .containsEntry("cachedPromptTokens", 400L)
                .containsEntry("unpricedCalls", 2L)
                .containsEntry("currency", "CNY");
        assertThat((double) summary.get("totalCostAmount")).isEqualTo(0.006);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> models = (List<Map<String, Object>>) summary.get("models");
        assertThat(models).hasSize(2);
        // 命中率是成本口径的解释项：400/1000
        assertThat(models.get(0)).containsEntry("cachedPromptTokens", 400L)
                .containsEntry("cacheHitRate", 0.4);
        // 有单价的那个标 priced=true，没配的标 false —— 看板用户不用去翻配置文件
        assertThat(models.get(0)).containsEntry("priced", true);
        assertThat(models.get(1)).containsEntry("priced", false);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> scopes = (List<Map<String, Object>>) summary.get("scopes");
        assertThat(scopes).hasSize(1);
        assertThat(scopes.get(0)).containsEntry("scope", SCOPE);
    }

    // ------------------------------------------------------------ 缓存价与峰谷价

    @Test
    @DisplayName("命中缓存的输入按缓存价算，其余输入按未命中价：600×0.5 + 400×2 + 500×8")
    void cacheHitInputUsesCheaperPrice() {
        service.record(SCOPE, "deepseek", "deepseek-v4-flash", null,
                TokenUsage.of(1000, 500, 1500, 600));

        assertThat(captureInsert().getCostMicros()).isEqualTo(5100L);
    }

    @Test
    @DisplayName("命中缓存的 token 数一并落库，成本事后可复核")
    void cachedTokensArePersisted() {
        service.record(SCOPE, "deepseek", "deepseek-v4-flash", null,
                TokenUsage.of(1000, 500, 1500, 600));

        ModelUsageRecord saved = captureInsert();
        assertThat(saved.getCachedPromptTokens()).isEqualTo(600);
        // 600×0.5 + 400×2 + 500×8 = 300 + 800 + 4000
        assertThat(saved.getCostMicros()).isEqualTo(5100L);
    }

    @Test
    @DisplayName("上游报的缓存命中数超过输入总数时按输入总数封顶，成本不会变负")
    void cachedTokensAreCapped() {
        service.record(SCOPE, "deepseek", "deepseek-v4-flash", null,
                TokenUsage.of(100, 0, 100, 999));

        // 100 全部按缓存价：100 × 0.5 = 50
        assertThat(captureInsert().getCostMicros()).isEqualTo(50L);
    }

    @Test
    @DisplayName("高峰时段整体乘倍数：同一笔用量在 UTC 工作日 02:30 是非高峰的 2 倍")
    void peakMultiplierAppliesInsideWindow() {
        properties.getModelUsage().setPeakMultiplier(2.0);
        properties.getModelUsage().setPeakWindowsUtc(List.of("01:00-04:00", "06:00-10:00"));
        HealthOsProperties.ModelUsage.Price price =
                properties.getModelUsage().getPrices().get("deepseek-v4-flash");
        TokenUsage usage = TokenUsage.of(1000, 500, 1500, 0);

        // 2026-11-05 是周四
        long peak = service.costMicros(price, usage,
                java.time.OffsetDateTime.parse("2026-11-05T02:30:00Z"));
        long offPeak = service.costMicros(price, usage,
                java.time.OffsetDateTime.parse("2026-11-05T12:00:00Z"));
        // 2026-11-07 是周六，同一时刻不算高峰
        long weekend = service.costMicros(price, usage,
                java.time.OffsetDateTime.parse("2026-11-07T02:30:00Z"));

        assertThat(offPeak).isEqualTo(6000L);       // 1000×2 + 500×8
        assertThat(peak).isEqualTo(12000L);         // ×2
        assertThat(weekend).isEqualTo(6000L);       // 周末不分峰谷
    }

    @Test
    @DisplayName("peak-multiplier=1.0 或时段为空时不分峰谷")
    void noPeakWhenDisabled() {
        HealthOsProperties.ModelUsage.Price price =
                properties.getModelUsage().getPrices().get("deepseek-v4-flash");
        TokenUsage usage = TokenUsage.of(1000, 0, 1000, 0);
        java.time.OffsetDateTime inside = java.time.OffsetDateTime.parse("2026-11-05T02:30:00Z");

        properties.getModelUsage().setPeakWindowsUtc(List.of("01:00-04:00"));
        assertThat(service.costMicros(price, usage, inside)).isEqualTo(2000L); // 倍数为 1.0

        properties.getModelUsage().setPeakMultiplier(2.0);
        properties.getModelUsage().setPeakWindowsUtc(List.of());
        assertThat(service.costMicros(price, usage, inside)).isEqualTo(2000L); // 无窗口
    }

    // ------------------------------------------------------------ 夹具

    /** 汇总行的形状与 mapper 返回一致；用 LinkedHashMap 避免 Map.of 的泛型推断打架。 */
    private static Map<String, Object> sums(long tokens, long costMicros) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("tokens", tokens);
        row.put("costMicros", costMicros);
        return row;
    }

    private ModelUsageRecord captureInsert() {
        ArgumentCaptor<ModelUsageRecord> captor = ArgumentCaptor.forClass(ModelUsageRecord.class);
        verify(mapper).insert(captor.capture());
        return captor.getValue();
    }
}
