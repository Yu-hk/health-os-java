package com.kangheng.healthos.contract.routing;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 模型配置中心的对外契约。
 *
 * <p>三条硬规矩：
 * <ol>
 *   <li><b>密钥永不回显</b>。请求里可以带新密钥，响应里只给"是否已配置"与凭据版本号；</li>
 *   <li><b>密钥字段用 {@code String} 而不是日志友好的类型</b>，且整个配置中心接口
 *       统一 {@code Cache-Control: no-store}——浏览器缓存住一份带密钥的请求体就等于泄露；</li>
 *   <li><b>改配置必须带期望版本</b>（{@code expectedVersion}），两个人同时改时后一个必须
 *       收到 409 而不是覆盖掉前一个。</li>
 * </ol>
 */
public final class ModelRouteContract {

    private ModelRouteContract() {
    }

    // ------------------------------------------------------------ 模型参数

    /**
     * 单次调用与整轮任务的时限/预算。
     *
     * <p>字段划分对应三段时限：{@code connect}（连上）、{@code first}（首包）、
     * {@code idle}（两包之间），三者都被 {@code total}（单次调用总时限）约束，
     * 而单次调用总时限又被 {@code runTimeout}（整轮任务总时限）约束。
     * 少设一级就会出现"单次调用还在等，任务已经超时了"的错配。
     */
    public record ModelParameters(double temperature,
                                  int maxOutputTokens,
                                  int connectTimeoutSeconds,
                                  int firstTimeoutSeconds,
                                  int idleTimeoutSeconds,
                                  int totalTimeoutSeconds,
                                  int runTimeoutSeconds,
                                  int maxRetries,
                                  int retryBaseMs) {
    }

    // ------------------------------------------------------------ 路由配置

    /**
     * 一条主线的路由配置。
     *
     * @param enabled        是否启用；停用后该主线不允许发起调用
     * @param providerId     供应商标识
     * @param model          模型名（必须非空、不含控制字符）
     * @param protocol       chat / responses / chat_tool
     * @param baseUrl        供应商基础地址（不得含凭据、查询串或片段）
     * @param connectionMode direct / system_proxy
     * @param faultPolicy    故障策略；本切片只有 {@code HALT_AND_RAISE}
     */
    public record RouteConfig(boolean enabled,
                              String providerId,
                              String model,
                              String protocol,
                              String baseUrl,
                              String connectionMode,
                              ModelParameters parameters,
                              String faultPolicy) {
    }

    // ------------------------------------------------------------ 请求

    /** 保存配置。 */
    public record SaveRouteRequest(@NotNull @Min(1) Integer expectedVersion,
                                    @NotNull @Valid RouteConfig config,
                                    @Size(max = 4096) String replacementApiKey,
                                    Boolean clearCredential,
                                    @Size(max = 500) String changeReason) {
    }

    /** 探测（不改配置，只验证这份配置能不能打通）。 */
    public record ProbeRouteRequest(@NotNull @Min(1) Integer expectedVersion,
                                    @NotNull @Valid RouteConfig config,
                                    @Size(max = 4096) String replacementApiKey,
                                    Boolean clearCredential,
                                    @Size(max = 500) String changeReason) {
    }

    /** 回滚到某个历史版本。 */
    public record RollbackRouteRequest(@NotNull @Min(1) Integer expectedVersion,
                                       @NotNull @Min(1) Integer targetVersion) {
    }

    // ------------------------------------------------------------ 响应

    /** 单项能力探测结果。 */
    public record CapabilityView(String status, Long latencyMs, String errorCode, String resolvedModel) {
    }

    /** 一次探测的结果。 */
    public record ProbeView(String status,
                            Long latencyMs,
                            String message,
                            Map<String, CapabilityView> capabilities,
                            OffsetDateTime checkedAt,
                            String scope,
                            Integer testedConfigVersion) {
    }

    /** 一条主线的当前配置视图。 */
    public record RouteView(String pipelineKey,
                            String label,
                            String sensitivity,
                            String configId,
                            Integer version,
                            boolean enabled,
                            String providerId,
                            String model,
                            String protocol,
                            String baseUrl,
                            String connectionMode,
                            ModelParameters parameters,
                            String faultPolicy,
                            boolean credentialConfigured,
                            Integer credentialVersion,
                            List<String> requiredCapabilities,
                            String integrationStatus,
                            ProbeView probe,
                            String updatedBy,
                            OffsetDateTime updatedAt,
                            String changeReason,
                            Map<String, Integer> nodeBudgets,
                            String externalDependency) {
    }

    /** 供应商目录。 */
    public record ProviderView(String id, String model, String baseUrl, String protocol) {
    }

    /** 当前账号的权限。 */
    public record PermissionsView(boolean canManageModelRoutes, boolean admin, boolean traceGlobalReader) {
    }

    /** 列表汇总。 */
    public record RouteSummary(int total, int integrated, int direct, int probed, int probePassed) {
    }

    /** 配置中心总览。 */
    public record RouteListView(List<RouteView> items,
                                PermissionsView permissions,
                                RouteSummary summary,
                                List<ProviderView> providers) {
    }

    /** 历史版本列表。 */
    public record VersionListView(List<RouteView> items, Integer nextBefore) {
    }
}
