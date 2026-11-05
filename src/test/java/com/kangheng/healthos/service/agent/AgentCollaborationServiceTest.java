package com.kangheng.healthos.service.agent;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.kangheng.healthos.common.BizException;
import com.kangheng.healthos.contract.agent.AgentCollaborationContract;
import com.kangheng.healthos.contract.agent.AgentCollaborationContract.AgentRole;
import com.kangheng.healthos.contract.agent.AgentCollaborationContract.AgentScenario;
import com.kangheng.healthos.contract.agent.AgentCollaborationContract.AgentScope;
import com.kangheng.healthos.contract.agent.AgentCollaborationContract.AgentTool;
import com.kangheng.healthos.contract.agent.AgentCollaborationContract.RolePlan;
import com.kangheng.healthos.contract.care.CareContract;
import com.kangheng.healthos.contract.patient.PatientMemoryContract;
import com.kangheng.healthos.mapper.agent.AgentNodeMapper;
import com.kangheng.healthos.mapper.agent.AgentRunMapper;
import com.kangheng.healthos.mapper.care.CareDocumentMapper;
import com.kangheng.healthos.mapper.patient.PatientMapper;
import com.kangheng.healthos.mapper.patient.PatientMemoryJobMapper;
import com.kangheng.healthos.model.agent.AgentNode;
import com.kangheng.healthos.model.agent.AgentRun;
import com.kangheng.healthos.provider.agent.TemplateAgentRoleExecutor;
import com.kangheng.healthos.service.care.CareService;
import com.kangheng.healthos.service.outbox.OutboxService;
import com.kangheng.healthos.service.patient.PatientMemoryService;
import com.kangheng.healthos.spi.agent.AgentRoleExecutor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 多智能体协同的编排约束。
 *
 * <p>这一组用例守的是<b>四个角色的纪律</b>：
 * <ul>
 *   <li>场景决定谁能跑、谁能用什么工具，越权直接拒；</li>
 *   <li>角色只能引用它实际读到过的东西；</li>
 *   <li>下游只接受同轮、状态成功、摘要对得上的上游交接；</li>
 *   <li>必需角色失败就整轮失败——缺了角色的汇报不能被描述成成功。</li>
 * </ul>
 */
class AgentCollaborationServiceTest {

    private static final String OWNER = "admin";
    private static final String PATIENT = "pat_1";

    private AgentRunMapper runMapper;
    private AgentNodeMapper nodeMapper;
    private PatientMemoryService memoryService;
    private CareService careService;
    private Map<String, AgentNode> nodeStore;
    private Map<String, AgentRun> runStore;
    private AgentCollaborationService service;
    private AgentRunEventService eventService;

    @BeforeEach
    void setUp() {
        runMapper = mock(AgentRunMapper.class);
        nodeMapper = mock(AgentNodeMapper.class);
        memoryService = mock(PatientMemoryService.class);
        careService = mock(CareService.class);
        PatientMemoryJobMapper jobMapper = mock(PatientMemoryJobMapper.class);
        CareDocumentMapper documentMapper = mock(CareDocumentMapper.class);
        PatientMapper patientMapper = mock(PatientMapper.class);
        OutboxService outboxService = mock(OutboxService.class);
        eventService = mock(AgentRunEventService.class);

        nodeStore = new LinkedHashMap<>();
        runStore = new LinkedHashMap<>();
        when(nodeMapper.insert(any(AgentNode.class))).thenAnswer(invocation -> {
            AgentNode node = invocation.getArgument(0);
            nodeStore.put(node.getId(), node);
            return 1;
        });
        when(nodeMapper.selectById(anyString())).thenAnswer(invocation -> nodeStore.get(invocation.getArgument(0)));
        when(nodeMapper.selectList(any())).thenAnswer(invocation ->
                new ArrayList<>(nodeStore.values()));
        when(runMapper.insert(any(AgentRun.class))).thenAnswer(invocation -> {
            AgentRun run = invocation.getArgument(0);
            runStore.put(run.getId(), run);
            return 1;
        });
        when(runMapper.selectById(anyString())).thenAnswer(invocation -> runStore.get(invocation.getArgument(0)));
        when(runMapper.selectOne(any())).thenAnswer(invocation ->
                runStore.values().stream().findFirst().orElse(null));

        service = new AgentCollaborationService(runMapper, nodeMapper,
                new TemplateAgentRoleExecutor(), memoryService, careService, jobMapper,
                documentMapper, patientMapper, outboxService, eventService);

        when(memoryService.current(OWNER, PATIENT)).thenReturn(memoryView("pmem_1", 3));
        when(careService.list(OWNER, CareService.KIND_REPORT, PATIENT, 20))
                .thenReturn(List.of(reportView("cdoc_r1", CareService.STATUS_SIGNED)));
        when(careService.list(OWNER, CareService.KIND_PLAN, PATIENT, 20))
                .thenReturn(List.of(planView("cdoc_p1")));
        when(jobMapper.selectList(any())).thenReturn(List.of());
    }

