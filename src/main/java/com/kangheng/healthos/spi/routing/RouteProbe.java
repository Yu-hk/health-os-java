package com.kangheng.healthos.spi.routing;

import java.util.List;
import java.util.Map;

/**
 * 模型路由的**能力探测**扩展点。
 *
 * <p>回答的是"这份配置真的能打通吗"。做法是发<b>合成请求</b>：让模型回一个
 * {@code {"ok":true}}，再分别用连通性、结构化输出、流式输出、工具调用四种方式各试一次。
 *
 * <p><b>四种能力分开试，是因为它们会分别坏</b>：连通了不代表能按 schema 吐结构化 JSON；
 * 能吐结构化 JSON 也不代表流式能正常收尾（少了终止帧的流会被前端挂死）；
 * 能聊天更不代表会正确发起函数调用。合成探测互不干扰患者数据，也不执行任何真实工具。
 *
 * <p>为什么不让业务层直接依赖某个 HTTP 实现：探测要真的打外部网络，
 * 而"怎么打"（直连还是走代理、超时怎么算、失败怎么归类）是会变的实现细节。
 * 业务层只认这个接口，换实现不用改业务代码。
 */
public interface RouteProbe {

    /**
     * 执行一次探测。
     *
     * <p>实现<b>不得抛异常</b>——探测失败是一条正常的业务结果（配置没错，供应商不通），
     * 不是一个需要冒泡的意外。返回 {@link ProbeOutcome} 里 status=failed 即可。
     */
    ProbeOutcome probe(RouteTarget target);

    /** 探测目标。只带真正需要的字段。 */
    record RouteTarget(String providerId,
                       String model,
                       String protocol,
                       String baseUrl,
                       double temperature,
                       int connectTimeoutSeconds,
                       int totalTimeoutSeconds,
                       List<String> requiredCapabilities,
                       String secret) {

        public RouteTarget {
            requiredCapabilities = requiredCapabilities == null ? List.of() : List.copyOf(requiredCapabilities);
        }
    }

    /**
     * 单项能力的探测结果。
     *
     * @param status        passed / failed
     * @param latencyMs     该项耗时
     * @param errorCode     失败原因（MODEL_PROBE_TIMEOUT / MODEL_CONNECTION_FAILED /
     *                      MODEL_CAPABILITY_RESPONSE_INVALID / MODEL_HTTP_xxx / PROBE_PROTOCOL_NOT_ADAPTED）
     * @param resolvedModel 供应商实际返回的模型名；与配置里写的不同通常意味着被静默替换了
     */
    record CapabilityOutcome(String status, long latencyMs, String errorCode, String resolvedModel) {

        public boolean passed() {
            return "passed".equals(status);
        }
    }

    /**
     * 探测总结果。
     *
     * @param status       passed / failed——只看<b>该主线要求的能力</b>是否全过，
     *                     不是要求所有能力都过（不要求流式的专线不必为流式付费）
     * @param latencyMs    四项探测耗时之和
     * @param message      面向管理员的说明
     * @param capabilities 逐项结果
     */
    record ProbeOutcome(String status, long latencyMs, String message,
                        Map<String, CapabilityOutcome> capabilities) {

        public ProbeOutcome {
            capabilities = capabilities == null ? Map.of() : Map.copyOf(capabilities);
        }

        public boolean passed() {
            return "passed".equals(status);
        }
    }
}
