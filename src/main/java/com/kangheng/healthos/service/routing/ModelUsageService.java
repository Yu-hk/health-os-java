package com.kangheng.healthos.service.routing;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.kangheng.healthos.common.Ids;
import com.kangheng.healthos.config.HealthOsProperties;
import com.kangheng.healthos.mapper.usage.ModelUsageMapper;
import com.kangheng.healthos.model.usage.ModelUsageRecord;
import com.kangheng.healthos.spi.routing.ModelQuotaExceededException;
import com.kangheng.healthos.spi.routing.ModelUsageMeter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 用量记账与配额闸门（模型成本口径的唯一出口）。
 *
 * <p>四条语义：
 * <ol>
 *   <li><b>记账不阻塞业务</b>：写库失败只留日志。钱已经花了，不能因为统计表写不进去
 *       就把一次成功的答案丢掉；</li>
 *   <li><b>没单价就不算钱</b>：{@code cost_micros} 留 NULL，汇总时单列
 *       {@code unpricedCalls}。编一个价格比留空更危险——它会让总额看起来是准的；</li>
 *   <li><b>缓存命中的输入按便宜的价算</b>：上游对命中缓存的输入报价比未命中低一到两个
 *       数量级，混用一个价会把成本高估几十倍；</li>
 *   <li><b>闸门只读汇总，不读明细</b>：每次调用前都查一次当日累计，走 SQL 聚合而不是
 *       把当天所有行拉回内存。</li>
 * </ol>
 *
 * <p>"当日"按业务时区（Asia/Shanghai）切，与 Agent Lab 的当日指标同一个口径——
 * 两处用不同时区会让"今天调了多少"出现两个答案。峰谷价则按<b>UTC</b>判定：
 * 厂商的峰谷表就是 UTC 写的，换算到本地时区只会引入另一种错法。
 */
@Service
public class ModelUsageService implements ModelUsageMeter {

    private static final Logger log = LoggerFactory.getLogger(ModelUsageService.class);
    static final String TIMEZONE = "Asia/Shanghai";

    private final ModelUsageMapper mapper;
    private final HealthOsProperties.ModelUsage config;

    public ModelUsageService(ModelUsageMapper mapper, HealthOsProperties properties) {
        this.mapper = mapper;
        this.config = properties.getModelUsage();
    }

    // ------------------------------------------------------------ 闸门

    @Override
    public void assertWithinQuota(String scope, String model) {
        if (!config.isEnabled()) {
            return;
        }
        boolean byTokens = config.getDailyTokenQuota() > 0;
        boolean byCost = config.getDailyCostQuotaMicros() > 0;
        if (!byTokens && !byCost) {
            return;
        }
        Map<String, Object> sum = mapper.sumScopeSince(normalizeScope(scope), startOfToday());
        long tokens = longValue(sum, "tokens");
        long cost = longValue(sum, "costMicros");
        if (byTokens && tokens >= config.getDailyTokenQuota()) {
            throw new ModelQuotaExceededException(ModelQuotaExceededException.QUOTA_TOKENS,
                    "主线 " + normalizeScope(scope) + " 当日 token 已达上限 " + config.getDailyTokenQuota()
                            + "（已用 " + tokens + "），本次调用被拒绝",
                    tokens, config.getDailyTokenQuota());
        }
        if (byCost && cost >= config.getDailyCostQuotaMicros()) {
            throw new ModelQuotaExceededException(ModelQuotaExceededException.QUOTA_COST,
                    "主线 " + normalizeScope(scope) + " 当日成本已达上限 "
                            + describe(config.getDailyCostQuotaMicros()) + "（已用 "
                            + describe(cost) + "），本次调用被拒绝",
                    cost, config.getDailyCostQuotaMicros());
        }
    }

    // ------------------------------------------------------------ 记账

    @Override
    public void record(String scope, String providerId, String model, String traceId,
                       TokenUsage usage) {
        if (!config.isEnabled() || usage == null || usage.empty()) {
            // 上游没回报 usage（部分网关会省略）时不写零行：零行的唯一作用是
            // 让调用次数看起来比实际多，对成本没有任何信息量。
            return;
        }
        try {
            OffsetDateTime now = OffsetDateTime.now();
            ModelUsageRecord record = new ModelUsageRecord();
            record.setId(Ids.newId("muse"));
            record.setScopeKey(normalizeScope(scope));
            record.setProviderId(providerId);
            record.setModel(model == null ? "unknown" : model);
            record.setPromptTokens(usage.promptTokens());
            record.setCompletionTokens(usage.completionTokens());
            record.setTotalTokens(usage.totalTokens());
            record.setCachedPromptTokens(usage.cachedPromptTokens());
            record.setCostMicros(costMicros(priceOf(record.getModel()), usage, now));
            record.setCurrency(config.getCurrency());
            record.setTraceId(traceId);
            record.setCreatedAt(now);
            mapper.insert(record);
        } catch (RuntimeException ex) {
            // 记账失败不影响业务：这次调用已经成功、钱已经花了。
            log.warn("模型用量记账失败 scope={} model={}（不影响本次调用）", scope, model);
        }
    }

