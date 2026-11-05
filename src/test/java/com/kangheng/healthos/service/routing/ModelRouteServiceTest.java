package com.kangheng.healthos.service.routing;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import com.kangheng.healthos.common.AccessControl;
import com.kangheng.healthos.common.BizException;
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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 模型配置中心的流程约束。
 *
 * <p>这一组用例守的是<b>改配置这件事本身</b>的安全边界：
 * <ul>
 *   <li>两个人同时改，后一个必须拿到 409 而不是覆盖前一个；</li>
 *   <li>密钥永不回显，落库必须是密文；</li>
 *   <li>没探测过的模型不许上线（除非它就是迁移基线）；</li>
 *   <li>回滚是"用旧配置建新版本"，不是把版本号拨回去；</li>
 *   <li>预算与协议约束拦住"配上了但跑不起来"的组合。</li>
 * </ul>
 */
class ModelRouteServiceTest {

    private static final String MASTER_KEY = "unit-test-master-key-0123456789abcdef";

    private ModelRouteMapper routeMapper;
    private ModelCredentialMapper credentialMapper;
    private ModelRouteVersionMapper versionMapper;
    private ModelRouteProbeMapper probeMapper;
    private RouteProbe routeProbe;
    private HealthOsProperties properties;
    private ModelRouteSecurity security;
    private ModelRouteService service;

    @BeforeEach
    void setUp() {
        routeMapper = mock(ModelRouteMapper.class);
        credentialMapper = mock(ModelCredentialMapper.class);
        versionMapper = mock(ModelRouteVersionMapper.class);
        probeMapper = mock(ModelRouteProbeMapper.class);
        routeProbe = mock(RouteProbe.class);
        properties = new HealthOsProperties();
        properties.getModelRouting().setEnabled(true);
        properties.getModelRouting().setMasterKey(MASTER_KEY);
        security = new ModelRouteSecurity(properties);
        service = new ModelRouteService(routeMapper, credentialMapper, versionMapper, probeMapper,
                security, routeProbe, properties,
                new AccessControl(List.of("admin"), List.of("admin"), List.of("modeladmin")));
    }

    // ------------------------------------------------------------ 夹具

    private ModelRouteContract.ModelParameters agentParameters() {
        return new ModelRouteContract.ModelParameters(0.2, 4000, 15, 45, 45, 45, 180, 0, 2000);
    }

    private ModelRouteContract.ModelParameters qaParameters() {
        return new ModelRouteContract.ModelParameters(0, 8000, 15, 120, 60, 240, 900, 1, 2000);
    }

    private ModelRouteContract.RouteConfig reportConfig() {
        return new ModelRouteContract.RouteConfig(true, "qwen", "qwen-max", "chat",
                "https://dashscope.aliyuncs.com/compatible-mode/v1", "direct",
                new ModelRouteContract.ModelParameters(0, 8000, 15, 120, 60, 240, 900, 1, 2000),
                "HALT_AND_RAISE");
    }

    private ModelRoute route(int version) {
        ModelRoute route = new ModelRoute();
        route.setPipelineKey(ModelPipeline.REPORT.name());
        route.setVersion(version);
        route.setUpdatedAt(OffsetDateTime.now());
        return route;
    }

    private ModelRouteVersion versionRow(int version, ModelRouteContract.RouteConfig config,
                                         String credentialRef, Integer credentialVersion) {
        ModelRouteVersion row = new ModelRouteVersion();
        row.setId("mcfg_" + version);
        row.setPipelineKey(ModelPipeline.REPORT.name());
        row.setVersion(version);
        row.setConfig(Json.write(config));
        row.setCredentialRef(credentialRef);
        row.setCredentialVersion(credentialVersion);
        row.setActor("admin");
        row.setChangeReason("测试");
        row.setCreatedAt(OffsetDateTime.now());
        return row;
    }