    // ------------------------------------------------------------ 夹具

    private PatientMemoryContract.VersionView memoryView(String id, int version) {
        return new PatientMemoryContract.VersionView(id, PATIENT, version, "memory-v1",
                "programmatic_facts", "hash-" + version, "src-hash", 2,
                "# 记忆\n\n- 事实一", Map.of("facts", List.of("事实一")), List.of(),
                OffsetDateTime.now(), OffsetDateTime.now());
    }

    private CareContract.DocumentView reportView(String id, String status) {
        return new CareContract.DocumentView(id, OWNER, CareService.KIND_REPORT, PATIENT, status, 2, 2,
                null, "exam_1", 1, null, null, null, null, true,
                List.of(new CareContract.SectionView("profile", "综合健康画像", "内容", List.of("bmi"), true)),
                List.of(), new CareContract.CheckinSummary(0, 0, 0), null, "免责", null, null);
    }

    private CareContract.DocumentView planView(String id) {
        return new CareContract.DocumentView(id, OWNER, CareService.KIND_PLAN, PATIENT,
                CareService.STATUS_ACTIVE, 1, 1, 1, "exam_1", 1, "cdoc_r1", 2, null, "2026-11-01",
                true, List.of(), List.of(), new CareContract.CheckinSummary(10, 4, 6), null, "免责",
                null, OffsetDateTime.now());
    }

    private AgentCollaborationContract.SubmitCollaborationRequest request(String scenario, List<String> scopes) {
        return new AgentCollaborationContract.SubmitCollaborationRequest(scenario, scopes,
                "请汇总当前进展", "op-" + scenario);
    }

    private AgentRun submitAndLoad(String scenario, List<String> scopes) {
        service.submit(OWNER, PATIENT, request(scenario, scopes));
        AgentRun run = runStore.values().iterator().next();
        return run;
    }

    // ------------------------------------------------------------ 冻结计划

    @Test
    @DisplayName("progress 场景缺 report/plan 范围时不启用生活方式角色")
    void lifestyleDisabledWithoutScopes() {
        List<RolePlan> plans = AgentCollaborationContract.freezePlan(AgentScenario.PROGRESS,
                Set.of(AgentScope.MEMORY, AgentScope.PIPELINE));
        RolePlan lifestyle = plans.stream()
                .filter(plan -> plan.role() == AgentRole.LIFESTYLE).findFirst().orElseThrow();
        assertThat(lifestyle.enabled()).isFalse();
        assertThat(lifestyle.skipReason()).isNotBlank();
    }

