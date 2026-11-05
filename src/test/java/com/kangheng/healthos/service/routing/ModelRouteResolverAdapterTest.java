package com.kangheng.healthos.service.routing;

import java.time.Duration;

import com.kangheng.healthos.common.Json;
import com.kangheng.healthos.config.HealthOsProperties;
import com.kangheng.healthos.contract.routing.ModelRouteContract;
import com.kangheng.healthos.mapper.routing.ModelRouteSnapshotMapper;
import com.kangheng.healthos.model.routing.ModelRouteSnapshot;
import com.kangheng.healthos.spi.routing.ModelRouteResolver;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 模型配置中心 → 业务执行路径的接入语义。
 *
 * <p>重点不是"能不能取到配置"，而是三条容易悄悄破掉的纪律：
 * 快照不含密钥、在途任务不被管理员改配置带走、引用对不上时明确报错而不静默降级。
 */
class ModelRouteResolverAdapterTest {

    private static final String PIPELINE = "REPORT";
    private static final String CONFIG_ID = "mcfg_001";
    private static final String CRED_REF = "mcred_001";

    private ModelRouteService routeService;
    private ModelRouteSnapshotMapper snapshotMapper;
    private ModelRouteSnapshotStore snapshotStore;
    private HealthOsProperties properties;
    private ModelRouteResolverAdapter adapter;

    @BeforeEach
    void setUp() {
        routeService = mock(ModelRouteService.class);
        snapshotMapper = mock(ModelRouteSnapshotMapper.class);
        snapshotStore = new ModelRouteSnapshotStore(snapshotMapper);
        properties = new HealthOsProperties();
        properties.getAi().setApiKey("sk-global-legacy");
        properties.getAi().setModel("legacy-model");
        properties.getAi().setBaseUrl("https://legacy.example.com");
        properties.getAi().setTimeout(Duration.ofSeconds(30));
        properties.getModelRouting().setBaselineProviderId("qwen");
        adapter = new ModelRouteResolverAdapter(routeService, snapshotStore, properties);
    }

    private ModelRouteContract.RouteConfig routedConfig(String model) {
        ModelRouteContract.ModelParameters p = new ModelRouteContract.ModelParameters(
                0.2, 4096, 12, 30, 20, 45, 180, 1, 500);
        return new ModelRouteContract.RouteConfig(true, "qwen", model, "chat",
                "https://routed.example.com", "direct", p, "HALT_AND_RAISE");
    }

    private ModelRouteService.FrozenRoute frozen(String model, String secret, int version) {
        return new ModelRouteService.FrozenRoute(PIPELINE, CONFIG_ID, version,
                CRED_REF, 2, routedConfig(model), secret);
    }

    @Test
    @DisplayName("配置中心未启用时走 legacy，行为与改造前一致")
    void disabledFallsBackToLegacy() {
        properties.getModelRouting().setEnabled(false);

        ModelRouteResolver.RouteSnapshot snapshot = adapter.freeze(PIPELINE);
        ModelRouteResolver.ResolvedRoute route = adapter.resolve(snapshot);

        assertThat(snapshot.configOrigin()).isEqualTo("legacy");
        assertThat(route.routed()).isFalse();
        assertThat(route.model()).isEqualTo("legacy-model");
        assertThat(route.baseUrl()).isEqualTo("https://legacy.example.com");
        assertThat(route.secret()).isEqualTo("sk-global-legacy");
        verify(routeService, never()).freeze(any());
    }

    @Test
    @DisplayName("启用后冻结的是配置版本引用，快照里绝不能出现密钥")
    void frozenSnapshotContainsReferencesOnly() {
        properties.getModelRouting().setEnabled(true);
        when(routeService.freeze(PIPELINE)).thenReturn(frozen("qwen-plus", "sk-routed-secret", 3));

        ModelRouteResolver.RouteSnapshot snapshot = adapter.freeze(PIPELINE);
        String json = Json.write(snapshot);

        assertThat(snapshot.configOrigin()).isEqualTo("model_route");
        assertThat(snapshot.configId()).isEqualTo(CONFIG_ID);
        assertThat(snapshot.configVersion()).isEqualTo(3);
        assertThat(snapshot.credentialRef()).isEqualTo(CRED_REF);
        assertThat(json).doesNotContain("sk-routed-secret");
        assertThat(json).doesNotContain("secret");
    }

    @Test
    @DisplayName("按引用还原：拿回那一版的模型、参数与解密后的密钥")
    void resolveSnapshotUsesThatVersion() {
        properties.getModelRouting().setEnabled(true);
        when(routeService.freezeVersion(PIPELINE, CONFIG_ID, 3, CRED_REF, 2))
                .thenReturn(frozen("qwen-plus", "sk-routed-secret", 3));

        ModelRouteResolver.ResolvedRoute route = adapter.resolve(
                new ModelRouteResolver.RouteSnapshot(PIPELINE, CONFIG_ID, 3, CRED_REF, 2,
                        "model_route"));

        assertThat(route.routed()).isTrue();
        assertThat(route.model()).isEqualTo("qwen-plus");
        assertThat(route.baseUrl()).isEqualTo("https://routed.example.com");
        assertThat(route.secret()).isEqualTo("sk-routed-secret");
        assertThat(route.totalTimeoutSeconds()).isEqualTo(45);
        assertThat(route.maxOutputTokens()).isEqualTo(4096);
        assertThat(route.temperature()).isEqualTo(0.2);
    }

    @Test
    @DisplayName("快照 JSON 可往返（record 序列化不能被换实现搞坏）")
    void snapshotJsonRoundTrip() {
        ModelRouteResolver.RouteSnapshot original =
                new ModelRouteResolver.RouteSnapshot(PIPELINE, CONFIG_ID, 3, CRED_REF, 2, "model_route");
        ModelRouteResolver.RouteSnapshot restored =
                Json.read(Json.write(original), ModelRouteResolver.RouteSnapshot.class);
        assertThat(restored).isEqualTo(original);
    }