    /** 造一把已加密、可解密的凭据。 */
    private ModelCredential credential(String id, int version, String secret) {
        ModelRouteSecurity.Encrypted encrypted =
                security.encrypt(secret, ModelPipeline.REPORT.name(), id, version);
        ModelCredential credential = new ModelCredential();
        credential.setId(id);
        credential.setPipelineKey(ModelPipeline.REPORT.name());
        credential.setVersion(version);
        credential.setCiphertext(encrypted.ciphertext());
        credential.setKeyId(encrypted.keyId());
        credential.setStatus("active");
        credential.setCreatedAt(OffsetDateTime.now());
        return credential;
    }

    private ModelRouteContract.SaveRouteRequest saveRequest(int expectedVersion,
                                                            ModelRouteContract.RouteConfig config,
                                                            String key) {
        return new ModelRouteContract.SaveRouteRequest(expectedVersion, config, key, false, "改配置");
    }

    // ------------------------------------------------------------ 用例

    @Test
    @DisplayName("版本落后时保存必须 409，不能覆盖别人的改动")
    void staleVersionIsRejected() {
        when(routeMapper.selectOne(any())).thenReturn(route(3));

        assertThatThrownBy(() -> service.save(ModelPipeline.REPORT.name(),
                saveRequest(2, reportConfig(), null), "admin"))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> {
                    BizException biz = (BizException) ex;
                    assertThat(biz.getCode()).isEqualTo("MODEL_CONFIG_CONFLICT");
                    assertThat(biz.getStatus().value()).isEqualTo(409);
                });
        verify(versionMapper, never()).insert(any(ModelRouteVersion.class));
    }

    @Test
    @DisplayName("保存成功要追加新版本，配置与凭据成对落库")
    void saveAppendsNewVersion() {
        when(routeMapper.selectOne(any())).thenReturn(route(1));
        when(routeMapper.selectById(any())).thenReturn(route(1));
        when(versionMapper.selectOne(any())).thenReturn(versionRow(1, reportConfig(), "mcred_1", 1));
        when(credentialMapper.selectById("mcred_1")).thenReturn(credential("mcred_1", 1, "sk-old"));
        when(credentialMapper.selectOne(any())).thenReturn(credential("mcred_1", 1, "sk-old"));
        when(credentialMapper.insert(any(ModelCredential.class))).thenAnswer(invocation -> 1);

        ModelRouteContract.RouteConfig changed = new ModelRouteContract.RouteConfig(true, "qwen", "qwen-plus",
                "chat", "https://dashscope.aliyuncs.com/compatible-mode/v1", "direct",
                reportConfig().parameters(), "HALT_AND_RAISE");
        ModelRouteContract.RouteView view = service.save(ModelPipeline.REPORT.name(),
                saveRequest(1, changed, "sk-new"), "admin");

        assertThat(view.version()).isEqualTo(2);
        ArgumentCaptor<ModelRouteVersion> captor = ArgumentCaptor.forClass(ModelRouteVersion.class);
        verify(versionMapper).insert(captor.capture());
        ModelRouteVersion inserted = captor.getValue();
        assertThat(inserted.getVersion()).isEqualTo(2);
        assertThat(inserted.getActor()).isEqualTo("admin");
        assertThat(inserted.getCredentialRef()).isNotBlank();

        // 落库的配置里能看到新模型名，但绝不该出现密钥明文。
        assertThat(inserted.getConfig()).contains("qwen-plus").doesNotContain("sk-new");
        ModelCredential stored = captureCredential();
        assertThat(stored.getCiphertext()).doesNotContain("sk-new");
    }

    @Test
    @DisplayName("配置与密钥都没变时保存是空操作，不产生新版本")
    void identicalSaveIsNoop() {
        when(routeMapper.selectOne(any())).thenReturn(route(1));
        when(routeMapper.selectById(any())).thenReturn(route(1));
        when(versionMapper.selectOne(any())).thenReturn(versionRow(1, reportConfig(), "mcred_1", 1));
        when(credentialMapper.selectById("mcred_1")).thenReturn(credential("mcred_1", 1, "sk-same"));

        ModelRouteContract.RouteView view = service.save(ModelPipeline.REPORT.name(),
                saveRequest(1, reportConfig(), null), "admin");

        assertThat(view.version()).isEqualTo(1);
        verify(versionMapper, never()).insert(any(ModelRouteVersion.class));
    }

    @Test
    @DisplayName("换供应商必须同时给新密钥")
    void switchingProviderRequiresNewKey() {
        when(routeMapper.selectOne(any())).thenReturn(route(1));
        when(routeMapper.selectById(any())).thenReturn(route(1));
        when(versionMapper.selectOne(any())).thenReturn(versionRow(1, reportConfig(), "mcred_1", 1));
        when(credentialMapper.selectById("mcred_1")).thenReturn(credential("mcred_1", 1, "sk-qwen"));

        ModelRouteContract.RouteConfig switched = new ModelRouteContract.RouteConfig(true, "deepseek",
                "deepseek-v4-pro", "chat", "https://api.deepseek.com", "direct",
                reportConfig().parameters(), "HALT_AND_RAISE");

        assertThatThrownBy(() -> service.save(ModelPipeline.REPORT.name(),
                saveRequest(1, switched, null), "admin"))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getCode()).isEqualTo("MODEL_CREDENTIAL_REQUIRED"));
    }

    @Test
    @DisplayName("密钥用掩码占位符要被拒")
    void maskedKeyIsRejected() {
        when(routeMapper.selectOne(any())).thenReturn(route(1));
        when(routeMapper.selectById(any())).thenReturn(route(1));
        when(versionMapper.selectOne(any())).thenReturn(versionRow(1, reportConfig(), "mcred_1", 1));

        assertThatThrownBy(() -> service.save(ModelPipeline.REPORT.name(),
                saveRequest(1, reportConfig(), "sk-****abcd"), "admin"))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getCode()).isEqualTo("MODEL_CREDENTIAL_INVALID"));
    }

    @Test
    @DisplayName("清除凭据与替换凭据不能同时提交")
    void clearAndReplaceAreExclusive() {
        when(routeMapper.selectOne(any())).thenReturn(route(1));
        when(routeMapper.selectById(any())).thenReturn(route(1));
        when(versionMapper.selectOne(any())).thenReturn(versionRow(1, reportConfig(), "mcred_1", 1));

        ModelRouteContract.SaveRouteRequest request = new ModelRouteContract.SaveRouteRequest(
                1, reportConfig(), "sk-new", true, "换密钥");
        assertThatThrownBy(() -> service.save(ModelPipeline.REPORT.name(), request, "admin"))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getCode()).isEqualTo("MODEL_CONFIG_INVALID"));
    }

    @Test
    @DisplayName("视图只说密钥是否已配置，绝不回显明文")
    void viewNeverExposesSecret() {
        when(routeMapper.selectById(any())).thenReturn(route(1));
        when(versionMapper.selectOne(any())).thenReturn(versionRow(1, reportConfig(), "mcred_1", 1));
        when(credentialMapper.selectById("mcred_1")).thenReturn(credential("mcred_1", 1, "sk-secret"));
        when(probeMapper.selectOne(any())).thenReturn(null);

        ModelRouteContract.RouteView view = service.get(ModelPipeline.REPORT.name());

        assertThat(view.credentialConfigured()).isTrue();
        assertThat(view.credentialVersion()).isEqualTo(1);
        assertThat(Json.write(view)).doesNotContain("sk-secret");
    }

    @Test
    @DisplayName("Responses 协议不允许改温度")
    void responsesProtocolRejectsTemperature() {
        ModelRouteContract.RouteConfig config = new ModelRouteContract.RouteConfig(true, "greatwall", "gw-1",
                "responses", "https://gw.example.com", "direct",
                new ModelRouteContract.ModelParameters(0.5, 8000, 15, 120, 60, 240, 900, 1, 2000),
                "HALT_AND_RAISE");
        assertThatThrownBy(() -> service.validateConfig(ModelPipeline.REPORT, config))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getCode()).isEqualTo("MODEL_PARAMETER_UNSUPPORTED"));
    }

    @Test
    @DisplayName("报告主线要求兼容 Chat Completions 的协议")
    void reportRequiresChatProtocol() {
        ModelRouteContract.RouteConfig config = new ModelRouteContract.RouteConfig(true, "greatwall", "gw-1",
                "responses", "https://gw.example.com", "direct",
                new ModelRouteContract.ModelParameters(0, 8000, 15, 120, 60, 240, 900, 1, 2000),
                "HALT_AND_RAISE");
        assertThatThrownBy(() -> service.validateConfig(ModelPipeline.REPORT, config))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getCode()).isEqualTo("MODEL_PROTOCOL_UNSUPPORTED"));
    }

    @Test
    @DisplayName("问答主线的输出与时限有硬预算")
    void qaBudgetIsEnforced() {
        ModelRouteContract.RouteConfig tooMuchOutput = new ModelRouteContract.RouteConfig(true, "qwen", "qwen-max",
                "chat", "https://dashscope.aliyuncs.com/compatible-mode/v1", "direct",
                new ModelRouteContract.ModelParameters(0, 16000, 15, 120, 60, 240, 900, 1, 2000),
                "HALT_AND_RAISE");
        assertThatThrownBy(() -> service.validateConfig(ModelPipeline.PATIENT_QA, tooMuchOutput))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getCode()).isEqualTo("MODEL_BUDGET_EXCEEDED"));

        ModelRouteContract.RouteConfig tooLong = new ModelRouteContract.RouteConfig(true, "qwen", "qwen-max",
                "chat", "https://dashscope.aliyuncs.com/compatible-mode/v1", "direct",
                new ModelRouteContract.ModelParameters(0, 8000, 15, 120, 60, 240, 1200, 1, 2000),
                "HALT_AND_RAISE");
        assertThatThrownBy(() -> service.validateConfig(ModelPipeline.PATIENT_QA, tooLong))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getCode()).isEqualTo("MODEL_BUDGET_EXCEEDED"));
    }

    @Test
    @DisplayName("Agent 主线禁止额外的自动重试，且受输出与时限上限约束")
    void agentBudgetIsEnforced() {
        ModelRouteContract.RouteConfig retrying = new ModelRouteContract.RouteConfig(true, "deepseek", "ds-1",
                "chat", "https://api.deepseek.com", "direct",
                new ModelRouteContract.ModelParameters(0.2, 4000, 15, 45, 45, 45, 180, 1, 2000),
                "HALT_AND_RAISE");
        assertThatThrownBy(() -> service.validateConfig(ModelPipeline.MCP_AGENT, retrying))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getCode()).isEqualTo("MODEL_PARAMETER_UNSUPPORTED"));

        ModelRouteContract.RouteConfig tooBig = new ModelRouteContract.RouteConfig(true, "deepseek", "ds-1",
                "chat", "https://api.deepseek.com", "direct",
                new ModelRouteContract.ModelParameters(0.2, 8000, 15, 45, 45, 45, 180, 0, 2000),
                "HALT_AND_RAISE");
        assertThatThrownBy(() -> service.validateConfig(ModelPipeline.MCP_AGENT, tooBig))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getCode()).isEqualTo("MODEL_BUDGET_EXCEEDED"));
    }

    @Test
    @DisplayName("首包时限不得大于单次总时限")
    void parameterDeadlineConsistency() {
        ModelRouteContract.RouteConfig config = new ModelRouteContract.RouteConfig(true, "qwen", "qwen-max",
                "chat", "https://dashscope.aliyuncs.com/compatible-mode/v1", "direct",
                new ModelRouteContract.ModelParameters(0, 8000, 15, 300, 60, 240, 900, 1, 2000),
                "HALT_AND_RAISE");
        assertThatThrownBy(() -> service.validateConfig(ModelPipeline.REPORT, config))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getCode()).isEqualTo("MODEL_PARAMETER_UNSUPPORTED"));
    }

    @Test
    @DisplayName("模型地址不得含凭据或查询串")
    void baseUrlMustBeClean() {
        ModelRouteContract.RouteConfig withCredential = new ModelRouteContract.RouteConfig(true, "qwen", "qwen-max",
                "chat", "https://user:pass@dashscope.aliyuncs.com/v1", "direct",
                reportConfig().parameters(), "HALT_AND_RAISE");
        assertThatThrownBy(() -> service.validateConfig(ModelPipeline.REPORT, withCredential))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getCode()).isEqualTo("MODEL_CONFIG_INVALID"));
    }

    @Test
    @DisplayName("迁移基线不需要探测就能取生效配置")
    void baselineNeedsNoProbe() {
        when(routeMapper.selectById(any())).thenReturn(route(1));
        when(versionMapper.selectOne(any())).thenReturn(versionRow(1, reportConfig(), "mcred_1", 1));
        when(credentialMapper.selectById("mcred_1")).thenReturn(credential("mcred_1", 1, "sk-baseline"));

        ModelRouteService.FrozenRoute frozen = service.freeze(ModelPipeline.REPORT.name());

        assertThat(frozen.secret()).isEqualTo("sk-baseline");
        assertThat(frozen.configVersion()).isEqualTo(1);
    }

    @Test
    @DisplayName("非基线模型没探测过就不许上线")
    void unprobedModelIsRejected() {
        ModelRouteContract.RouteConfig unproven = new ModelRouteContract.RouteConfig(true, "deepseek", "ds-v4",
                "chat", "https://api.deepseek.com", "direct",
                reportConfig().parameters(), "HALT_AND_RAISE");
        when(routeMapper.selectById(any())).thenReturn(route(1));
        when(versionMapper.selectOne(any())).thenReturn(versionRow(1, unproven, "mcred_1", 1));
        when(credentialMapper.selectById("mcred_1")).thenReturn(credential("mcred_1", 1, "sk-ds"));

        assertThatThrownBy(() -> service.freeze(ModelPipeline.REPORT.name()))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getCode()).isEqualTo("MODEL_CAPABILITY_UNVERIFIED"));
    }

    @Test
    @DisplayName("探测通过后同一份配置可以取生效配置")
    void passedProbeUnblocksModel() {
        ModelRouteContract.RouteConfig unproven = new ModelRouteContract.RouteConfig(true, "deepseek", "ds-v4",
                "chat", "https://api.deepseek.com", "direct",
                reportConfig().parameters(), "HALT_AND_RAISE");
        when(routeMapper.selectById(any())).thenReturn(route(1));
        when(versionMapper.selectOne(any())).thenReturn(versionRow(1, unproven, "mcred_1", 1));
        when(credentialMapper.selectById("mcred_1")).thenReturn(credential("mcred_1", 1, "sk-ds"));
        ModelRouteProbe probe = new ModelRouteProbe();
        probe.setId("mprobe_1");
        probe.setPipelineKey(ModelPipeline.REPORT.name());
        probe.setConfigVersion(1);
        probe.setFingerprint(security.fingerprint(unproven, "sk-ds"));
        probe.setResult(Json.write(Map.of("status", "passed", "latency_ms", 120, "message", "ok",
                "capabilities", Map.of())));
        probe.setActor("admin");
        probe.setCreatedAt(OffsetDateTime.now());
        when(probeMapper.selectOne(any())).thenReturn(probe);

        ModelRouteService.FrozenRoute frozen = service.freeze(ModelPipeline.REPORT.name());

        assertThat(frozen.config().model()).isEqualTo("ds-v4");
        assertThat(frozen.secret()).isEqualTo("sk-ds");
    }

    @Test
    @DisplayName("停用的主线取不到生效配置")
    void disabledRouteIsRejected() {
        ModelRouteContract.RouteConfig disabled = new ModelRouteContract.RouteConfig(false, "qwen", "qwen-max",
                "chat", "https://dashscope.aliyuncs.com/compatible-mode/v1", "direct",
                reportConfig().parameters(), "HALT_AND_RAISE");
        when(routeMapper.selectById(any())).thenReturn(route(1));
        when(versionMapper.selectOne(any())).thenReturn(versionRow(1, disabled, "mcred_1", 1));

        assertThatThrownBy(() -> service.freeze(ModelPipeline.REPORT.name()))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getCode()).isEqualTo("MODEL_ROUTE_DISABLED"));
    }

    @Test
    @DisplayName("没配密钥的主线取不到生效配置")
    void missingCredentialIsRejected() {
        when(routeMapper.selectById(any())).thenReturn(route(1));
        when(versionMapper.selectOne(any())).thenReturn(versionRow(1, reportConfig(), null, null));

        assertThatThrownBy(() -> service.freeze(ModelPipeline.REPORT.name()))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getCode()).isEqualTo("MODEL_CREDENTIAL_MISSING"));
    }

    @Test
    @DisplayName("回滚是用旧配置创建新版本，版本号继续递增")
    void rollbackCreatesNewVersion() {
        when(routeMapper.selectOne(any())).thenReturn(route(5));
        when(versionMapper.selectOne(any())).thenReturn(versionRow(2, reportConfig(), "mcred_2", 1));
        when(credentialMapper.selectById("mcred_2")).thenReturn(credential("mcred_2", 1, "sk-v2"));

        ModelRouteContract.RouteView view = service.rollback(ModelPipeline.REPORT.name(),
                new ModelRouteContract.RollbackRouteRequest(5, 2), "admin");

        assertThat(view.version()).isEqualTo(6);
        ArgumentCaptor<ModelRouteVersion> captor = ArgumentCaptor.forClass(ModelRouteVersion.class);
        verify(versionMapper).insert(captor.capture());
        assertThat(captor.getValue().getVersion()).isEqualTo(6);
        assertThat(captor.getValue().getChangeReason()).contains("回滚");
    }

    @Test
    @DisplayName("回滚到当前版本要被拒")
    void rollbackToCurrentIsRejected() {
        when(routeMapper.selectOne(any())).thenReturn(route(5));
        when(versionMapper.selectOne(any())).thenReturn(versionRow(5, reportConfig(), null, null));

        assertThatThrownBy(() -> service.rollback(ModelPipeline.REPORT.name(),
                new ModelRouteContract.RollbackRouteRequest(5, 5), "admin"))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getCode()).isEqualTo("MODEL_VERSION_CURRENT"));
    }

    @Test
    @DisplayName("探测十秒内重复发起要被限流")
    void probeIsRateLimited() {
        ModelRoute route = route(1);
        route.setLastProbeAt(OffsetDateTime.now());
        when(routeMapper.selectById(any())).thenReturn(route);
        when(versionMapper.selectOne(any())).thenReturn(versionRow(1, reportConfig(), "mcred_1", 1));
        when(credentialMapper.selectById("mcred_1")).thenReturn(credential("mcred_1", 1, "sk-baseline"));

        ModelRouteContract.ProbeRouteRequest request = new ModelRouteContract.ProbeRouteRequest(
                1, reportConfig(), null, false, "探测");
        assertThatThrownBy(() -> service.probe(request, ModelPipeline.REPORT.name()))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> {
                    assertThat(((BizException) ex).getCode()).isEqualTo("MODEL_PROBE_RATE_LIMIT");
                    assertThat(((BizException) ex).getStatus().value()).isEqualTo(429);
                });
    }

    @Test
    @DisplayName("探测记录要落库并带上逐项结果")
    void probeResultIsRecorded() {
        ModelRoute route = route(1);
        when(routeMapper.selectById(any())).thenReturn(route);
        when(versionMapper.selectOne(any())).thenReturn(versionRow(1, reportConfig(), "mcred_1", 1));
        when(credentialMapper.selectById("mcred_1")).thenReturn(credential("mcred_1", 1, "sk-baseline"));
        when(routeProbe.probe(any())).thenReturn(new RouteProbe.ProbeOutcome("passed", 300, "ok",
                Map.of("connectivity", new RouteProbe.CapabilityOutcome("passed", 80, null, "qwen-max"),
                        "structured", new RouteProbe.CapabilityOutcome("passed", 90, null, "qwen-max"),
                        "streaming", new RouteProbe.CapabilityOutcome("passed", 70, null, "qwen-max"),
                        "tool_calling", new RouteProbe.CapabilityOutcome("passed", 60, null, "qwen-max"))));

        ModelRouteContract.ProbeRouteRequest request = new ModelRouteContract.ProbeRouteRequest(
                1, reportConfig(), null, false, "探测");
        ModelRouteContract.ProbeView view = service.probe(request, ModelPipeline.REPORT.name());

        assertThat(view.status()).isEqualTo("passed");
        assertThat(view.capabilities()).containsKey("streaming");
        assertThat(view.capabilities().get("connectivity").resolvedModel()).isEqualTo("qwen-max");
        verify(probeMapper).insert(any(ModelRouteProbe.class));
    }

    @Test
    @DisplayName("未启用配置中心时一律 503")
    void disabledCenterIsUnavailable() {
        properties.getModelRouting().setEnabled(false);
        assertThatThrownBy(() -> service.list("admin"))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> {
                    assertThat(((BizException) ex).getCode()).isEqualTo("MODEL_ROUTING_DISABLED");
                    assertThat(((BizException) ex).getStatus().value()).isEqualTo(503);
                });
    }

    @Test
    @DisplayName("未知主线返回 404")
    void unknownPipelineIsNotFound() {
        assertThatThrownBy(() -> service.get("NOT_A_PIPELINE"))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getStatus().value()).isEqualTo(404));
    }

    @Test
    @DisplayName("权限：模型配置管理是独立白名单，与运维白名单不合并")
    void modelAdminIsSeparateWhitelist() {
        AccessControl control = new AccessControl(List.of("admin"), List.of("admin"), List.of("modeladmin"));
        assertThat(control.isModelAdmin("modeladmin")).isTrue();
        assertThat(control.isModelAdmin("admin")).isFalse();
        assertThat(control.isAdmin("admin")).isTrue();
        assertThat(control.rolesOf("modeladmin")).contains("MODEL_ADMIN");
    }

    @Test
    @DisplayName("加密后的密钥可解密还原，且换一把密钥指纹就变")
    void encryptionRoundTripAndFingerprint() {
        ModelCredential stored = credential("mcred_9", 3, "sk-plaintext");
        assertThat(stored.getCiphertext()).doesNotContain("sk-plaintext");
        assertThat(security.decrypt(stored)).isEqualTo("sk-plaintext");

        String first = security.fingerprint(reportConfig(), "sk-a");
        String second = security.fingerprint(reportConfig(), "sk-b");
        assertThat(first).isNotEqualTo(second);
        assertThat(security.fingerprint(reportConfig(), "sk-a")).isEqualTo(first);
    }

    @Test
    @DisplayName("密文换行即解不开——AAD 把密文绑在具体那行凭据上")
    void credentialCannotBeMovedToAnotherRow() {
        ModelCredential original = credential("mcred_9", 3, "sk-plaintext");
        ModelCredential moved = new ModelCredential();
        moved.setId("mcred_other");
        moved.setPipelineKey(original.getPipelineKey());
        moved.setVersion(original.getVersion());
        moved.setCiphertext(original.getCiphertext());
        moved.setKeyId(original.getKeyId());
        moved.setStatus("active");

        assertThatThrownBy(() -> security.decrypt(moved)).isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("主密钥变更后旧凭据解不开，且报的是可读错误而不是乱码")
    void masterKeyChangeBreaksDecrypt() {
        ModelCredential stored = credential("mcred_9", 3, "sk-plaintext");
        properties.getModelRouting().setMasterKey("another-master-key-0123456789abcdef");
        assertThatThrownBy(() -> security.decrypt(stored))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getCode()).isEqualTo("MODEL_CREDENTIAL_UNREADABLE"));
    }

    @Test
    @DisplayName("已撤销的凭据不能再用")
    void revokedCredentialIsRejected() {
        ModelCredential stored = credential("mcred_9", 3, "sk-plaintext");
        stored.setStatus("revoked");
        assertThatThrownBy(() -> security.decrypt(stored))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getCode()).isEqualTo("MODEL_CREDENTIAL_REVOKED"));
    }

    /** 抓取刚落库的凭据实体。 */
    private ModelCredential captureCredential() {
        ArgumentCaptor<ModelCredential> captor = ArgumentCaptor.forClass(ModelCredential.class);
        verify(credentialMapper).insert(captor.capture());
        return captor.getValue();
    }
}
