package com.kangheng.healthos.service.routing;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import com.kangheng.healthos.common.AccessControl;
import com.kangheng.healthos.common.BizException;
import com.kangheng.healthos.common.Ids;
import com.kangheng.healthos.common.Json;
import com.kangheng.healthos.config.HealthOsProperties;
import com.kangheng.healthos.contract.routing.ModelPipeline;
import com.kangheng.healthos.contract.routing.ModelRouteContract;
import com.kangheng.healthos.mapper.routing.ModelCredentialMapper;
import com.kangheng.healthos.mapper.routing.ModelRouteMapper;
import com.kangheng.healthos.mapper.routing.ModelRouteProbeMapper;
import com.kangheng.healthos.mapper.routing.ModelRouteVersionMapper;
import com.kangheng.healthos.model.routing.ModelCredential;
import com.kangheng.healthos.model.routing.ModelRoute;
import com.kangheng.healthos.model.routing.ModelRouteProbe;
import com.kangheng.healthos.model.routing.ModelRouteVersion;
import com.kangheng.healthos.spi.routing.RouteProbe;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 模型配置中心。
 *
 * <p>它回答一个问题：**每条业务主线现在该调哪个模型、用哪把密钥、什么参数。**
 * 回答方式是"版本化 + 只追加"：
 * <ul>
 *   <li>{@code model_routes} 只存当前是第几版；</li>
 *   <li>{@code model_route_versions} 存每一版配置，改一次加一行，绝不原地改；</li>
 *   <li>{@code model_credentials} 存加密后的密钥，与配置版本成对引用。</li>
 * </ul>
 *
 * <p>三条不能破的纪律：
 * <ol>
 *   <li><b>密钥永不回显</b>。接口只给"是否已配置"与凭据版本号；</li>
 *   <li><b>改配置必须带期望版本</b>。两个人同时改，后一个拿到 409 而不是覆盖前一个；</li>
 *   <li><b>没探测过的模型不许上线</b>。除非它就是当前迁移基线——基线已经在跑了，
 *       它"能用"这件事本身就是既成事实。</li>
 * </ol>
 *
 * <p>对应原工程 {@code model_route_service.py}。
 */
@Service
public class ModelRouteService {

    /** Agent 主线的输出上限（token）。对齐原工程 {@code agent_run_contract.MAX_OUTPUT_TOKENS}。 */
    public static final int AGENT_MAX_OUTPUT_TOKENS = 4000;
    /** Agent 主线的单次模型调用超时（秒）。 */
    public static final int AGENT_MODEL_TIMEOUT = 45;
    /** Agent 主线的整轮任务超时（秒）。 */
    public static final int AGENT_RUN_TIMEOUT = 180;
    /** 问答主线的输出上限（token）。 */
    public static final int QA_MAX_OUTPUT_TOKENS = 8000;
    /** 问答主线的整轮任务超时（秒）。 */
    public static final int QA_MAX_RUN_TIMEOUT = 900;

    /** 探测限流窗口（秒）：配置页连点十下不该打出十次外部调用。 */
    private static final int PROBE_RATE_LIMIT_SECONDS = 10;
    /** 历史版本每页条数。 */
    private static final int HISTORY_PAGE_SIZE = 20;

    private final ModelRouteMapper routeMapper;
    private final ModelCredentialMapper credentialMapper;
    private final ModelRouteVersionMapper versionMapper;
    private final ModelRouteProbeMapper probeMapper;
    private final ModelRouteSecurity security;
    private final RouteProbe routeProbe;
    private final HealthOsProperties properties;
    private final AccessControl accessControl;

    public ModelRouteService(ModelRouteMapper routeMapper,
                             ModelCredentialMapper credentialMapper,
                             ModelRouteVersionMapper versionMapper,
                             ModelRouteProbeMapper probeMapper,
                             ModelRouteSecurity security,
                             RouteProbe routeProbe,
                             HealthOsProperties properties,
                             AccessControl accessControl) {
        this.routeMapper = routeMapper;
        this.credentialMapper = credentialMapper;
        this.versionMapper = versionMapper;
        this.probeMapper = probeMapper;
        this.security = security;
        this.routeProbe = routeProbe;
        this.properties = properties;
        this.accessControl = accessControl;
    }

    // ------------------------------------------------------------ 目录与初始化

    /**
     * 幂等初始化：只补齐缺失的主线，已有配置一律不碰。
     *
     * <p>这条约束来自实际运维场景——配置中心上线时已经有人手工改过几条主线，
     * 启动逻辑不该把它们冲回默认。主键冲突直接忽略即可：并发初始化时
     * 两个实例可能同时插同一条，谁先插谁算，另一个忽略冲突走"已存在"分支。
     */
    @Transactional
    public void initialize() {
        ensureEnabled();
        HealthOsProperties.ModelRouting routing = properties.getModelRouting();
        String seedSecret = properties.getAi().getApiKey();
        for (ModelPipeline pipeline : ModelPipeline.values()) {
            if (routeMapper.selectById(pipeline.name()) != null) {
                continue;
            }
            ModelRouteContract.RouteConfig config = initialConfig(pipeline);
            String credentialRef = null;
            Integer credentialVersion = null;
            if (seedSecret != null && !seedSecret.isBlank()) {
                ModelCredential credential = addCredential(pipeline.name(), seedSecret);
                credentialRef = credential.getId();
                credentialVersion = credential.getVersion();
            }
            ModelRoute route = new ModelRoute();
            route.setPipelineKey(pipeline.name());
            route.setVersion(1);
            route.setUpdatedAt(OffsetDateTime.now());
            try {
                routeMapper.insert(route);
            } catch (DuplicateKeyException ignored) {
                // 另一个实例已经插入同一主线，继续走版本写入即可。
            }
            ModelRouteVersion version = new ModelRouteVersion();
            version.setId(Ids.newId("mcfg"));
            version.setPipelineKey(pipeline.name());
            version.setVersion(1);
            version.setConfig(Json.write(config));
            version.setCredentialRef(credentialRef);
            version.setCredentialVersion(credentialVersion);
            version.setActor("system:init");
            version.setChangeReason("从当前业务默认值初始化；保留已有独立配置");
            version.setCreatedAt(OffsetDateTime.now());
            try {
                versionMapper.insert(version);
            } catch (DuplicateKeyException ignored) {
                // 同上：已被别的事务写入。
            }
        }
    }