    @Test
    @DisplayName("快照引用的版本被改过 → 明确报错，不静默用当前配置")
    void staleReferenceIsRejected() {
        properties.getModelRouting().setEnabled(true);
        when(routeService.freezeVersion(any(), any(), org.mockito.ArgumentMatchers.anyInt(),
                any(), any())).thenThrow(new com.kangheng.healthos.common.BizException(
                "MODEL_CONFIG_SNAPSHOT_INVALID", "配置版本已失效",
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> adapter.resolve(
                new ModelRouteResolver.RouteSnapshot(PIPELINE, CONFIG_ID, 3, CRED_REF, 2,
                        "model_route")))
                .isInstanceOf(ModelRouteResolver.ModelRouteResolutionException.class)
                .satisfies(ex -> assertThat(
                        ((ModelRouteResolver.ModelRouteResolutionException) ex).code())
                        .isEqualTo("MODEL_CONFIG_SNAPSHOT_INVALID"));
    }

    @Test
    @DisplayName("配置中心被关掉但快照是 model_route → 不许拿全局密钥顶上")
    void disablingCenterDoesNotSilentlyDowngrade() {
        properties.getModelRouting().setEnabled(false);
        assertThatThrownBy(() -> adapter.resolve(
                new ModelRouteResolver.RouteSnapshot(PIPELINE, CONFIG_ID, 3, CRED_REF, 2,
                        "model_route")))
                .isInstanceOf(ModelRouteResolver.ModelRouteResolutionException.class)
                .hasMessageContaining("停用");
        verify(routeService, never()).freezeVersion(any(), any(),
                org.mockito.ArgumentMatchers.anyInt(), any(), any());
    }

    @Test
    @DisplayName("同一业务实例只冻结一次，之后沿用旧版配置，管理员改路由不影响在途任务")
    void businessSnapshotIsStableAcrossRouteChanges() {
        properties.getModelRouting().setEnabled(true);
        when(routeService.freeze(PIPELINE)).thenReturn(frozen("qwen-plus", "sk-routed-secret", 3));
        // 模拟管理员随后把当前版本改到了 4；快照仍指向 3。
        when(routeService.freezeVersion(PIPELINE, CONFIG_ID, 3, CRED_REF, 2))
                .thenReturn(frozen("qwen-plus", "sk-routed-secret", 3));
        when(routeService.freezeVersion(PIPELINE, CONFIG_ID, 4, CRED_REF, 2))
                .thenReturn(frozen("qwen-max", "sk-other", 4));

        when(snapshotMapper.selectById(any())).thenAnswer(invocation -> null);
        when(snapshotMapper.insert(any(ModelRouteSnapshot.class))).thenAnswer(invocation -> {
            ModelRouteSnapshot inserted = invocation.getArgument(0);
            when(snapshotMapper.selectById(inserted.getId())).thenReturn(inserted);
            return 1;
        });

        ModelRouteResolver.ResolvedRoute first =
                adapter.resolveForBusiness("care_document", "cdoc_1", PIPELINE);
        ModelRouteResolver.ResolvedRoute second =
                adapter.resolveForBusiness("care_document", "cdoc_1", PIPELINE);

        assertThat(first.model()).isEqualTo("qwen-plus");
        assertThat(second.model()).isEqualTo("qwen-plus");
        verify(routeService, times(1)).freeze(PIPELINE);
        verify(snapshotMapper, times(1)).insert(any(ModelRouteSnapshot.class));
    }

    @Test
    @DisplayName("落库的快照只带引用，不带密钥明文")
    void persistedSnapshotHasNoSecret() {
        properties.getModelRouting().setEnabled(true);
        when(routeService.freeze(PIPELINE)).thenReturn(frozen("qwen-plus", "sk-routed-secret", 3));
        when(routeService.freezeVersion(PIPELINE, CONFIG_ID, 3, CRED_REF, 2))
                .thenReturn(frozen("qwen-plus", "sk-routed-secret", 3));
        when(snapshotMapper.selectById(any())).thenReturn(null);
        when(snapshotMapper.insert(any(ModelRouteSnapshot.class))).thenReturn(1);

        adapter.resolveForBusiness("care_document", "cdoc_2", PIPELINE);

        ArgumentCaptor<ModelRouteSnapshot> captor = ArgumentCaptor.forClass(ModelRouteSnapshot.class);
        verify(snapshotMapper).insert(captor.capture());
        assertThat(captor.getValue().getSnapshotJson()).doesNotContain("sk-routed-secret");
        assertThat(captor.getValue().getBizId()).isEqualTo("cdoc_2");
        assertThat(captor.getValue().getPipelineKey()).isEqualTo(PIPELINE);
    }

    @Test
    @DisplayName("业务实例被换成另一条主线 → 拒绝，防止拿报告的配置去答疑问")
    void mismatchedPipelineIsRejected() {
        ModelRouteSnapshot row = new ModelRouteSnapshot();
        row.setSnapshotJson(Json.write(new ModelRouteResolver.RouteSnapshot(
                "PATIENT_QA", CONFIG_ID, 3, CRED_REF, 2, "model_route")));
        when(snapshotMapper.selectById(any())).thenReturn(row);

        assertThatThrownBy(() -> adapter.resolveForBusiness("qa_conversation", "conv_1", PIPELINE))
                .isInstanceOf(ModelRouteResolver.ModelRouteResolutionException.class)
                .hasMessageContaining("不一致");
        verify(routeService, never()).freeze(any());
    }
}