    @Test
    @DisplayName("progress 场景给全范围时四个角色都启用，依赖链是 MEMORY/PIPELINE → LIFESTYLE → SYNTHESIS")
    void fullProgressPlan() {
        List<RolePlan> plans = AgentCollaborationContract.freezePlan(AgentScenario.PROGRESS,
                Set.of(AgentScope.MEMORY, AgentScope.PIPELINE, AgentScope.REPORT, AgentScope.PLAN));
        Map<AgentRole, RolePlan> byRole = new LinkedHashMap<>();
        plans.forEach(plan -> byRole.put(plan.role(), plan));

        assertThat(byRole.get(AgentRole.MEMORY).enabled()).isTrue();
        assertThat(byRole.get(AgentRole.PIPELINE).enabled()).isTrue();
        assertThat(byRole.get(AgentRole.LIFESTYLE).enabled()).isTrue();
        assertThat(byRole.get(AgentRole.SYNTHESIS).enabled()).isTrue();

        assertThat(byRole.get(AgentRole.LIFESTYLE).dependsOn())
                .containsExactlyInAnyOrder(AgentRole.MEMORY, AgentRole.PIPELINE);
        assertThat(byRole.get(AgentRole.SYNTHESIS).dependsOn())
                .containsExactlyInAnyOrder(AgentRole.MEMORY, AgentRole.PIPELINE, AgentRole.LIFESTYLE);
        assertThat(byRole.get(AgentRole.MEMORY).dependsOn()).isEmpty();
    }

    @Test
    @DisplayName("被禁用的角色不能进下游依赖链，否则下游永远等不到交接")
    void disabledRoleIsNotAnUpstream() {
        // 生活方式角色没启用，它不会产出交接。汇总角色的依赖链里若还留着它，
        // 汇总角色就会被判成"必需上游失败或无可交接结果"而整轮失败。
        Map<AgentRole, RolePlan> progress = byRole(AgentScenario.PROGRESS,
                Set.of(AgentScope.MEMORY, AgentScope.PIPELINE));
        assertThat(progress.get(AgentRole.LIFESTYLE).enabled()).isFalse();
        assertThat(progress.get(AgentRole.SYNTHESIS).dependsOn())
                .containsExactlyInAnyOrder(AgentRole.MEMORY, AgentRole.PIPELINE)
                .doesNotContain(AgentRole.LIFESTYLE);

        Map<AgentRole, RolePlan> statistics = byRole(AgentScenario.STATISTICS,
                Set.of(AgentScope.STATISTICS));
        assertThat(statistics.get(AgentRole.PIPELINE).enabled()).isFalse();
        assertThat(statistics.get(AgentRole.SYNTHESIS).dependsOn())
                .containsExactly(AgentRole.MEMORY)
                .doesNotContain(AgentRole.LIFESTYLE, AgentRole.PIPELINE);
    }

    @Test
    @DisplayName("execution 场景不启用记忆角色；statistics 场景只有汇总角色")
    void scenarioGatesRoles() {
        Map<AgentRole, RolePlan> execution = byRole(AgentScenario.EXECUTION,
                Set.of(AgentScope.PIPELINE, AgentScope.PLAN));
        assertThat(execution.get(AgentRole.MEMORY).enabled()).isFalse();
        assertThat(execution.get(AgentRole.PIPELINE).enabled()).isTrue();
        assertThat(execution.get(AgentRole.SYNTHESIS).enabled()).isTrue();

        Map<AgentRole, RolePlan> statistics = byRole(AgentScenario.STATISTICS,
                Set.of(AgentScope.STATISTICS));
        assertThat(statistics.get(AgentRole.MEMORY).enabled()).isTrue();
        assertThat(statistics.get(AgentRole.PIPELINE).enabled()).isFalse();
        assertThat(statistics.get(AgentRole.LIFESTYLE).enabled()).isFalse();
        assertThat(statistics.get(AgentRole.MEMORY).tools())
                .containsExactlyInAnyOrder(AgentTool.GET_PATIENT_STATISTICS, AgentTool.GET_REPORT_STATISTICS);
    }