    /**
     * 某条主线的初始配置。
     *
     * <p>参数不是一刀切的：问答主线用默认时限（它自己带 SSE 与长输出预算）；
     * 其余主线按供应商超时推算；Agent 主线额外收紧——它有显式任务重试与
     * 自己的输出预算，配上自动重试就成了"重试叠重试"，出问题时烧的钱翻倍。
     */
    private ModelRouteContract.RouteConfig initialConfig(ModelPipeline pipeline) {
        HealthOsProperties.ModelRouting routing = properties.getModelRouting();
        ModelRouteContract.ModelParameters parameters =
                new ModelRouteContract.ModelParameters(0, 8000, 15, 120, 60, 240, 900, 1, 2000);
        if (pipeline != ModelPipeline.PATIENT_QA) {
            int total = Math.min(600, Math.max(15, routing.getProviderTimeoutSeconds()));
            parameters = new ModelRouteContract.ModelParameters(0,
                    Math.min(24000, routing.getStructuredOutputMaxTokens()),
                    15, Math.min(120, total), Math.min(60, total), total, 900, 1, 2000);
        }
        if (pipeline.isAgentPipeline()) {
            parameters = new ModelRouteContract.ModelParameters(0.2, AGENT_MAX_OUTPUT_TOKENS,
                    15, AGENT_MODEL_TIMEOUT, AGENT_MODEL_TIMEOUT, AGENT_MODEL_TIMEOUT,
                    AGENT_RUN_TIMEOUT, 0, 2000);
        }
        return new ModelRouteContract.RouteConfig(true,
                routing.getBaselineProviderId(), routing.getBaselineModel(), routing.getBaselineProtocol(),
                routing.getBaselineBaseUrl(), "direct", parameters, "HALT_AND_RAISE");
    }

    // ------------------------------------------------------------ 读取

    /** 配置中心总览。 */
    @Transactional
    public ModelRouteContract.RouteListView list(String user) {
        initialize();
        List<ModelRouteContract.RouteView> items = new ArrayList<>();
        for (ModelPipeline pipeline : ModelPipeline.values()) {
            items.add(view(current(pipeline.name())));
        }
        int direct = 0;
        int probed = 0;
        int probePassed = 0;
        for (ModelRouteContract.RouteView item : items) {
            if ("direct".equals(item.connectionMode())) {
                direct++;
            }
            if (item.probe() != null) {
                probed++;
                if ("passed".equals(item.probe().status())) {
                    probePassed++;
                }
            }
        }
        ModelRouteContract.RouteSummary summary = new ModelRouteContract.RouteSummary(
                items.size(), items.size(), direct, probed, probePassed);
        return new ModelRouteContract.RouteListView(items, permissions(user), summary, providerCatalog());
    }

    /** 单条主线的当前配置。 */
    @Transactional
    public ModelRouteContract.RouteView get(String key) {
        return view(current(key));
    }

    /** 历史版本列表（倒序）。 */
    @Transactional
    public ModelRouteContract.VersionListView history(String key, Integer before) {
        current(key);
        LambdaQueryWrapper<ModelRouteVersion> wrapper = Wrappers.<ModelRouteVersion>lambdaQuery()
                .eq(ModelRouteVersion::getPipelineKey, key)
                .orderByDesc(ModelRouteVersion::getVersion)
                .last("LIMIT " + (HISTORY_PAGE_SIZE + 1));
        if (before != null) {
            wrapper.lt(ModelRouteVersion::getVersion, before);
        }
        List<ModelRouteVersion> rows = versionMapper.selectList(wrapper);
        List<ModelRouteContract.RouteView> items = new ArrayList<>();
        for (ModelRouteVersion row : rows) {
            if (items.size() == HISTORY_PAGE_SIZE) {
                break;
            }
            items.add(view(row));
        }
        Integer nextBefore = rows.size() > HISTORY_PAGE_SIZE ? rows.get(HISTORY_PAGE_SIZE - 1).getVersion() : null;
        return new ModelRouteContract.VersionListView(items, nextBefore);
    }

    /** 当前账号在配置中心的权限。 */
    public ModelRouteContract.PermissionsView permissions(String user) {
        return new ModelRouteContract.PermissionsView(
                accessControl.isModelAdmin(user),
                accessControl.isAdmin(user),
                accessControl.isTraceGlobalReader(user));
    }

    /** 供应商目录。 */
    public List<ModelRouteContract.ProviderView> providerCatalog() {
        HealthOsProperties.ModelRouting routing = properties.getModelRouting();
        List<ModelRouteContract.ProviderView> providers = new ArrayList<>();
        providers.add(new ModelRouteContract.ProviderView(routing.getBaselineProviderId(),
                routing.getBaselineModel(), routing.getBaselineBaseUrl(), routing.getBaselineProtocol()));
        providers.add(new ModelRouteContract.ProviderView("openai_compatible", "", "", "chat"));
        return providers;
    }

    // ------------------------------------------------------------ 保存与回滚