    /**
     * 折算一次调用的微元金额。
     *
     * <pre>
     * cost = 命中缓存输入 × 缓存价
     *      + (输入 − 命中缓存) × 未命中价
     *      + 输出 × 输出价
     * 高峰期整体 × peakMultiplier
     * </pre>
     *
     * <p>单价是「货币 / 百万 token」，落库是微元，两者正好抵消，故无需乘除 1e6。
     *
     * <p>没配单价时返回 {@code null} 而不是 0——"不知道花了多少"和"一分没花"是两件事，
     * 用 0 兜底会让看板上的总额看起来是准的。
     */
    Long costMicros(HealthOsProperties.ModelUsage.Price price, TokenUsage usage,
                    OffsetDateTime at) {
        if (price == null || usage == null) {
            return null;
        }
        double multiplier = isPeak(at) ? config.getPeakMultiplier() : 1.0;
        return computeMicros(price, usage, multiplier);
    }

    static long computeMicros(HealthOsProperties.ModelUsage.Price price, TokenUsage usage,
                              double multiplier) {
        long hit = Math.min(usage.cachedPromptTokens(), usage.promptTokens());
        long miss = usage.promptTokens() - hit;
        // 缓存价没配时退回未命中价：宁可按贵一点估，也不要凭空少算
        double hitPrice = price.getCacheHitInputPerMillion() > 0
                ? price.getCacheHitInputPerMillion() : price.getCacheMissInputPerMillion();
        double amount = (hit * hitPrice
                + miss * price.getCacheMissInputPerMillion()
                + usage.completionTokens() * price.getOutputPerMillion()) * Math.max(multiplier, 0.0);
        return Math.max(0L, Math.round(amount));
    }

