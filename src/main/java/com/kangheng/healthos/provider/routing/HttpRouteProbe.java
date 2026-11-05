package com.kangheng.healthos.provider.routing;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.kangheng.healthos.common.Json;
import com.kangheng.healthos.spi.routing.RouteProbe;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 真实 HTTP 能力探测。
 *
 * <p>发四种合成请求，逐项判定：
 * <ul>
 *   <li>{@code connectivity} —— 能不能拿到内容；</li>
 *   <li>{@code structured} —— 能不能按 json_schema 吐出 {@code {"ok":true}} 且正常收尾；</li>
 *   <li>{@code streaming} —— 流式能不能收到终止帧、拼出来的内容是否合法；</li>
 *   <li>{@code tool_calling} —— 能不能发起一次名字与参数都合法的函数调用。</li>
 * </ul>
 *
 * <p><b>只发合成输入</b>：提示词是固定的 "Return {"ok":true}"，不碰任何患者数据，
 * 也从不执行工具——工具调用只是被"发起"，不落地。
 *
 * <p>整体状态只看<b>该主线要求的能力</b>是否全过。不要求流式的专线不必为流式付费，
 * 反过来也一样：要求流式的专线，流式不过就是不过。
 *
 * <p>对应原工程 {@code model_route_probe.py}。
 */
@Component
public class HttpRouteProbe implements RouteProbe {

    private static final Logger log = LoggerFactory.getLogger(HttpRouteProbe.class);

    /** 合成探测的固定输入。 */
    private static final String PROBE_INSTRUCTION = "Synthetic connection test. Return {\"ok\":true}.";
    /** 合成调用的工具名。 */
    private static final String PROBE_TOOL = "healthos_probe";
    /** 响应体上限，防止异常供应商把内存打满。 */
    private static final int MAX_RESPONSE_BYTES = 65536;
    /** 单项探测的硬超时。 */
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(20);
    private static final List<String> KINDS =
            List.of("connectivity", "structured", "streaming", "tool_calling");