    /**
     * 保存配置：追加一个新版本并移动当前指针。
     *
     * <p>配置与密钥都相同的重复保存是<b>无操作</b>——不产生新版本。
     * 否则管理员在页面上点两次保存，历史里就会多出一条一模一样的版本，
     * "这个配置是谁改的"这种问题会越来越难回答。
     */
    @Transactional
    public ModelRouteContract.RouteView save(String key, ModelRouteContract.SaveRouteRequest request, String actor) {
        Draft draft = draft(key, request.expectedVersion(), request.config(),
                request.replacementApiKey(), request.clearCredential());
        ModelRouteVersion previous = draft.previous();
        String oldSecret = credentialValue(previous);
        if (draft.config().equals(previousConfig(previous)) && Objects.equals(draft.secret(), oldSecret)) {
            return view(previous);
        }
        ModelCredential credential = null;
        if (draft.secret() != null && !draft.secret().equals(oldSecret)) {
            credential = addCredential(key, draft.secret());
        }
        ModelRoute route = draft.route();
        route.setVersion(route.getVersion() + 1);
        route.setUpdatedAt(OffsetDateTime.now());
        routeMapper.updateById(route);

        ModelRouteVersion version = new ModelRouteVersion();
        version.setId(Ids.newId("mcfg"));
        version.setPipelineKey(key);
        version.setVersion(route.getVersion());
        version.setConfig(Json.write(draft.config()));
        version.setCredentialRef(credential != null ? credential.getId()
                : (draft.secret() != null ? previous.getCredentialRef() : null));
        version.setCredentialVersion(credential != null ? credential.getVersion()
                : (draft.secret() != null ? previous.getCredentialVersion() : null));
        version.setActor(actor);
        version.setChangeReason(blankToEmpty(request.changeReason()));
        version.setCreatedAt(OffsetDateTime.now());
        versionMapper.insert(version);
        return view(version);
    }

