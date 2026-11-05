package com.kangheng.healthos.spi.routing;

/**
 * 业务执行侧取模型配置的端口。
 *
 * <p>对应原工程 {@code model_runtime.py} 的 {@code freeze_business} / {@code resolve_credential}。
 * 配置中心只负责"该用哪份配置"，这个端口负责"这次任务实际用哪份配置发请求"。
 *
 * <p>为什么要冻结：任务提交到真正执行之间可能隔了很久。如果执行时现读当前配置，
 * 管理员中途改一次模型，同一个任务的前半段和后半段就会用两份不同的配置——
 * 排查时根本说不清结果是怎么来的。所以任务第一次取配置时写一份<b>快照</b>，
 * 之后这个业务实例永远按快照里那一版执行。
 *
 * <p>快照里<b>只有引用，没有密钥</b>。密钥在执行时按引用解密，解密失败直接报错，
 * 不静默降级成"没配密钥"。
 *
 * <p>本接口自带全部类型，不依赖 {@code contract} 或 {@code service}——
 * 扩展点契约不该知道 API 长什么样、也不该知道谁实现它。
 */
public interface ModelRouteResolver {

    /** 配置中心是否启用。未启用时全部走 {@code legacy}（全局 {@code healthos.ai.*}）。 */
    boolean enabled();

    /** 冻结某条主线的当前生效配置，返回可持久化的引用快照。 */
    RouteSnapshot freeze(String pipelineKey);

    /** 按引用快照还原出这一次调用要用的完整配置（含解密后的密钥）。 */
    ResolvedRoute resolve(RouteSnapshot snapshot);

    /**
     * 按业务实例取配置：已有快照就沿用它，没有就冻结一份并落库。
     *
     * <p>这样业务提交处不需要改代码——第一次真正调用模型时才冻结，
     * 之后这个实例一直用同一版配置，管理员改配置不影响在途任务。
     */
    ResolvedRoute resolveForBusiness(String bizType, String bizId, String pipelineKey);

    /**
     * 快照：只存引用。
     *
     * @param configOrigin {@code model_route}（配置中心）或 {@code legacy}（全局 ai.*）
     */
    record RouteSnapshot(String pipelineKey,
                         String configId,
                         int configVersion,
                         String credentialRef,
                         Integer credentialVersion,
                         String configOrigin) {
    }

    /**
     * 一次调用实际要用的配置。密钥只存在于内存里，不落业务表、不进日志。
     */
    record ResolvedRoute(String pipelineKey,
                         String configOrigin,
                         String providerId,
                         String model,
                         String protocol,
                         String baseUrl,
                         String connectionMode,
                         String secret,
                         double temperature,
                         int maxOutputTokens,
                         int connectTimeoutSeconds,
                         int totalTimeoutSeconds,
                         int runTimeoutSeconds,
                         int maxRetries) {

        /** 是否走配置中心（否则是迁移期的全局配置）。 */
        public boolean routed() {
            return "model_route".equals(configOrigin);
        }
    }

    /** 配置不可用。带错误码，便于上层把"密钥失效"和"配置被改"区分开。 */
    class ModelRouteResolutionException extends RuntimeException {

        private final String code;

        public ModelRouteResolutionException(String code, String message) {
            super(message);
            this.code = code;
        }

        public ModelRouteResolutionException(String code, String message, Throwable cause) {
            super(message, cause);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
