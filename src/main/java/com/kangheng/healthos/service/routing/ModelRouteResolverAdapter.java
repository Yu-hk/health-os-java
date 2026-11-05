package com.kangheng.healthos.service.routing;

import java.util.Objects;

import com.kangheng.healthos.common.BizException;
import com.kangheng.healthos.common.Json;
import com.kangheng.healthos.config.HealthOsProperties;
import com.kangheng.healthos.contract.routing.ModelRouteContract;
import com.kangheng.healthos.spi.routing.ModelRouteResolver;

import org.springframework.stereotype.Service;

/**
 * 把配置中心的"当前生效配置"翻译成业务调用侧能直接用的一次调用参数。
 *
 * <p>对应原工程 {@code model_runtime.py}。三条语义：
 * <ol>
 *   <li>配置中心没启用 → {@code legacy}，仍用全局 {@code healthos.ai.*}，老部署零改动；</li>
 *   <li>启用了 → 冻结当前版本，之后这个业务实例永远按这一版跑，管理员改路由不影响在途任务；</li>
 *   <li>引用对不上或密钥解不开 → <b>明确报错</b>，绝不静默退回全局配置。
 *       悄悄降级会让"改了密钥以为生效了"变成最难查的那种问题。</li>
 * </ol>
 */
@Service
public class ModelRouteResolverAdapter implements ModelRouteResolver {

    private static final String ORIGIN_LEGACY = "legacy";
    private static final String ORIGIN_ROUTE = "model_route";

    private final ModelRouteService routeService;
    private final ModelRouteSnapshotStore snapshotStore;
    private final HealthOsProperties properties;

    public ModelRouteResolverAdapter(ModelRouteService routeService,
                                     ModelRouteSnapshotStore snapshotStore,
                                     HealthOsProperties properties) {
        this.routeService = routeService;
        this.snapshotStore = snapshotStore;
        this.properties = properties;
    }

    @Override
    public boolean enabled() {
        return properties.getModelRouting().isEnabled();
    }

    @Override
    public RouteSnapshot freeze(String pipelineKey) {
        if (!enabled()) {
            return new RouteSnapshot(pipelineKey, "legacy", 0, null, null, ORIGIN_LEGACY);
        }
        ModelRouteService.FrozenRoute frozen = guarded(() -> routeService.freeze(pipelineKey));
        return new RouteSnapshot(pipelineKey, frozen.configId(), frozen.configVersion(),
                frozen.credentialRef(), frozen.credentialVersion(), ORIGIN_ROUTE);
    }

    @Override
    public ResolvedRoute resolve(RouteSnapshot snapshot) {
        if (snapshot == null) {
            throw new ModelRouteResolutionException("MODEL_CONFIG_SNAPSHOT_INVALID", "模型配置快照为空");
        }
        if (ORIGIN_LEGACY.equals(snapshot.configOrigin())) {
            return legacy(snapshot.pipelineKey());
        }
        if (!enabled()) {
            // 快照说好走配置中心，配置中心却被关掉了。不能"顺便"用全局密钥顶上——
            // 那把密钥可能压根不是这个供应商的。
            throw new ModelRouteResolutionException("MODEL_CONFIG_SNAPSHOT_INVALID",
                    "配置中心已停用，无法还原该任务引用的模型配置");
        }
        ModelRouteService.FrozenRoute frozen = guarded(() -> routeService.freezeVersion(
                snapshot.pipelineKey(), snapshot.configId(), snapshot.configVersion(),
                snapshot.credentialRef(), snapshot.credentialVersion()));
        return fromFrozen(frozen);
    }

    @Override
    public ResolvedRoute resolveForBusiness(String bizType, String bizId, String pipelineKey) {
        // 落库在独立事务里完成：业务失败回滚时，绑定关系不能被一起回滚掉，
        // 否则每次重试都会重新冻结一版配置。
        RouteSnapshot snapshot = snapshotStore.loadOrFreeze(bizType, bizId, pipelineKey,
                () -> freeze(pipelineKey));
        if (!Objects.equals(pipelineKey, snapshot.pipelineKey())) {
            throw new ModelRouteResolutionException("MODEL_CONFIG_SNAPSHOT_INVALID",
                    "业务实例引用的主线与本次调用不一致");
        }
        return resolve(snapshot);
    }

    // ------------------------------------------------------------ 转换

    private ResolvedRoute fromFrozen(ModelRouteService.FrozenRoute frozen) {
        ModelRouteContract.RouteConfig config = frozen.config();
        ModelRouteContract.ModelParameters p = config.parameters();
        return new ResolvedRoute(
                frozen.pipelineKey(), ORIGIN_ROUTE,
                config.providerId(), config.model(), config.protocol(), config.baseUrl(),
                config.connectionMode(), frozen.secret(),
                p == null ? 0 : p.temperature(),
                p == null ? 8000 : p.maxOutputTokens(),
                p == null ? 15 : p.connectTimeoutSeconds(),
                p == null ? 60 : p.totalTimeoutSeconds(),
                p == null ? 900 : p.runTimeoutSeconds(),
                p == null ? 1 : p.maxRetries());
    }

    /** 迁移期回退：配置中心未启用时，行为与改造前完全一致。 */
    private ResolvedRoute legacy(String pipelineKey) {
        HealthOsProperties.Ai ai = properties.getAi();
        return new ResolvedRoute(
                pipelineKey, ORIGIN_LEGACY,
                properties.getModelRouting().getBaselineProviderId(),
                ai.getModel(), "chat", ai.getBaseUrl(), "direct", ai.getApiKey(),
                0, 8000, 10,
                (int) Math.max(1, ai.getTimeout().toSeconds()), 900, 1);
    }

    /**
     * 配置中心的 {@link BizException} 转成端口自己的异常。
     *
     * <p>不能把 {@code BizException} 直接抛给 provider：那会让适配器反过来认识
     * 应用层的异常类型。这里只搬错误码和消息，密钥原文一个字都不带。
     */
    private <T> T guarded(java.util.function.Supplier<T> call) {
        try {
            return call.get();
        } catch (BizException ex) {
            throw new ModelRouteResolutionException(ex.getCode(), ex.getMessage());
        }
    }
}