    /**
     * 回滚到历史版本。
     *
     * <p>回滚<b>不是</b>把指针拨回去，而是<b>用历史配置再创建一个新版本</b>。
     * 理由：指针往回拨，"第 5 版"这个编号会被重用，而版本号必须是单调递增的——
     * 否则审计日志里"5 版配置"会指到两份不同的东西。回滚也要留下"谁在什么时候回滚到了哪版"。
     */
    @Transactional
    public ModelRouteContract.RouteView rollback(String key, ModelRouteContract.RollbackRouteRequest request,
                                                 String actor) {
        ModelPipeline pipeline = checkKey(key);
        ensureEnabled();
        ModelRoute route = lockedRoute(key);
        if (!Objects.equals(route.getVersion(), request.expectedVersion())) {
            throw fail("MODEL_CONFIG_CONFLICT", "配置已被更新，请重新读取后修改", HttpStatus.CONFLICT);
        }
        ModelRouteVersion target = versionMapper.selectOne(Wrappers.<ModelRouteVersion>lambdaQuery()
                .eq(ModelRouteVersion::getPipelineKey, key)
                .eq(ModelRouteVersion::getVersion, request.targetVersion()));
        if (target == null) {
            throw fail("MODEL_VERSION_NOT_FOUND", "历史配置版本不存在", HttpStatus.NOT_FOUND);
        }
        if (Objects.equals(target.getVersion(), route.getVersion())) {
            throw fail("MODEL_VERSION_CURRENT", "请选择早于当前版本的配置", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        ModelRouteContract.RouteConfig targetConfig = parseConfig(target.getConfig());
        validateConfig(pipeline, targetConfig);
        if (target.getCredentialRef() != null) {
            // 先确认这份历史密钥现在还能解开、解开后还是 active，再允许回滚。
            credentialValue(target);
            ModelCredential credential = credentialMapper.selectById(target.getCredentialRef());
            if (credential == null || !"active".equals(credential.getStatus())) {
                throw fail("MODEL_CREDENTIAL_INACTIVE", "历史凭据已停用，请使用新密钥保存配置",
                        HttpStatus.CONFLICT);
            }
        }
        route.setVersion(route.getVersion() + 1);
        route.setUpdatedAt(OffsetDateTime.now());
        routeMapper.updateById(route);

        ModelRouteVersion version = new ModelRouteVersion();
        version.setId(Ids.newId("mcfg"));
        version.setPipelineKey(key);
        version.setVersion(route.getVersion());
        version.setConfig(target.getConfig());
        version.setCredentialRef(target.getCredentialRef());
        version.setCredentialVersion(target.getCredentialVersion());
        version.setActor(actor);
        version.setChangeReason("回滚至 v" + target.getVersion() + "（创建新版本）");
        version.setCreatedAt(OffsetDateTime.now());
        versionMapper.insert(version);
        return view(version);
    }

    // ------------------------------------------------------------ 探测

    /** 探测预留：校验配置、限流、算出指纹，然后由调用方去打供应商。 */
    public ProbeReservation reserveProbe(String key, ModelRouteContract.ProbeRouteRequest request) {
        ModelPipeline pipeline = checkKey(key);
        ensureEnabled();
        ModelRoute route = routeMapper.selectById(key);
        if (route == null) {
            initialize();
            route = routeMapper.selectById(key);
        }
        if (route == null) {
            throw fail("MODEL_ROUTE_NOT_FOUND", "模型业务主线不存在", HttpStatus.NOT_FOUND);
        }
        if (!Objects.equals(route.getVersion(), request.expectedVersion())) {
            throw fail("MODEL_CONFIG_CONFLICT", "配置已被更新，请重新读取后修改", HttpStatus.CONFLICT);
        }
        ModelRouteContract.RouteConfig config = request.config();
        validateConfig(pipeline, config);
        // 探测同样可以沿用已保存的密钥：管理员改个模型名就想先探一下，
        // 不该被要求"再把密钥粘一遍"。所以这里要拿到当前版本。
        ModelRouteVersion previous = versionMapper.selectOne(Wrappers.<ModelRouteVersion>lambdaQuery()
                .eq(ModelRouteVersion::getPipelineKey, key)
                .eq(ModelRouteVersion::getVersion, route.getVersion()));
        String secret = resolveSecret(key, config, request.replacementApiKey(), request.clearCredential(), previous);
        if (secret == null) {
            throw fail("MODEL_CREDENTIAL_MISSING", "请先输入或保存模型密钥", HttpStatus.SERVICE_UNAVAILABLE);
        }
        OffsetDateTime now = OffsetDateTime.now();
        if (route.getLastProbeAt() != null
                && route.getLastProbeAt().plusSeconds(PROBE_RATE_LIMIT_SECONDS).isAfter(now)) {
            throw fail("MODEL_PROBE_RATE_LIMIT", "探测过于频繁，请在 10 秒后重试", HttpStatus.TOO_MANY_REQUESTS);
        }
        String fingerprint = security.fingerprint(config, secret);
        route.setLastProbeAt(now);
        route.setUpdatedAt(now);
        routeMapper.updateById(route);
        return new ProbeReservation(config, secret, fingerprint, route.getVersion());
    }

    /** 执行探测：真的打一次供应商，但全程不持有数据库行锁。 */
    public ModelRouteContract.ProbeView probe(ModelRouteContract.ProbeRouteRequest request, String key) {
        ModelPipeline pipeline = checkKey(key);
        ProbeReservation reservation = reserveProbe(key, request);
        ModelRouteContract.ModelParameters parameters = reservation.config().parameters();
        RouteProbe.ProbeOutcome outcome = routeProbe.probe(new RouteProbe.RouteTarget(
                reservation.config().providerId(), reservation.config().model(),
                reservation.config().protocol(), reservation.config().baseUrl(),
                parameters == null ? 0 : parameters.temperature(),
                parameters == null ? 15 : parameters.connectTimeoutSeconds(),
                parameters == null ? 60 : parameters.totalTimeoutSeconds(),
                pipeline.capabilities(),
                reservation.secret()));
        return recordProbe(key, request.expectedVersion(), reservation, outcome);
    }

    /** 记录探测结果。 */
    @Transactional
    public ModelRouteContract.ProbeView recordProbe(String key, Integer configVersion,
                                                    ProbeReservation reservation, RouteProbe.ProbeOutcome outcome) {
        Map<String, Object> capabilities = new java.util.LinkedHashMap<>();
        outcome.capabilities().forEach((kind, value) -> capabilities.put(kind, Map.of(
                "status", value.status(),
                "latency_ms", value.latencyMs(),
                "error_code", value.errorCode() == null ? "" : value.errorCode(),
                "resolved_model", value.resolvedModel() == null ? "" : value.resolvedModel())));
        ModelRouteProbe record = new ModelRouteProbe();
        record.setId(Ids.newId("mprobe"));
        record.setPipelineKey(key);
        record.setConfigVersion(configVersion);
        record.setFingerprint(reservation.fingerprint());
        record.setResult(Json.write(Map.of(
                "status", outcome.status(),
                "latency_ms", outcome.latencyMs(),
                "message", outcome.message() == null ? "" : outcome.message(),
                "capabilities", capabilities)));
        record.setActor("system:probe");
        record.setCreatedAt(OffsetDateTime.now());
        probeMapper.insert(record);
        return new ModelRouteContract.ProbeView(outcome.status(), outcome.latencyMs(), outcome.message(),
                capabilityViews(capabilities), record.getCreatedAt(), "tested_draft", configVersion);
    }

    // ------------------------------------------------------------ 取生效配置

    /**
     * 取这条主线**当前生效**的调用配置。
     *
     * <p>这是业务代码真正调用模型前的入口。它保证三件事：
     * <ol>
     *   <li>该主线已启用；</li>
     *   <li>密钥已配置且能解密；</li>
     *   <li>这份配置已被探测验证过——除非它就是迁移基线（基线一直在跑，"能用"是既成事实）。</li>
     * </ol>
     *
     * <p>第 3 条是关键：没有它，管理员把模型改成一把没试过的密钥，
     * 业务就会在半夜用一把不通的密钥去调模型。
     */
    @Transactional
    public FrozenRoute freeze(String key) {
        ModelPipeline pipeline = checkKey(key);
        ensureEnabled();
        ModelRouteVersion version = current(key);
        ModelRouteContract.RouteConfig config = parseConfig(version.getConfig());
        if (!config.enabled()) {
            throw fail("MODEL_ROUTE_DISABLED", "该业务主线模型已停用", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        validateConfig(pipeline, config);
        String secret = credentialValue(version);
        if (secret == null) {
            throw fail("MODEL_CREDENTIAL_MISSING", "请先配置该业务主线的模型密钥",
                    HttpStatus.SERVICE_UNAVAILABLE);
        }
        if (!isBaseline(config)) {
            ModelRouteProbe probe = latestProbe(version);
            if (probe == null || !"passed".equals(probeResult(probe).status())) {
                throw fail("MODEL_CAPABILITY_UNVERIFIED", "此模型尚未通过该主线能力探测，请先验证配置",
                        HttpStatus.UNPROCESSABLE_ENTITY);
            }
        }
        return new FrozenRoute(key, version.getId(), version.getVersion(), version.getCredentialRef(),
                version.getCredentialVersion(), config, secret);
    }

    /**
     * 按快照引用还原出那一版配置。
     *
     * <p>对应原工程 {@code model_runtime.resolve_credential} 的引用校验：
     * 快照里的 configId / version / credentialRef / credentialVersion 必须与库里那一行
     * <b>逐项完全对上</b>，否则说明有人动过历史或被挪去别的任务上了，直接报
     * {@code MODEL_CONFIG_SNAPSHOT_INVALID}，不能"差不多就用"。
     *
     * <p>这里<b>不检查当前是否启用、也不要求重新探测</b>：快照代表任务提交那一刻已经
     * 验证过的配置，在途任务按它跑完即可。管理员事后停用是给<b>新任务</b>设的门禁。
     */
    @Transactional(readOnly = true)
    public FrozenRoute freezeVersion(String pipelineKey, String configId, int configVersion,
                                     String credentialRef, Integer credentialVersion) {
        checkKey(pipelineKey);
        ensureEnabled();
        ModelRouteVersion version = versionMapper.selectById(configId);
        if (version == null
                || !pipelineKey.equals(version.getPipelineKey())
                || version.getVersion() != configVersion
                || !Objects.equals(credentialRef, version.getCredentialRef())
                || !Objects.equals(credentialVersion, version.getCredentialVersion())) {
            throw fail("MODEL_CONFIG_SNAPSHOT_INVALID", "任务引用的配置版本已失效，请重新发起任务",
                    HttpStatus.SERVICE_UNAVAILABLE);
        }
        ModelRouteContract.RouteConfig config = parseConfig(version.getConfig());
        String secret = credentialValue(version);
        if (secret == null) {
            throw fail("MODEL_CREDENTIAL_MISSING", "该任务引用的凭据已不可用",
                    HttpStatus.SERVICE_UNAVAILABLE);
        }
        return new FrozenRoute(pipelineKey, version.getId(), version.getVersion(),
                version.getCredentialRef(), version.getCredentialVersion(), config, secret);
    }

    /** 是否为迁移基线：供应商、模型、协议三者与基线一致。 */
    private boolean isBaseline(ModelRouteContract.RouteConfig config) {
        HealthOsProperties.ModelRouting routing = properties.getModelRouting();
        return routing.getBaselineProviderId().equals(config.providerId())
                && routing.getBaselineModel().equals(config.model())
                && "chat".equals(config.protocol());
    }

    // ------------------------------------------------------------ 配置校验

    /**
     * 配置校验。
     *
     * <p>每条规则背后都有一个具体的不想要的结果，不是形式主义：
     * <ul>
     *   <li>Responses 协议改温度会改变采样行为——该适配使用供应商默认采样，不支持改；</li>
     *   <li>要结构化输出的主线换成非 chat 协议，拿不到一样的字段，只会得到一份结构不对的结果；</li>
     *   <li>代理没配却选了走代理，调用会直接连不上；</li>
     *   <li>问答主线的输出与时限有硬预算——超了就不是"慢一点"，是打断流式输出；</li>
     *   <li>Agent 主线有显式任务重试，再叠自动重试就是重试套重试。</li>
     * </ul>
     */
    public void validateConfig(ModelPipeline pipeline, ModelRouteContract.RouteConfig config) {
        if (config == null) {
            throw fail("MODEL_CONFIG_INVALID", "配置不能为空", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        validateModelName(config.model());
        validateBaseUrl(config.baseUrl());
        validateParameters(config.parameters());
        if ("responses".equals(config.protocol())
                && config.parameters() != null && config.parameters().temperature() != 0) {
            throw fail("MODEL_PARAMETER_UNSUPPORTED", "Responses 适配使用供应商默认采样，本期不支持修改温度",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
        if (pipeline.requiresChatProtocol() && !"chat".equals(config.protocol())) {
            throw fail("MODEL_PROTOCOL_UNSUPPORTED", "该业务需要兼容 Chat Completions 的模型接口",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
        if ("system_proxy".equals(config.connectionMode())
                && (properties.getModelRouting().getSystemProxy() == null
                        || properties.getModelRouting().getSystemProxy().isBlank())) {
            throw fail("MODEL_PROXY_NOT_CONFIGURED", "尚未配置服务端代理，请配置代理或选择直连",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
        if (!"direct".equals(config.connectionMode()) && !"system_proxy".equals(config.connectionMode())) {
            throw fail("MODEL_CONFIG_INVALID", "连接方式只支持 direct 或 system_proxy",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
        if (pipeline == ModelPipeline.PATIENT_QA) {
            if (config.parameters().maxOutputTokens() > QA_MAX_OUTPUT_TOKENS) {
                throw fail("MODEL_BUDGET_EXCEEDED", "问答主线输出上限不能超过 8000 Token",
                        HttpStatus.UNPROCESSABLE_ENTITY);
            }
            if (config.parameters().runTimeoutSeconds() > QA_MAX_RUN_TIMEOUT) {
                throw fail("MODEL_BUDGET_EXCEEDED", "问答任务总时限不能超过 900 秒",
                        HttpStatus.UNPROCESSABLE_ENTITY);
            }
        }
        if (pipeline.isAgentPipeline()) {
            ModelRouteContract.ModelParameters parameters = config.parameters();
            if (parameters.maxRetries() != 0) {
                throw fail("MODEL_PARAMETER_UNSUPPORTED", "Agent 使用显式任务重试，额外自动重试次数须为 0",
                        HttpStatus.UNPROCESSABLE_ENTITY);
            }
            if (parameters.maxOutputTokens() > AGENT_MAX_OUTPUT_TOKENS
                    || parameters.totalTimeoutSeconds() > AGENT_MODEL_TIMEOUT
                    || parameters.runTimeoutSeconds() > AGENT_RUN_TIMEOUT) {
                throw fail("MODEL_BUDGET_EXCEEDED", "Agent 配置超过当前服务端输出或时间上限",
                        HttpStatus.UNPROCESSABLE_ENTITY);
            }
        }
    }

    private void validateModelName(String model) {
        if (model == null || model.isBlank()) {
            throw fail("MODEL_CONFIG_INVALID", "请输入真实模型名称", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        String trimmed = model.trim();
        for (int i = 0; i < trimmed.length(); i++) {
            if (trimmed.charAt(i) < 32) {
                throw fail("MODEL_CONFIG_INVALID", "请输入真实模型名称", HttpStatus.UNPROCESSABLE_ENTITY);
            }
        }
    }

    /** 地址必须是不含凭据、查询串与片段的 HTTP(S) 基础地址。 */
    private void validateBaseUrl(String baseUrl) {
        if (baseUrl == null) {
            return;
        }
        String trimmed = baseUrl.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        for (int i = 0; i < trimmed.length(); i++) {
            if (Character.isWhitespace(trimmed.charAt(i))) {
                throw fail("MODEL_CONFIG_INVALID", "模型地址格式错误", HttpStatus.UNPROCESSABLE_ENTITY);
            }
        }
        java.net.URI uri;
        try {
            uri = new java.net.URI(trimmed);
        } catch (java.net.URISyntaxException ex) {
            throw fail("MODEL_CONFIG_INVALID", "模型地址格式错误", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        String scheme = uri.getScheme();
        if (scheme == null || !("http".equals(scheme) || "https".equals(scheme))) {
            throw fail("MODEL_CONFIG_INVALID", "模型地址必须为不含凭据、查询串或片段的 HTTP(S) 基础地址",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw fail("MODEL_CONFIG_INVALID", "模型地址必须为不含凭据、查询串或片段的 HTTP(S) 基础地址",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    /** 参数的取值范围与跨字段约束。 */
    private void validateParameters(ModelRouteContract.ModelParameters parameters) {
        if (parameters == null) {
            throw fail("MODEL_CONFIG_INVALID", "模型参数不能为空", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        if (parameters.temperature() < 0 || parameters.temperature() > 2) {
            throw fail("MODEL_PARAMETER_UNSUPPORTED", "temperature 必须在 0 到 2 之间",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
        if (parameters.maxOutputTokens() < 256 || parameters.maxOutputTokens() > 24000) {
            throw fail("MODEL_PARAMETER_UNSUPPORTED", "max_output_tokens 必须在 256 到 24000 之间",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
        if (parameters.connectTimeoutSeconds() < 1 || parameters.connectTimeoutSeconds() > 60) {
            throw fail("MODEL_PARAMETER_UNSUPPORTED", "connect_timeout_seconds 必须在 1 到 60 之间",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
        if (parameters.firstTimeoutSeconds() < 1 || parameters.firstTimeoutSeconds() > 300) {
            throw fail("MODEL_PARAMETER_UNSUPPORTED", "first_timeout_seconds 必须在 1 到 300 之间",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
        if (parameters.idleTimeoutSeconds() < 1 || parameters.idleTimeoutSeconds() > 300) {
            throw fail("MODEL_PARAMETER_UNSUPPORTED", "idle_timeout_seconds 必须在 1 到 300 之间",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
        if (parameters.totalTimeoutSeconds() < 5 || parameters.totalTimeoutSeconds() > 600) {
            throw fail("MODEL_PARAMETER_UNSUPPORTED", "total_timeout_seconds 必须在 5 到 600 之间",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
        if (parameters.runTimeoutSeconds() < 30 || parameters.runTimeoutSeconds() > 1800) {
            throw fail("MODEL_PARAMETER_UNSUPPORTED", "run_timeout_seconds 必须在 30 到 1800 之间",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
        if (parameters.maxRetries() < 0 || parameters.maxRetries() > 3) {
            throw fail("MODEL_PARAMETER_UNSUPPORTED", "max_retries 必须在 0 到 3 之间",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
        if (parameters.retryBaseMs() < 100 || parameters.retryBaseMs() > 10000) {
            throw fail("MODEL_PARAMETER_UNSUPPORTED", "retry_base_ms 必须在 100 到 10000 之间",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
        int longest = Math.max(parameters.connectTimeoutSeconds(),
                Math.max(parameters.firstTimeoutSeconds(), parameters.idleTimeoutSeconds()));
        if (longest > parameters.totalTimeoutSeconds()) {
            throw fail("MODEL_PARAMETER_UNSUPPORTED", "连接、首包和空闲时限不能大于单次总时限",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
        if (parameters.totalTimeoutSeconds() > parameters.runTimeoutSeconds()) {
            throw fail("MODEL_PARAMETER_UNSUPPORTED", "单次调用时限不能大于任务总时限",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    // ------------------------------------------------------------ 内部协作

    /** 保存/探测的共同前置：乐观并发 + 配置校验 + 密钥解析。 */
    private Draft draft(String key, Integer expectedVersion, ModelRouteContract.RouteConfig config,
                        String replacementApiKey, Boolean clearCredential) {
        ModelPipeline pipeline = checkKey(key);
        ensureEnabled();
        ModelRoute route = lockedRoute(key);
        if (!Objects.equals(route.getVersion(), expectedVersion)) {
            throw fail("MODEL_CONFIG_CONFLICT", "配置已被更新，请重新读取后修改", HttpStatus.CONFLICT);
        }
        validateConfig(pipeline, config);
        ModelRouteVersion previous = versionMapper.selectOne(Wrappers.<ModelRouteVersion>lambdaQuery()
                .eq(ModelRouteVersion::getPipelineKey, key)
                .eq(ModelRouteVersion::getVersion, route.getVersion()));
        if (previous == null) {
            throw fail("MODEL_VERSION_NOT_FOUND", "当前配置版本不存在", HttpStatus.NOT_FOUND);
        }
        String secret = resolveSecret(key, config, replacementApiKey, clearCredential, previous);
        return new Draft(route, previous, config, secret);
    }

    /**
     * 解析这一版要用的密钥。
     *
     * <p>三种情形：给了新密钥就用新的；明确要清除就清空；什么都没给就沿用上一版——
     * 但<b>换供应商时必须给新密钥</b>：沿用旧供应商的密钥去打新供应商，
     * 只会得到一个让人费解的 401。
     *
     * @param previous 当前已保存的配置版本；探测时传入以便沿用旧密钥。首次配置时为 null。
     */
    private String resolveSecret(String key, ModelRouteContract.RouteConfig config, String replacementApiKey,
                                 Boolean clearCredential, ModelRouteVersion previous) {
        if (Boolean.TRUE.equals(clearCredential)) {
            if (replacementApiKey != null && !replacementApiKey.isBlank()) {
                throw fail("MODEL_CONFIG_INVALID", "清除和替换凭据不能同时提交",
                        HttpStatus.UNPROCESSABLE_ENTITY);
            }
            return null;
        }
        String candidate = normalizeSecret(replacementApiKey);
        if (candidate != null) {
            return candidate;
        }
        if (previous == null) {
            // 还没有任何已保存的版本：没有可沿用的密钥，只能让使用者自己填一把。
            throw fail("MODEL_CREDENTIAL_MISSING", "请先输入或保存模型密钥",
                    HttpStatus.SERVICE_UNAVAILABLE);
        }
        ModelRouteContract.RouteConfig previousConfig = previousConfig(previous);
        if (!Objects.equals(config.providerId(), previousConfig.providerId())) {
            throw fail("MODEL_CREDENTIAL_REQUIRED", "切换供应商时请提供该供应商的密钥",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
        return credentialValue(previous);
    }

    /** 密钥格式校验：拒绝掩码占位符与含空白/控制字符的串。 */
    private String normalizeSecret(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.contains("*")) {
            throw fail("MODEL_CREDENTIAL_INVALID", "请填写有效密钥，不可使用掩码占位符",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (Character.isWhitespace(c) || c < 32) {
                throw fail("MODEL_CREDENTIAL_INVALID", "请填写有效密钥，不可使用掩码占位符",
                        HttpStatus.UNPROCESSABLE_ENTITY);
            }
        }
        return trimmed;
    }

    /** 取某一版配置引用的密钥；引用失效就报错，不返回 null 蒙混过去。 */
    private String credentialValue(ModelRouteVersion version) {
        if (version.getCredentialRef() == null) {
            return null;
        }
        ModelCredential credential = credentialMapper.selectById(version.getCredentialRef());
        if (credential == null
                || !Objects.equals(credential.getPipelineKey(), version.getPipelineKey())
                || !Objects.equals(credential.getVersion(), version.getCredentialVersion())) {
            throw fail("MODEL_CREDENTIAL_MISSING", "配置引用的凭据版本不存在",
                    HttpStatus.SERVICE_UNAVAILABLE);
        }
        return security.decrypt(credential);
    }

    /** 新增一版加密凭据。 */
    private ModelCredential addCredential(String pipelineKey, String secret) {
        ModelCredential latest = credentialMapper.selectOne(Wrappers.<ModelCredential>lambdaQuery()
                .eq(ModelCredential::getPipelineKey, pipelineKey)
                .orderByDesc(ModelCredential::getVersion)
                .last("LIMIT 1"));
        int version = latest == null ? 1 : latest.getVersion() + 1;
        String id = Ids.newId("mcred");
        ModelRouteSecurity.Encrypted encrypted = security.encrypt(secret, pipelineKey, id, version);
        ModelCredential credential = new ModelCredential();
        credential.setId(id);
        credential.setPipelineKey(pipelineKey);
        credential.setVersion(version);
        credential.setCiphertext(encrypted.ciphertext());
        credential.setKeyId(encrypted.keyId());
        credential.setStatus("active");
        credential.setCreatedAt(OffsetDateTime.now());
        credentialMapper.insert(credential);
        return credential;
    }

    /** 当前版本（不存在则先初始化）。 */
    private ModelRouteVersion current(String key) {
        checkKey(key);
        ensureEnabled();
        ModelRoute route = routeMapper.selectById(key);
        if (route == null) {
            initialize();
            route = routeMapper.selectById(key);
        }
        if (route == null) {
            throw fail("MODEL_ROUTE_NOT_FOUND", "模型业务主线不存在", HttpStatus.NOT_FOUND);
        }
        ModelRouteVersion version = versionMapper.selectOne(Wrappers.<ModelRouteVersion>lambdaQuery()
                .eq(ModelRouteVersion::getPipelineKey, key)
                .eq(ModelRouteVersion::getVersion, route.getVersion()));
        if (version == null) {
            throw fail("MODEL_VERSION_NOT_FOUND", "当前配置版本不存在", HttpStatus.NOT_FOUND);
        }
        return version;
    }

    /** 锁定当前指针行（保存与回滚时用，避免两人同时改导致版本号错乱）。 */
    private ModelRoute lockedRoute(String key) {
        ModelRoute route = routeMapper.selectOne(Wrappers.<ModelRoute>lambdaQuery()
                .eq(ModelRoute::getPipelineKey, key)
                .last("FOR UPDATE"));
        if (route == null) {
            initialize();
            route = routeMapper.selectOne(Wrappers.<ModelRoute>lambdaQuery()
                    .eq(ModelRoute::getPipelineKey, key)
                    .last("FOR UPDATE"));
        }
        if (route == null) {
            throw fail("MODEL_ROUTE_NOT_FOUND", "模型业务主线不存在", HttpStatus.NOT_FOUND);
        }
        return route;
    }

    /**
     * 某份配置是否已经被探测过（按指纹匹配当前配置+密钥）。
     *
     * <p>取不到密钥时返回 {@code null} 而不是抛错：那只说明"这份配置没法匹配到探测记录"，
     * 属于展示层面的缺失。真正要发起调用时（{@link #freeze}）仍会严格失败。
     * 配置中心整页因为一行凭据读不出来就 503，是把展示问题升级成了可用性问题。
     */
    private ModelRouteProbe latestProbe(ModelRouteVersion version) {
        String secret;
        try {
            secret = credentialValue(version);
        } catch (BizException ex) {
            return null;
        }
        if (secret == null) {
            return null;
        }
        String fingerprint = security.fingerprint(parseConfig(version.getConfig()), secret);
        return probeMapper.selectOne(Wrappers.<ModelRouteProbe>lambdaQuery()
                .eq(ModelRouteProbe::getPipelineKey, version.getPipelineKey())
                .eq(ModelRouteProbe::getFingerprint, fingerprint)
                .orderByDesc(ModelRouteProbe::getCreatedAt)
                .last("LIMIT 1"));
    }

    /** 配置视图。密钥只以"是否已配置"与版本号的形式出现。 */
    public ModelRouteContract.RouteView view(ModelRouteVersion version) {
        ModelPipeline pipeline = ModelPipeline.of(version.getPipelineKey());
        ModelRouteContract.RouteConfig config = parseConfig(version.getConfig());
        ModelRouteProbe probe = latestProbe(version);
        ModelCredential credential = version.getCredentialRef() == null
                ? null : credentialMapper.selectById(version.getCredentialRef());
        boolean credentialConfigured = credential != null && "active".equals(credential.getStatus());
        return new ModelRouteContract.RouteView(
                version.getPipelineKey(),
                pipeline == null ? version.getPipelineKey() : pipeline.label(),
                pipeline == null ? "NORMAL" : pipeline.sensitivity(),
                version.getId(), version.getVersion(), config.enabled(), config.providerId(), config.model(),
                config.protocol(), config.baseUrl(), config.connectionMode(), config.parameters(),
                config.faultPolicy(), credentialConfigured, version.getCredentialVersion(),
                pipeline == null ? List.of() : pipeline.capabilities(), "active",
                probe == null ? null : probeView(probe),
                version.getActor(), version.getCreatedAt(), version.getChangeReason(),
                nodeBudgets(version.getPipelineKey()), externalDependency(version.getPipelineKey()));
    }

    /** 问答主线的三路节点预算；其余主线为空。 */
    private Map<String, Integer> nodeBudgets(String pipelineKey) {
        if (ModelPipeline.PATIENT_QA.name().equals(pipelineKey)) {
            return Map.of("modern", 2400, "tcm", 2400, "summary", 4000);
        }
        return Map.of();
    }

    /** 问答主线的模型绑在 RAGFlow 远端，本页改不了。 */
    private String externalDependency(String pipelineKey) {
        if (ModelPipeline.PATIENT_QA.name().equals(pipelineKey)) {
            return "RAGFlow Chat/Session 模型独立配置，本页不修改远端绑定";
        }
        return null;
    }

    private ModelRouteContract.ProbeView probeView(ModelRouteProbe probe) {
        Map<String, Object> result = Json.readMap(probe.getResult());
        Object latency = result.get("latency_ms");
        Long latencyMs = latency instanceof Number number ? number.longValue() : null;
        Object rawCapabilities = result.get("capabilities");
        Map<String, ModelRouteContract.CapabilityView> capabilities = rawCapabilities instanceof Map<?, ?> map
                ? capabilityViews((Map<String, Object>) map) : Map.of();
        return new ModelRouteContract.ProbeView(
                result.get("status") == null ? null : String.valueOf(result.get("status")),
                latencyMs,
                result.get("message") == null ? null : String.valueOf(result.get("message")),
                capabilities, probe.getCreatedAt(), "tested_draft", probe.getConfigVersion());
    }

    private static String blankToNull(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }

    /** 逐项探测结果 → 视图。 */
    private Map<String, ModelRouteContract.CapabilityView> capabilityViews(Map<String, Object> capabilities) {
        Map<String, ModelRouteContract.CapabilityView> views = new java.util.LinkedHashMap<>();
        capabilities.forEach((kind, value) -> {
            if (!(value instanceof Map<?, ?> item)) {
                return;
            }
            Object itemLatency = item.get("latency_ms");
            views.put(kind, new ModelRouteContract.CapabilityView(
                    item.get("status") == null ? null : String.valueOf(item.get("status")),
                    itemLatency instanceof Number number ? number.longValue() : null,
                    blankToNull(item.get("error_code")),
                    blankToNull(item.get("resolved_model"))));
        });
        return views;
    }

    private RouteProbeResult probeResult(ModelRouteProbe probe) {
        Map<String, Object> result = Json.readMap(probe.getResult());
        String status = result.get("status") == null ? null : String.valueOf(result.get("status"));
        return new RouteProbeResult(status);
    }

    /** 探测结果里只需要状态。 */
    private record RouteProbeResult(String status) {
    }

    private ModelRouteContract.RouteConfig parseConfig(String json) {
        ModelRouteContract.RouteConfig config = Json.read(json, ModelRouteContract.RouteConfig.class);
        if (config == null) {
            throw fail("MODEL_CONFIG_INVALID", "配置内容无法解析", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        return config;
    }

    private ModelRouteContract.RouteConfig previousConfig(ModelRouteVersion version) {
        return parseConfig(version.getConfig());
    }

    private void ensureEnabled() {
        if (!properties.getModelRouting().isEnabled()) {
            throw fail("MODEL_ROUTING_DISABLED", "模型配置中心尚未启用", HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    private ModelPipeline checkKey(String key) {
        ModelPipeline pipeline = ModelPipeline.of(key);
        if (pipeline == null) {
            throw fail("MODEL_ROUTE_NOT_FOUND", "模型业务主线不存在", HttpStatus.NOT_FOUND);
        }
        return pipeline;
    }

    private static String blankToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static BizException fail(String code, String message, HttpStatus status) {
        return new BizException(code, message, status);
    }

    // ------------------------------------------------------------ 值对象

    /** 保存/探测的前置计算结果。 */
    private record Draft(ModelRoute route, ModelRouteVersion previous,
                         ModelRouteContract.RouteConfig config, String secret) {
    }

    /** 探测预留：配置、密钥、指纹与被探测的版本号。 */
    public record ProbeReservation(ModelRouteContract.RouteConfig config, String secret,
                                    String fingerprint, Integer configVersion) {
    }

    /** 生效配置：业务调用模型前拿到的东西。 */
    public record FrozenRoute(String pipelineKey, String configId, Integer configVersion,
                              String credentialRef, Integer credentialVersion,
                              ModelRouteContract.RouteConfig config, String secret) {
    }
}
