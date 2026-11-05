package com.kangheng.healthos.provider.ai;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;

import com.kangheng.healthos.common.Json;
import com.kangheng.healthos.config.HealthOsProperties;
import com.kangheng.healthos.spi.ai.LlmException;
import com.kangheng.healthos.spi.ai.LlmProvider;
import com.kangheng.healthos.spi.routing.ModelRouteResolver;
import com.kangheng.healthos.spi.routing.ModelUsageMeter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * OpenAI 兼容接口实现（DeepSeek / Qwen / 各类网关都走这一条）。
 *
 * <p>保持原项目的三条硬约束：结构化解码必须校验后再入库；空输出不等于成功；
 * 上游 413/超长回复按"体量敏感"抛出以便二分。
 *
 * <p>调用参数<b>不再固定读全局 {@code healthos.ai.*}</b>：带 {@link RouteContext}
 * 的入口按业务主线去解析配置中心里那一版（含密钥、模型名、超时），
 * 配置中心未启用时才回退到全局配置。这样七条主线可以各用各的模型而互不拖累。
 */
@Component
@ConditionalOnProperty(name = "healthos.ai.provider", havingValue = "openai")
public class OpenAiCompatibleLlmProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleLlmProvider.class);

    private static final String EXTRACTION_SYSTEM_PROMPT = """
            你是中医典籍结构化抽取器。只输出 JSON，不要任何解释、不要 Markdown 代码块。
            输出结构：{"entities":[{"name":"","type":"","aliases":[]}],
                      "relations":[{"source":"","target":"","type":"","confidence":0.0,"evidence":""}]}
            type 取值限定为：FORMULA（方剂）、HERB（药材）、SYMPTOM（病证）、EFFECT（功效）、MERIDIAN（归经）、CONCEPT（其他）。
            evidence 必须是原文中的连续片段，不得改写。抽不到就返回空数组。
            """;

    private static final String ANSWER_SYSTEM_PROMPT = """
            你是中医典籍问答助手。只依据用户提供的【原文片段】作答，
            不得引入片段之外的知识；每条结论后用 [片段序号] 标注出处；
            片段无法支撑问题时，直接回答"原文未记载该内容"，不要猜测。
            """;

    private final HealthOsProperties properties;
    private final ModelRouteResolver routeResolver;
    private final ModelUsageMeter usageMeter;
    /** baseUrl + 超时 → 客户端。配置中心允许不同主线指向不同供应商，不能只建一个。 */
    private final Map<String, RestClient> clients = new ConcurrentHashMap<>();

    public OpenAiCompatibleLlmProvider(HealthOsProperties properties, ModelRouteResolver routeResolver,
                                       ModelUsageMeter usageMeter) {
        this.properties = properties;
        this.routeResolver = routeResolver;
        this.usageMeter = usageMeter;
    }

    @Override
    public String name() {
        return "openai-compatible:" + properties.getAi().getModel();
    }

    // ------------------------------------------------------------ 带主线入口

    @Override
    public ExtractionResult extract(RouteContext context, String documentTitle, String segmentText) {
        String content = call(routeFor(context), EXTRACTION_SYSTEM_PROMPT,
                "典籍标题：" + documentTitle + "\n请抽取以下片段：\n" + segmentText, true);
        return parseExtraction(content);
    }

    @Override
    public String answer(RouteContext context, String question, String contextBlock) {
        String content = call(routeFor(context), ANSWER_SYSTEM_PROMPT,
                "【原文片段】\n" + contextBlock + "\n\n【问题】\n" + question, false);
        if (!StringUtils.hasText(content)) {
            throw LlmException.invalidSchema("模型返回空答案", null);
        }
        return content.trim();
    }

    @Override
    public String chat(RouteContext context, String systemPrompt, String userPrompt) {
        String content = call(routeFor(context), systemPrompt, userPrompt, false);
        if (!StringUtils.hasText(content)) {
            throw LlmException.invalidSchema("模型返回空内容", null);
        }
        return content.trim();
    }

    /** 解析配置。失败必须抛，不能悄悄用全局密钥顶上——那把密钥可能不是这个供应商的。 */
    private ModelRouteResolver.ResolvedRoute routeFor(RouteContext context) {
        try {
            return routeResolver.resolveForBusiness(context.bizType(), context.bizId(),
                    context.pipelineKey());
        } catch (ModelRouteResolver.ModelRouteResolutionException ex) {
            throw LlmException.nonRetryable(
                    "模型配置不可用[" + ex.code() + "]：" + ex.getMessage(), null);
        }
    }

    // ------------------------------------------------------------ 兼容入口（无主线）

    @Override
    public ExtractionResult extract(String documentTitle, String segmentText) {
        String content = call(legacy(), EXTRACTION_SYSTEM_PROMPT,
                "典籍标题：" + documentTitle + "\n请抽取以下片段：\n" + segmentText, true);
        return parseExtraction(content);
    }

    @Override
    public String answer(String question, String contextBlock) {
        String content = call(legacy(), ANSWER_SYSTEM_PROMPT,
                "【原文片段】\n" + contextBlock + "\n\n【问题】\n" + question, false);
        if (!StringUtils.hasText(content)) {
            throw LlmException.invalidSchema("模型返回空答案", null);
        }
        return content.trim();
    }

    @Override
    public String chat(String systemPrompt, String userPrompt) {
        String content = call(legacy(), systemPrompt, userPrompt, false);
        if (!StringUtils.hasText(content)) {
            throw LlmException.invalidSchema("模型返回空内容", null);
        }
        return content.trim();
    }

    /** 迁移期回退：没带主线的调用仍走全局配置，行为与改造前一致。 */
    private ModelRouteResolver.ResolvedRoute legacy() {
        HealthOsProperties.Ai ai = properties.getAi();
        return new ModelRouteResolver.ResolvedRoute("LEGACY", "legacy",
                properties.getModelRouting().getBaselineProviderId(), ai.getModel(), "chat",
                ai.getBaseUrl(), "direct", ai.getApiKey(), 0, 8000, 10,
                (int) Math.max(1, ai.getTimeout().toSeconds()), 900, 1);
    }

    // ------------------------------------------------------------ 实际请求

    /**
     * 真实调用一次。
     *
     * <p>配额闸门放在<b>请求之前</b>：成本已经超了还发出去，钱就真花了。
     * 记账放在<b>响应之后</b>：以上游回报的 {@code usage} 为准，不按字符数估算——
     * 估出来的数字看着像真的，反而更难发现计费口径变了。
     *
     * <p>配额超限直接抛 {@link com.kangheng.healthos.spi.routing.ModelQuotaExceededException}，
     * <b>不</b>折成 {@code LlmException}：它带着 used/limit，运维要的是"超了多少"，
     * 而不是又一条"上游不可用"。
     */
    private String call(ModelRouteResolver.ResolvedRoute route, String systemPrompt,
                        String userPrompt, boolean jsonMode) {
        if (!StringUtils.hasText(route.secret())) {
            throw LlmException.nonRetryable("模型密钥未配置，无法调用真实模型", null);
        }
        usageMeter.assertWithinQuota(route.pipelineKey(), route.model());
        Map<String, Object> body = Map.of(
                "model", route.model(),
                "temperature", route.temperature(),
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)));
        try {
            JsonNode response = clientFor(route).post()
                    .uri("/v1/chat/completions")
                    .header("Authorization", "Bearer " + route.secret())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
            if (response == null) {
                throw LlmException.invalidSchema("模型响应为空", null);
            }
            String content = response.path("choices").path(0).path("message").path("content").asText("");
            if (!StringUtils.hasText(content)) {
                // 完整响应里带明确错误时不能当作成功答案，原项目踩过 RAGFlow 返回 **ERROR** 的坑
                String upstreamError = response.path("error").path("message").asText("");
                throw LlmException.invalidSchema(
                        "模型未返回内容" + (upstreamError.isEmpty() ? "" : "，上游错误：" + upstreamError), null);
            }
            recordUsage(route, response);
            return jsonMode ? stripCodeFence(content) : content;
        } catch (RestClientResponseException ex) {
            int status = ex.getStatusCode().value();
            if (status == 413) {
                throw LlmException.tooLarge("请求体超过上游限制", ex);
            }
            if (status == 429 || status >= 500) {
                throw LlmException.timeout("上游限流或不可用，status=" + status, ex);
            }
            throw LlmException.nonRetryable("上游拒绝请求，status=" + status + "，body=" + ex.getResponseBodyAsString(), ex);
        } catch (ResourceAccessException ex) {
            throw LlmException.timeout("调用模型超时或网络不可达", ex);
        }
    }

    /**
     * 按上游回报的 {@code usage} 记账。
     *
     * <p>没有 {@code usage} 节点就什么都不记（{@code record} 会忽略空用量）——
     * 写一条全零的记录只会让"调用次数"虚高。
     *
     * <p>不带 traceId：轨迹上下文在 service 层，provider 依赖它会破坏分层方向，
     * 而"从一笔花费回到那次调用"还可以用 {@code created_at} + 主线定位。
     */
    private void recordUsage(ModelRouteResolver.ResolvedRoute route, JsonNode response) {
        JsonNode usage = response.path("usage");
        if (usage.isMissingNode() || usage.isNull()) {
            return;
        }
        usageMeter.record(route.pipelineKey(), route.providerId(), route.model(), null,
                ModelUsageMeter.TokenUsage.of(
                        intOrNull(usage, "prompt_tokens"),
                        intOrNull(usage, "completion_tokens"),
                        intOrNull(usage, "total_tokens"),
                        cacheHitTokens(usage)));
    }

    /**
     * 输入里命中缓存的 token 数。
     *
     * <p>DeepSeek 给的是顶层 {@code prompt_cache_hit_tokens}，OpenAI 系放在
     * {@code prompt_tokens_details.cached_tokens}。两处都认，取到就用——
     * 少了这个数，缓存命中的输入会按未命中的价算，Flash 上差 50 倍。
     */
    private static Integer cacheHitTokens(JsonNode usage) {
        Integer hit = intOrNull(usage, "prompt_cache_hit_tokens");
        if (hit == null) {
            hit = intOrNull(usage.path("prompt_tokens_details"), "cached_tokens");
        }
        return hit;
    }

    private static Integer intOrNull(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || !node.hasNonNull(field)) {
            return null;
        }
        JsonNode value = node.get(field);
        return value.isNumber() ? value.asInt() : null;
    }

    private RestClient clientFor(ModelRouteResolver.ResolvedRoute route) {
        int total = Math.max(1, route.totalTimeoutSeconds());
        String key = route.baseUrl() + "|" + total;
        return clients.computeIfAbsent(key, ignored -> {
            HttpClient httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(Math.max(1, route.connectTimeoutSeconds())))
                    .build();
            JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
            factory.setReadTimeout(Duration.ofSeconds(total));
            log.info("模型客户端装配 baseUrl={} model={} origin={} timeout={}s",
                    route.baseUrl(), route.model(), route.configOrigin(), total);
            return RestClient.builder()
                    .baseUrl(route.baseUrl())
                    .requestFactory(factory)
                    .build();
        });
    }

    static String stripCodeFence(String content) {
        String trimmed = content.trim();
        if (trimmed.startsWith("```")) {
            int firstBreak = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstBreak > 0 && lastFence > firstBreak) {
                return trimmed.substring(firstBreak + 1, lastFence).trim();
            }
        }
        return trimmed;
    }

    ExtractionResult parseExtraction(String content) {
        JsonNode root;
        try {
            root = Json.mapper().readTree(content);
        } catch (Exception ex) {
            throw LlmException.invalidSchema("模型输出不是合法 JSON", ex);
        }
        JsonNode entityNodes = root.path("entities");
        JsonNode relationNodes = root.path("relations");
        if (!entityNodes.isArray() || !relationNodes.isArray()) {
            throw LlmException.invalidSchema("模型输出缺少 entities/relations 数组", null);
        }
        List<LlmProvider.ExtractedEntity> entities = new ArrayList<>();
        for (JsonNode node : entityNodes) {
            String name = node.path("name").asText("").trim();
            String type = node.path("type").asText("CONCEPT").trim();
            if (name.isEmpty()) {
                continue;
            }
            List<String> aliases = new ArrayList<>();
            node.path("aliases").forEach(alias -> aliases.add(alias.asText()));
            entities.add(new LlmProvider.ExtractedEntity(name, type.isEmpty() ? "CONCEPT" : type, aliases));
        }
        List<LlmProvider.ExtractedRelation> relations = new ArrayList<>();
        for (JsonNode node : relationNodes) {
            String source = node.path("source").asText("").trim();
            String target = node.path("target").asText("").trim();
            if (source.isEmpty() || target.isEmpty()) {
                continue;
            }
            relations.add(new LlmProvider.ExtractedRelation(
                    source,
                    target,
                    node.path("type").asText("RELATED").trim(),
                    node.path("confidence").asDouble(0.6),
                    node.path("evidence").asText("").trim()));
        }
        log.debug("模型抽取完成 entities={} relations={}", entities.size(), relations.size());
        return new LlmProvider.ExtractionResult(entities, relations);
    }
}