    @Override
    public ProbeOutcome probe(RouteTarget target) {
        if (!"chat".equals(target.protocol())
                && !"chat_tool".equals(target.protocol())
                && !"responses".equals(target.protocol())) {
            Map<String, CapabilityOutcome> skipped = new LinkedHashMap<>();
            for (String kind : KINDS) {
                skipped.put(kind, new CapabilityOutcome("failed", 0, "PROBE_PROTOCOL_NOT_ADAPTED", null));
            }
            return new ProbeOutcome("failed", 0, "该协议暂不支持能力探测", skipped);
        }
        if (target.baseUrl() == null || target.baseUrl().isBlank()) {
            Map<String, CapabilityOutcome> skipped = new LinkedHashMap<>();
            for (String kind : KINDS) {
                skipped.put(kind, new CapabilityOutcome("failed", 0, "MODEL_ADDRESS_MISSING", null));
            }
            return new ProbeOutcome("failed", 0, "未配置模型地址，无法探测", skipped);
        }

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(1, Math.min(60, target.connectTimeoutSeconds()))))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        Map<String, CapabilityOutcome> results = new LinkedHashMap<>();
        long total = 0;
        for (String kind : KINDS) {
            CapabilityOutcome outcome = runOne(client, target, kind);
            results.put(kind, outcome);
            total += outcome.latencyMs();
        }
        boolean passed = target.requiredCapabilities().stream()
                .allMatch(kind -> {
                    CapabilityOutcome outcome = results.get(kind);
                    return outcome != null && outcome.passed();
                });
        return new ProbeOutcome(passed ? "passed" : "failed", total,
                "合成输入验证；不代表业务端到端验收", results);
    }

    /** 跑一项探测。任何异常都归类成失败，不往外抛。 */
    private CapabilityOutcome runOne(HttpClient client, RouteTarget target, String kind) {
        long started = System.nanoTime();
        try {
            String endpoint = "responses".equals(target.protocol()) ? "/responses" : "/chat/completions";
            String url = target.baseUrl().replaceAll("/+$", "") + endpoint;
            Map<String, Object> body = "responses".equals(target.protocol())
                    ? responsesBody(target, kind) : chatBody(target, kind);
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(PROBE_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + (target.secret() == null ? "" : target.secret()));
            request.POST(HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8));

            HttpResponse<InputStream> response =
                    client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                return failed(started, "MODEL_HTTP_" + response.statusCode(), null);
            }
            String raw = readBounded(response.body());
            String resolvedModel = "responses".equals(target.protocol())
                    ? checkResponses(raw, kind) : checkChat(raw, kind, target.protocol());
            return new CapabilityOutcome("passed", elapsedMs(started), null, resolvedModel);
        } catch (java.net.http.HttpTimeoutException ex) {
            return failed(started, "MODEL_PROBE_TIMEOUT", null);
        } catch (InterruptedException ex) {
            // 被中断时恢复中断标记，让上层线程池知道要收摊了。
            Thread.currentThread().interrupt();
            return failed(started, "MODEL_PROBE_INTERRUPTED", null);
        } catch (IOException ex) {
            return failed(started, "MODEL_CONNECTION_FAILED", null);
        } catch (IllegalStateException ex) {
            return failed(started, "MODEL_CAPABILITY_RESPONSE_INVALID", null);
        } catch (RuntimeException ex) {
            log.warn("模型能力探测异常 kind={} provider={}", kind, target.providerId(), ex);
            return failed(started, "MODEL_CAPABILITY_RESPONSE_INVALID", null);
        }
    }

    // ------------------------------------------------------------ 请求体

    /** Chat Completions 协议请求体。 */
    private Map<String, Object> chatBody(RouteTarget target, String kind) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", target.model());
        body.put("messages", List.of(Map.of("role", "user", "content", PROBE_INSTRUCTION)));
        body.put("max_tokens", 128);
        body.put("temperature", target.temperature());
        if ("qwen".equals(target.providerId())) {
            body.put("enable_thinking", false);
        }
        if ("structured".equals(kind) || "streaming".equals(kind)) {
            body.put("response_format", Map.of("type", "json_schema", "json_schema",
                    Map.of("name", PROBE_TOOL, "strict", true, "schema", schema())));
        }
        if ("streaming".equals(kind)) {
            body.put("stream", true);
            body.put("stream_options", Map.of("include_usage", true));
        }
        if ("tool_calling".equals(kind)) {
            body.put("tools", List.of(Map.of("type", "function", "function",
                    Map.of("name", PROBE_TOOL, "description", "Synthetic probe; no tool is executed.",
                            "parameters", schema()))));
            body.put("tool_choice", Map.of("type", "function", "function", Map.of("name", PROBE_TOOL)));
        }
        if ("chat_tool".equals(target.protocol()) && "structured".equals(kind)) {
            // chat_tool 下结构化输出靠工具调用表达，不能再同时要求 json_schema 响应格式。
            body.remove("response_format");
            body.put("tools", List.of(Map.of("type", "function", "function",
                    Map.of("name", PROBE_TOOL, "description", "Synthetic probe; never executed.",
                            "parameters", schema(), "strict", true))));
            body.put("tool_choice", Map.of("type", "function", "function", Map.of("name", PROBE_TOOL)));
        }
        return body;
    }

    /** Responses 协议请求体。 */
    private Map<String, Object> responsesBody(RouteTarget target, String kind) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", target.model());
        body.put("input", PROBE_INSTRUCTION);
        body.put("max_output_tokens", 128);
        if ("structured".equals(kind) || "streaming".equals(kind)) {
            body.put("text", Map.of("format", Map.of("type", "json_schema", "name", PROBE_TOOL,
                    "strict", true, "schema", schema())));
        }
        if ("streaming".equals(kind)) {
            body.put("stream", true);
        }
        if ("tool_calling".equals(kind)) {
            body.put("tools", List.of(Map.of("type", "function", "name", PROBE_TOOL,
                    "description", "Synthetic probe; never executed.",
                    "parameters", schema(), "strict", true)));
            body.put("tool_choice", Map.of("type", "function", "name", PROBE_TOOL));
        }
        return body;
    }

    private Map<String, Object> schema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of("ok", Map.of("type", "boolean")));
        schema.put("required", List.of("ok"));
        schema.put("additionalProperties", false);
        return schema;
    }

    // ------------------------------------------------------------ 响应校验

    /** 校验 Chat Completions 响应。 */
    private String checkChat(String raw, String kind, String protocol) {
        if ("streaming".equals(kind)) {
            List<String> frames = new ArrayList<>();
            for (String line : raw.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("data:")) {
                    frames.add(trimmed.substring(5).trim());
                }
            }
            if (!frames.contains("[DONE]")) {
                throw new IllegalStateException("流式响应缺少终止帧");
            }
            StringBuilder content = new StringBuilder();
            boolean terminal = false;
            for (String frame : frames) {
                if ("[DONE]".equals(frame)) {
                    continue;
                }
                Map<String, Object> chunk = Json.readMap(frame);
                Object choices = chunk.get("choices");
                if (!(choices instanceof List<?> list)) {
                    continue;
                }
                for (Object item : list) {
                    if (!(item instanceof Map<?, ?> choice)) {
                        continue;
                    }
                    if ("stop".equals(choice.get("finish_reason"))) {
                        terminal = true;
                    }
                    Object delta = choice.get("delta");
                    if (delta instanceof Map<?, ?> map && map.get("content") != null) {
                        content.append(map.get("content"));
                    }
                }
            }
            if (!terminal) {
                throw new IllegalStateException("流式响应没有正常收尾");
            }
            requireValidPayload(content.toString());
            return null;
        }
        Map<String, Object> payload = Json.readMap(raw);
        Object choices = payload.get("choices");
        if (!(choices instanceof List<?> list) || list.isEmpty()) {
            throw new IllegalStateException("响应缺少 choices");
        }
        if (!(list.get(0) instanceof Map<?, ?> choice)) {
            throw new IllegalStateException("响应 choices 结构异常");
        }
        Object message = choice.get("message");
        if (!(message instanceof Map<?, ?> messageMap)) {
            throw new IllegalStateException("响应缺少 message");
        }
        if ("connectivity".equals(kind)) {
            Object content = messageMap.get("content");
            if (content == null || String.valueOf(content).isBlank()) {
                throw new IllegalStateException("响应缺少内容");
            }
        }
        if ("structured".equals(kind) && !"chat_tool".equals(protocol)) {
            if (!"stop".equals(choice.get("finish_reason"))) {
                throw new IllegalStateException("结构化响应未正常收尾");
            }
            requireValidPayload(String.valueOf(messageMap.get("content")));
        }
        if ("tool_calling".equals(kind) || ("structured".equals(kind) && "chat_tool".equals(protocol))) {
            requireValidToolCall(messageMap.get("tool_calls"));
        }
        Object model = payload.get("model");
        return model == null ? null : String.valueOf(model);
    }

    /** 校验 Responses 协议响应。 */
    private String checkResponses(String raw, String kind) {
        Map<String, Object> value;
        if ("streaming".equals(kind)) {
            Map<String, Object> completed = null;
            for (String line : raw.split("\n")) {
                String trimmed = line.trim();
                if (!trimmed.startsWith("data:")) {
                    continue;
                }
                String frame = trimmed.substring(5).trim();
                if (frame.isEmpty() || "[DONE]".equals(frame)) {
                    continue;
                }
                Map<String, Object> chunk = Json.readMap(frame);
                if ("response.completed".equals(chunk.get("type"))) {
                    Object response = chunk.get("response");
                    if (response instanceof Map<?, ?> map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> casted = (Map<String, Object>) map;
                        completed = casted;
                    }
                }
            }
            if (completed == null) {
                throw new IllegalStateException("流式响应缺少完成帧");
            }
            value = completed;
        } else {
            value = Json.readMap(raw);
        }
        if (!"completed".equals(value.get("status"))) {
            throw new IllegalStateException("响应未完成");
        }
        if ("tool_calling".equals(kind)) {
            requireValidResponsesToolCall(value.get("output"));
        } else if (!"connectivity".equals(kind)) {
            requireValidPayload(responseText(value));
        } else if (responseText(value).isBlank()) {
            throw new IllegalStateException("响应缺少内容");
        }
        Object model = value.get("model");
        return model == null ? null : String.valueOf(model);
    }

    /** Responses 协议里把正文取出来。 */
    private String responseText(Map<String, Object> value) {
        Object direct = value.get("output_text");
        if (direct != null && !String.valueOf(direct).isBlank()) {
            return String.valueOf(direct);
        }
        StringBuilder buffer = new StringBuilder();
        if (value.get("output") instanceof List<?> output) {
            for (Object item : output) {
                if (!(item instanceof Map<?, ?> entry) || !"message".equals(entry.get("type"))) {
                    continue;
                }
                if (!(entry.get("content") instanceof List<?> content)) {
                    continue;
                }
                for (Object part : content) {
                    if (part instanceof Map<?, ?> piece && "output_text".equals(piece.get("type"))
                            && piece.get("text") != null) {
                        buffer.append(piece.get("text"));
                    }
                }
            }
        }
        return buffer.toString();
    }

    /** Chat 协议的工具调用校验。 */
    private void requireValidToolCall(Object toolCalls) {
        if (!(toolCalls instanceof List<?> list) || list.isEmpty()) {
            throw new IllegalStateException("响应缺少工具调用");
        }
        if (!(list.get(0) instanceof Map<?, ?> call)) {
            throw new IllegalStateException("工具调用结构异常");
        }
        Object function = call.get("function");
        if (!(function instanceof Map<?, ?> functionMap)) {
            throw new IllegalStateException("工具调用缺少 function");
        }
        if (!PROBE_TOOL.equals(functionMap.get("name"))) {
            throw new IllegalStateException("工具名不符合预期");
        }
        requireValidPayload(String.valueOf(functionMap.get("arguments")));
    }

    /** Responses 协议的工具调用校验。 */
    private void requireValidResponsesToolCall(Object output) {
        if (!(output instanceof List<?> list)) {
            throw new IllegalStateException("响应缺少 output");
        }
        for (Object item : list) {
            if (item instanceof Map<?, ?> entry && "function_call".equals(entry.get("type"))) {
                if (!PROBE_TOOL.equals(entry.get("name"))) {
                    throw new IllegalStateException("工具名不符合预期");
                }
                requireValidPayload(String.valueOf(entry.get("arguments")));
                return;
            }
        }
        throw new IllegalStateException("响应缺少工具调用");
    }

    /** 合成输出必须严格是 {"ok":true}。 */
    private void requireValidPayload(String content) {
        Map<String, Object> payload = Json.readMap(content);
        if (payload.size() != 1 || !Boolean.TRUE.equals(payload.get("ok"))) {
            throw new IllegalStateException("结构化响应不符合预期");
        }
    }

    // ------------------------------------------------------------ 工具方法

    /** 读响应体，超过上限就截断并判失败——不让异常供应商把内存吃满。 */
    private String readBounded(InputStream stream) throws IOException {
        StringBuilder buffer = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            char[] chunk = new char[4096];
            int read;
            while ((read = reader.read(chunk)) > 0) {
                buffer.append(chunk, 0, read);
                if (buffer.length() > MAX_RESPONSE_BYTES) {
                    throw new IllegalStateException("响应体过大");
                }
            }
        }
        return buffer.toString();
    }

    private long elapsedMs(long startedNanos) {
        return Math.round((System.nanoTime() - startedNanos) / 1_000_000.0);
    }

    private CapabilityOutcome failed(long startedNanos, String errorCode, String resolvedModel) {
        return new CapabilityOutcome("failed", elapsedMs(startedNanos), errorCode, resolvedModel);
    }
}