    /**
     * 是否处于高峰时段（UTC 工作日）。
     *
     * <p>只建模"几点到几点"，不建模中国法定节假日——那天会按峰值估价，
     * 属于保守高估。引入一张需要每年维护的假期表，成本远大于这点误差。
     */
    boolean isPeak(OffsetDateTime at) {
        List<String> windows = config.getPeakWindowsUtc();
        if (windows == null || windows.isEmpty() || config.getPeakMultiplier() == 1.0) {
            return false;
        }
        OffsetDateTime utc = at.withOffsetSameInstant(ZoneOffset.UTC);
        DayOfWeek day = utc.getDayOfWeek();
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) {
            return false;
        }
        LocalTime time = utc.toLocalTime();
        for (String window : windows) {
            LocalTime[] range = parseWindow(window);
            if (range != null && !time.isBefore(range[0]) && time.isBefore(range[1])) {
                return true;
            }
        }
        return false;
    }

    private static LocalTime[] parseWindow(String window) {
        if (window == null) {
            return null;
        }
        String[] parts = window.split("-");
        if (parts.length != 2) {
            return null;
        }
        try {
            return new LocalTime[]{LocalTime.parse(parts[0].trim()), LocalTime.parse(parts[1].trim())};
        } catch (RuntimeException ex) {
            // 配置写错不该让记账崩掉：这条窗口忽略，其余照算
            return null;
        }
    }

    /**
     * 单价查找：先精确匹配，再按小写匹配。
     *
     * <p>第二段是给配置写的：YAML 里的键习惯用 {@code deepseek-flash}，
     * 而上游回报的模型名大小写不一定一致。匹配不到就返回 null（不猜）。
     */
    HealthOsProperties.ModelUsage.Price priceOf(String model) {
        Map<String, HealthOsProperties.ModelUsage.Price> prices = config.getPrices();
        if (prices == null || prices.isEmpty() || model == null) {
            return null;
        }
        HealthOsProperties.ModelUsage.Price exact = prices.get(model);
        if (exact != null) {
            return exact;
        }
        for (Map.Entry<String, HealthOsProperties.ModelUsage.Price> entry : prices.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(model)) {
                return entry.getValue();
            }
        }
        return null;
    }

    // ------------------------------------------------------------ 查询

    /** 成本看板：最近 {@code days} 天，按模型与主线两个维度。 */
    public Map<String, Object> summary(int days) {
        int window = days <= 0 ? 7 : Math.min(days, 90);
        OffsetDateTime since = startOfToday().minusDays(window - 1L);
        List<Map<String, Object>> byModel = mapper.summaryByModelSince(since);
        List<Map<String, Object>> byScope = mapper.summaryByScopeSince(since);

        long totalTokens = 0L;
        long totalCost = 0L;
        long totalCached = 0L;
        long unpricedCalls = 0L;
        List<Map<String, Object>> models = new ArrayList<>();
        for (Map<String, Object> row : byModel == null ? List.<Map<String, Object>>of() : byModel) {
            totalTokens += longValue(row, "totalTokens");
            totalCost += longValue(row, "costMicros");
            totalCached += longValue(row, "cachedPromptTokens");
            unpricedCalls += longValue(row, "unpricedCalls");
            models.add(modelView(row));
        }
        List<Map<String, Object>> scopes = new ArrayList<>();
        for (Map<String, Object> row : byScope == null ? List.<Map<String, Object>>of() : byScope) {
            scopes.add(scopeView(row));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("days", window);
        result.put("since", String.valueOf(since));
        result.put("timezone", TIMEZONE);
        result.put("currency", config.getCurrency());
        result.put("enabled", config.isEnabled());
        result.put("totalTokens", totalTokens);
        result.put("cachedPromptTokens", totalCached);
        result.put("totalCostMicros", totalCost);
        result.put("totalCostAmount", amount(totalCost));
        // 有多少次调用没算进成本：让"总额准不准"这件事一眼可见，而不是藏在脚注里
        result.put("unpricedCalls", unpricedCalls);
        result.put("dailyTokenQuota", config.getDailyTokenQuota());
        result.put("dailyCostQuotaAmount", amount(config.getDailyCostQuotaMicros()));
        result.put("peakMultiplier", config.getPeakMultiplier());
        result.put("peakWindowsUtc", config.getPeakWindowsUtc());
        // 此刻按峰价还是谷价：避免看板用户把"今天贵了"误判成"用量涨了"
        result.put("peakNow", isPeak(OffsetDateTime.now()));
        result.put("models", models);
        result.put("scopes", scopes);
        return result;
    }

    private Map<String, Object> modelView(Map<String, Object> row) {
        Map<String, Object> view = new LinkedHashMap<>();
        String model = String.valueOf(row.get("model"));
        long prompt = longValue(row, "promptTokens");
        long cached = longValue(row, "cachedPromptTokens");
        view.put("model", model);
        view.put("calls", longValue(row, "calls"));
        view.put("promptTokens", prompt);
        view.put("completionTokens", longValue(row, "completionTokens"));
        view.put("cachedPromptTokens", cached);
        // 命中率是成本口径的解释项：命中率高说明系统提示词前缀被复用，钱花在输出上而不是重发上下文
        view.put("cacheHitRate", prompt <= 0 ? 0.0 : Math.round(cached * 10000.0 / prompt) / 10000.0);
        view.put("totalTokens", longValue(row, "totalTokens"));
        view.put("costMicros", longValue(row, "costMicros"));
        view.put("costAmount", amount(longValue(row, "costMicros")));
        view.put("unpricedCalls", longValue(row, "unpricedCalls"));
        // 单价有没有配，直接在这儿说清楚，省得看板用户去翻配置文件
        view.put("priced", priceOf(model) != null);
        return view;
    }

    private Map<String, Object> scopeView(Map<String, Object> row) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("scope", row.get("scopeKey"));
        view.put("calls", longValue(row, "calls"));
        view.put("totalTokens", longValue(row, "totalTokens"));
        view.put("costMicros", longValue(row, "costMicros"));
        view.put("costAmount", amount(longValue(row, "costMicros")));
        view.put("unpricedCalls", longValue(row, "unpricedCalls"));
        return view;
    }

    // ------------------------------------------------------------ 小工具

    /** 东八区当天 00:00（带偏移），与 Agent Lab 的"当日"同一口径。 */
    static OffsetDateTime startOfToday() {
        return LocalDate.now(ZoneId.of(TIMEZONE)).atStartOfDay(ZoneId.of(TIMEZONE)).toOffsetDateTime();
    }

    /** 主线为空时归到 {@code default}：空字符串会让 GROUP BY 出现一个"看不见的"分组。 */
    static String normalizeScope(String scope) {
        return scope == null || scope.isBlank() ? "default" : scope.trim();
    }

    /** 微元 → 货币金额（保留 6 位小数，只是给人看的展示值）。 */
    static double amount(long micros) {
        return Math.round(micros) / 1_000_000.0;
    }

    /** 带货币的展示串，用在闸门报错里。 */
    private String describe(long micros) {
        return String.format(Locale.ROOT, "%.6f %s", amount(micros),
                config.getCurrency() == null ? "" : config.getCurrency()).trim();
    }

    private static long longValue(Map<String, Object> row, String key) {
        if (row == null) {
            return 0L;
        }
        Object value = row.get(key);
        return value instanceof Number number ? number.longValue() : 0L;
    }
}