    @Test
    @DisplayName("场景必需范围缺失或越权都要被拒")
    void scenarioScopeIsEnforced() {
        assertThatThrownBy(() -> AgentCollaborationContract.freezePlan(AgentScenario.PROGRESS,
                Set.of(AgentScope.MEMORY)))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getCode())
                        .isEqualTo(AgentCollaborationContract.ERROR_SCENARIO_SCOPE));

        assertThatThrownBy(() -> AgentCollaborationContract.freezePlan(AgentScenario.STATISTICS,
                Set.of(AgentScope.STATISTICS, AgentScope.MEMORY)))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getCode())
                        .isEqualTo(AgentCollaborationContract.ERROR_SCENARIO_SCOPE));
    }

    @Test
    @DisplayName("工具按授权范围过滤：没授权 memory 就不会出现在工具清单里")
    void toolsAreFilteredByScopes() {
        Map<AgentRole, RolePlan> plans = byRole(AgentScenario.PROGRESS,
                Set.of(AgentScope.MEMORY, AgentScope.PIPELINE));
        assertThat(plans.get(AgentRole.MEMORY).tools()).containsExactly(AgentTool.READ_PATIENT_MEMORY);
        assertThat(plans.get(AgentRole.PIPELINE).tools())
                .containsExactly(AgentTool.GET_PIPELINE_STATUS);
    }

    // ------------------------------------------------------------ 提交与幂等

    @Test
    @DisplayName("同一 operationId 重复提交复用同一次运行")
    void submissionIsIdempotent() {
        AgentCollaborationContract.CollaborationAccepted first =
                service.submit(OWNER, PATIENT, request("progress", List.of("memory", "pipeline")));
        AgentCollaborationContract.CollaborationAccepted second =
                service.submit(OWNER, PATIENT, request("progress", List.of("memory", "pipeline")));
        assertThat(first.reused()).isFalse();
        assertThat(second.reused()).isTrue();
        assertThat(second.runId()).isEqualTo(first.runId());
    }

    @Test
    @DisplayName("同一 operationId 换了输入要报冲突，不能静默复用")
    void idempotencyMismatchIsRejected() {
        service.submit(OWNER, PATIENT, request("progress", List.of("memory", "pipeline")));
        assertThatThrownBy(() -> service.submit(OWNER, PATIENT,
                new AgentCollaborationContract.SubmitCollaborationRequest("progress",
                        List.of("memory", "pipeline"), "换了指令", "op-progress")))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getStatus().value()).isEqualTo(409));
    }

    @Test
    @DisplayName("执行完成后再用同一 operationId 提交，仍然复用同一次运行而不是报冲突")
    void idempotencySurvivesExecution() {
        AgentRun run = submitAndLoad("progress", List.of("memory", "pipeline", "report", "plan"));
        service.execute(run.getId());

        // 执行会把最终结果写进 result 列。若幂等指纹与 result 共用一列，
        // 此时的重复提交会被误判成"同一 operationId 换了请求"而报 409。
        AgentCollaborationContract.CollaborationAccepted again =
                service.submit(OWNER, PATIENT, request("progress", List.of("memory", "pipeline", "report", "plan")));
        assertThat(again.reused()).isTrue();
        assertThat(again.runId()).isEqualTo(run.getId());
        assertThat(again.status()).isEqualTo("SUCCEEDED");
    }

    @Test
    @DisplayName("查不到任务时补采样回执来源，不能因为\"没有实体\"把角色判死")
    void emptyResultStillCarriesSampleReceipt() {
        // 只授权 memory+pipeline：任务状态是任务感知角色的唯一读取，
        // 而夹具里该患者没有任何任务记录——这次查询的结论就是"没有"。
        AgentRun run = submitAndLoad("progress", List.of("memory", "pipeline"));
        service.execute(run.getId());

        AgentRun finished = runStore.get(run.getId());
        assertThat(finished.getStatus())
                .as("节点状态: %s", nodeStore.values().stream()
                        .map(node -> node.getRole() + "/" + node.getKind() + ":" + node.getStatus()
                                + ":" + node.getError()).toList())
                .isEqualTo("SUCCEEDED");
        assertThat(finished.getResult())
                .as("空结果也要留下\"这次查询执行过\"的凭据")
                .contains("sample:get_pipeline_status");
    }

    @Test
    @DisplayName("统计场景同样成立：账号汇总为空也补采样回执")
    void statisticsScenarioAcceptsEmptyAggregate() {
        AgentRun run = submitAndLoad("statistics", List.of("statistics"));
        service.execute(run.getId());

        AgentRun finished = runStore.get(run.getId());
        assertThat(finished.getStatus())
                .as("节点状态: %s", nodeStore.values().stream()
                        .map(node -> node.getRole() + "/" + node.getKind() + ":" + node.getStatus()
                                + ":" + node.getError()).toList())
                .isEqualTo("SUCCEEDED");
        assertThat(finished.getResult()).contains("sample:get_patient_statistics");
    }

    @Test
    @DisplayName("场景与范围不匹配在提交时就拒，不留到执行期")
    void invalidScenarioRejectedOnSubmit() {
        assertThatThrownBy(() -> service.submit(OWNER, PATIENT, request("statistics", List.of("memory"))))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getCode())
                        .isEqualTo(AgentCollaborationContract.ERROR_SCENARIO_SCOPE));
    }

    // ------------------------------------------------------------ 执行

    @Test
    @DisplayName("四个角色跑完，整轮成功且汇总带来源")
    void fullRunSucceeds() {
        AgentRun run = submitAndLoad("progress", List.of("memory", "pipeline", "report", "plan"));
        service.execute(run.getId());

        AgentRun finished = runStore.get(run.getId());
        assertThat(finished.getStatus())
                .as("节点状态: %s", nodeStore.values().stream()
                        .map(node -> node.getRole() + "/" + node.getKind() + ":" + node.getStatus()
                                + ":" + node.getError()).toList())
                .isEqualTo("SUCCEEDED");
        assertThat(nodeStore.values()).hasSizeGreaterThanOrEqualTo(8);
        assertThat(nodeStore.values().stream()
                .filter(node -> "role".equals(node.getKind()))
                .filter(node -> "succeeded".equals(node.getStatus())))
                .hasSize(4);
        assertThat(finished.getResult()).contains("disclaimer").contains("verified_facts");
    }

    @Test
    @DisplayName("角色引用了没读到过的来源 → 角色失败 → 整轮失败")
    void hallucinatedSourceFailsRun() {
        AgentRoleExecutor hallucinating = request1 -> new AgentRoleExecutor.RoleOutcome(
                new AgentRoleExecutor.Answer("编的结论", List.of(), List.of(),
                        List.of(new AgentRoleExecutor.Source("pmem_not_read", 1))),
                request1.readings());
        AgentCollaborationService strict = new AgentCollaborationService(runMapper, nodeMapper,
                hallucinating, memoryService, careService, mock(PatientMemoryJobMapper.class),
                mock(CareDocumentMapper.class), mock(PatientMapper.class),
                mock(OutboxService.class), eventService);
        AgentRun run = submitAndLoad("progress", List.of("memory", "pipeline", "report", "plan"));
        strict.execute(run.getId());

        AgentRun finished = runStore.get(run.getId());
        assertThat(finished.getStatus()).isEqualTo("FAILED");
        assertThat(finished.getError()).isEqualTo(AgentCollaborationContract.ERROR_REQUIRED_ROLE_FAILED);
        assertThat(nodeStore.values().stream()
                .anyMatch(node -> AgentCollaborationContract.ERROR_ROLE_SOURCE_INVALID.equals(node.getError())))
                .isTrue();
    }

    @Test
    @DisplayName("必需上游失败时下游角色标记 skipped，而不是继续推导")
    void downstreamSkippedWhenUpstreamFails() {
        AgentRoleExecutor failing = request1 -> {
            if ("patient_memory_manager".equals(request1.role())) {
                throw new IllegalStateException("模拟角色执行失败");
            }
            return new TemplateAgentRoleExecutor().execute(request1);
        };
        AgentCollaborationService strict = new AgentCollaborationService(runMapper, nodeMapper,
                failing, memoryService, careService, mock(PatientMemoryJobMapper.class),
                mock(CareDocumentMapper.class), mock(PatientMapper.class),
                mock(OutboxService.class), eventService);
        AgentRun run = submitAndLoad("progress", List.of("memory", "pipeline", "report", "plan"));
        strict.execute(run.getId());

        assertThat(nodeStore.values().stream()
                .anyMatch(node -> "skipped".equals(node.getStatus())
                        && "lifestyle_advisor".equals(node.getRole())))
                .isTrue();
        assertThat(runStore.get(run.getId()).getStatus()).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("交接被篡改（摘要对不上）时下游不得接手")
    void tamperedHandoffIsRejected() {
        // 在 PIPELINE 角色执行时篡改已完成的 MEMORY 节点：
        // 等到 LIFESTYLE 取交接时，摘要与内容已经对不上。
        AgentRoleExecutor tampering = request1 -> {
            if ("pipeline_inspector".equals(request1.role())) {
                for (AgentNode node : nodeStore.values()) {
                    if ("patient_memory_manager".equals(node.getRole()) && "role".equals(node.getKind())) {
                        node.setOutput("{\"role_id\":\"patient_memory_manager\",\"answer\":{\"summary\":\"被改过\"}}");
                    }
                }
            }
            return new TemplateAgentRoleExecutor().execute(request1);
        };
        AgentCollaborationService strict = new AgentCollaborationService(runMapper, nodeMapper,
                tampering, memoryService, careService, mock(PatientMemoryJobMapper.class),
                mock(CareDocumentMapper.class), mock(PatientMapper.class),
                mock(OutboxService.class), eventService);
        AgentRun run = submitAndLoad("progress", List.of("memory", "pipeline", "report", "plan"));
        strict.execute(run.getId());

        assertThat(nodeStore.values().stream()
                .anyMatch(node -> "lifestyle_advisor".equals(node.getRole())
                        && "failed".equals(node.getStatus())
                        && AgentCollaborationContract.ERROR_HANDOFF_INVALID.equals(node.getError())))
                .isTrue();
        assertThat(runStore.get(run.getId()).getStatus()).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("缺必需读取时角色没有资格作答")
    void missingRequiredToolIsRejected() {
        when(careService.list(OWNER, CareService.KIND_REPORT, PATIENT, 20)).thenReturn(List.of());
        AgentRun run = submitAndLoad("progress", List.of("memory", "pipeline", "report", "plan"));
        service.execute(run.getId());

        // 生活方式角色读不到已签发报告 → 该角色失败 → 整轮失败。
        assertThat(nodeStore.values().stream()
                .anyMatch(node -> "lifestyle_advisor".equals(node.getRole())
                        && "failed".equals(node.getStatus())))
                .isTrue();
        assertThat(runStore.get(run.getId()).getStatus()).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("运行视图能读出各角色状态与来源")
    void runViewExposesRolesAndSources() {
        AgentRun run = submitAndLoad("progress", List.of("memory", "pipeline", "report", "plan"));
        service.execute(run.getId());

        AgentCollaborationContract.CollaborationRunView view = service.get(OWNER, run.getId());
        assertThat(view.status()).isEqualTo("SUCCEEDED");
        assertThat(view.roleStatuses()).isNotEmpty();
        assertThat(view.answer()).isNotNull();
        assertThat(view.answer().sources()).isNotEmpty();
        assertThat(view.nodes()).isNotEmpty();
    }

    // ------------------------------------------------------------ 工具方法

    private Map<AgentRole, RolePlan> byRole(AgentScenario scenario, Set<AgentScope> scopes) {
        Map<AgentRole, RolePlan> byRole = new LinkedHashMap<>();
        for (RolePlan plan : AgentCollaborationContract.freezePlan(scenario, scopes)) {
            byRole.put(plan.role(), plan);
        }
        return byRole;
    }
}
