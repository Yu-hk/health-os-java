package com.kangheng.healthos.service.agent;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.kangheng.healthos.common.BizException;
import com.kangheng.healthos.contract.agent.AgentCollaborationContract.AgentScope;
import com.kangheng.healthos.contract.agent.AgentLabContract;
import com.kangheng.healthos.mapper.agent.AgentMcpReceiptMapper;
import com.kangheng.healthos.mapper.care.CareDocumentMapper;
import com.kangheng.healthos.mapper.patient.PatientMapper;
import com.kangheng.healthos.mapper.patient.PatientMemoryJobMapper;
import com.kangheng.healthos.mapper.patient.PatientMemoryStateMapper;
import com.kangheng.healthos.model.agent.AgentMcpReceipt;
import com.kangheng.healthos.model.patient.Patient;
import com.kangheng.healthos.spi.agent.McpBridgeException;
import com.kangheng.healthos.spi.agent.McpServerBridge;

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
 * Agent Lab 协议校验与回执约束。
 *
 * <p>这一组用例守的是<b>对齐原工程的几条语义</b>：
 * <ul>
 *   <li>目录是冻结的 6 工具 / 4 agent；</li>
 *   <li>serviceId 不符、跨账号患者都走 404，不泄漏"ID 存在"；</li>
 *   <li>越权工具不抛异常，仍给一条 failed 回执；</li>
 *   <li>probe 不发 tools/call 且 output 为 null；</li>
 *   <li>成功调用留 outputHash；running 超时只在视图里显示 interrupted；</li>
 *   <li>执行异常收敛成 MCP_UNAVAILABLE，不把堆栈/SQL/连接串带回执里。</li>
 * </ul>
 */
class AgentLabServiceTest {

    private static final String OWNER = "admin";
    private static final String PATIENT = "pat_1";
    private static final String SERVICE_ID = AgentLabContract.SERVICE_ID;

    private AgentMcpReceiptMapper receiptMapper;
    private PatientMapper patientMapper;
    private AgentLabService service;
    private Map<String, AgentMcpReceipt> receiptStore;

    @BeforeEach
    void setUp() {
        receiptMapper = mock(AgentMcpReceiptMapper.class);
        patientMapper = mock(PatientMapper.class);
        PatientMemoryStateMapper stateMapper = mock(PatientMemoryStateMapper.class);
        PatientMemoryJobMapper jobMapper = mock(PatientMemoryJobMapper.class);
        CareDocumentMapper careDocumentMapper = mock(CareDocumentMapper.class);
        // 用真实的工具服务（只读查询落在 mock mapper 上），这样 discover/execute 都是真代码。
        AgentLabToolService toolService = new AgentLabToolService(
                stateMapper, jobMapper, careDocumentMapper, patientMapper);

        receiptStore = new LinkedHashMap<>();
        when(receiptMapper.insert(any(AgentMcpReceipt.class))).thenAnswer(invocation -> {
            AgentMcpReceipt receipt = invocation.getArgument(0);
            receiptStore.put(receipt.getId(), receipt);
            return 1;
        });
        when(receiptMapper.updateById(any(AgentMcpReceipt.class))).thenAnswer(invocation -> {
            AgentMcpReceipt receipt = invocation.getArgument(0);
            receiptStore.put(receipt.getId(), receipt);
            return 1;
        });
        // 默认患者归属校验通过；跨账号用例单独覆盖成 null。
        when(patientMapper.selectOne(any())).thenReturn(new Patient());

        service = new AgentLabService(receiptMapper, toolService, patientMapper);
    }

    // ------------------------------------------------------------ 目录

    @Test
    @DisplayName("目录返回 6 个工具、4 个 agent，入参 schema 只允许 resource_id")
    void catalogExposesSixToolsAndFourAgents() {
        AgentLabContract.CatalogView view = service.catalog(OWNER);

        assertThat(view.catalogVersion()).isEqualTo("agent-lab-m6-v1");
        assertThat(view.serviceId()).isEqualTo("patient-business");
        assertThat(view.configuredTools()).hasSize(6);
        assertThat(view.agents()).hasSize(4);
        assertThat(view.agents()).extracting(AgentLabContract.AgentDescriptor::id)
                .containsExactly("patient_memory_manager", "pipeline_inspector",
                        "lifestyle_advisor", "progress_synthesizer");
        assertThat(view.services()).hasSize(1);
        assertThat(view.services().get(0).transport()).isEqualTo("IN_PROCESS");
        assertThat(view.services().get(0).configuredTools()).isEqualTo(6);
        assertThat(view.metrics().configuredAgents()).isEqualTo(4);
        assertThat(view.metrics().registeredTools()).isEqualTo(6);
        assertThat(view.metrics().timezone()).isEqualTo("Asia/Shanghai");

        Map<String, Object> schema = view.configuredTools().get(0).inputSchema();
        assertThat(schema.get("type")).isEqualTo("object");
        assertThat(schema.get("additionalProperties")).isEqualTo(false);
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertThat(properties.keySet()).containsExactly("resource_id");
    }

    @Test
    @DisplayName("服务状态由最近一次 probe 回执派生：not_probed/available/stale/unavailable")
    void serviceStatusDerivedFromLatestProbe() {
        assertThat(service.catalog(OWNER).services().get(0).status()).isEqualTo("not_probed");

        when(receiptMapper.selectOne(any())).thenReturn(probe("succeeded", 10));
        assertThat(service.catalog(OWNER).services().get(0).status()).isEqualTo("available");

        when(receiptMapper.selectOne(any())).thenReturn(probe("succeeded", 400));
        assertThat(service.catalog(OWNER).services().get(0).status()).isEqualTo("stale");

        when(receiptMapper.selectOne(any())).thenReturn(probe("failed", 10));
        assertThat(service.catalog(OWNER).services().get(0).status()).isEqualTo("unavailable");
    }

    // ------------------------------------------------------------ 请求级校验

    @Test
    @DisplayName("serviceId 不匹配返回 404 MCP_SERVICE_NOT_FOUND，且不落回执")
    void wrongServiceIdIsNotFound() {
        assertThatThrownBy(() -> service.probe(OWNER, "other-service",
                new AgentLabContract.ProbeRequest(PATIENT, List.of("memory"))))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> {
                    BizException biz = (BizException) ex;
                    assertThat(biz.getCode()).isEqualTo("MCP_SERVICE_NOT_FOUND");
                    assertThat(biz.getStatus().value()).isEqualTo(404);
                });
        assertThat(receiptStore).isEmpty();
    }

    @Test
    @DisplayName("跨账号患者查不到返回 404，且不落回执")
    void crossAccountPatientIsNotFound() {
        when(patientMapper.selectOne(any())).thenReturn(null);
        assertThatThrownBy(() -> service.call(OWNER, SERVICE_ID,
                new AgentLabContract.CallRequest(PATIENT, List.of("memory"),
                        "read_patient_memory", Map.of())))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getStatus().value()).isEqualTo(404));
        assertThat(receiptStore).isEmpty();
    }

    @Test
    @DisplayName("scopes 语义重复（memory 与 MEMORY）也要被拒")
    void duplicateScopesAreRejected() {
        assertThatThrownBy(() -> service.probe(OWNER, SERVICE_ID,
                new AgentLabContract.ProbeRequest(PATIENT, List.of("memory", "MEMORY"))))
                .isInstanceOf(BizException.class);
        assertThat(receiptStore).isEmpty();
    }

    // ------------------------------------------------------------ probe

    @Test
    @DisplayName("probe 只跑 initialize/tools/list，不发 tools/call，output 为 null")
    void probeSkipsToolsCallAndHasNullOutput() {
        AgentLabContract.OperationView result = service.probe(OWNER, SERVICE_ID,
                new AgentLabContract.ProbeRequest(PATIENT, List.of("memory", "pipeline")));

        assertThat(result.output()).isNull();
        assertThat(result.receipt().status()).isEqualTo("succeeded");
        assertThat(result.receipt().detail()).containsKeys(
                "protocolVersion", "serverInfo", "capabilities", "handshakeMs", "events");
        assertThat(events(result)).extracting(e -> String.valueOf(e.get("method")))
                .containsExactly("initialize", "tools/list");
        assertThat(receiptStore.values().iterator().next().getStatus()).isEqualTo("succeeded");
    }

    // ------------------------------------------------------------ call

    @Test
    @DisplayName("越权工具不抛异常，仍返回 failed 回执且 detail.error=MCP_TOOL_REJECTED")
    void unauthorizedToolReturnsFailedReceipt() {
        // 只授权 memory（白名单仅 read_patient_memory），却请求 pipeline 工具。
        AgentLabContract.OperationView result = service.call(OWNER, SERVICE_ID,
                new AgentLabContract.CallRequest(PATIENT, List.of("memory"),
                        "get_pipeline_status", Map.of()));

        assertThat(result.receipt().status()).isEqualTo("failed");
        assertThat(result.receipt().detail().get("error")).isEqualTo("MCP_TOOL_REJECTED");
        assertThat(result.output()).isNull();
        assertThat(receiptStore.values().iterator().next().getStatus()).isEqualTo("failed");
        assertThat(events(result)).anySatisfy(e -> {
            assertThat(e.get("method")).isEqualTo("tools/call");
            assertThat(e.get("status")).isEqualTo("rejected");
        });
    }

    @Test
    @DisplayName("call 成功产出非空 structuredContent，detail 带 outputHash/outputBytes")
    void successfulCallCarriesOutputHash() {
        AgentLabContract.OperationView result = service.call(OWNER, SERVICE_ID,
                new AgentLabContract.CallRequest(PATIENT, List.of("statistics"),
                        "get_patient_statistics", Map.of("resourceId", "ignored")));

        assertThat(result.receipt().status()).isEqualTo("succeeded");
        assertThat(result.output()).isInstanceOf(AgentLabContract.ToolCallOutput.class);
        assertThat(((AgentLabContract.ToolCallOutput) result.output()).structuredContent()).isNotEmpty();
        assertThat(result.receipt().detail().get("outputHash")).isInstanceOf(String.class);
        assertThat((String) result.receipt().detail().get("outputHash")).hasSize(64);
        assertThat(result.receipt().detail().get("outputBytes")).isNotNull();
        assertThat(events(result)).extracting(e -> String.valueOf(e.get("method")))
                .containsExactly("initialize", "tools/list", "tools/call");
        // 原始 arguments 不落回执（可能夹带 PII），请求侧塞 patientId 也不参与查询。
        assertThat(result.receipt().request()).doesNotContainKey("arguments");
    }

    @Test
    @DisplayName("工具执行异常收敛为 MCP_UNAVAILABLE，detail 不泄漏堆栈/SQL/连接串")
    void toolExceptionBecomesUnavailable() {
        AgentLabToolService broken = mock(AgentLabToolService.class);
        when(broken.discover(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Set<AgentScope> scopes = (Set<AgentScope>) invocation.getArgument(0);
            return AgentLabContract.authorizedToolWires(scopes);
        });
        when(broken.execute(any(), anyString(), anyString(), any()))
                .thenThrow(new IllegalStateException("jdbc:postgresql://secret/db password=xxx"));
        AgentLabService strict = new AgentLabService(receiptMapper, broken, patientMapper);

        AgentLabContract.OperationView result = strict.call(OWNER, SERVICE_ID,
                new AgentLabContract.CallRequest(PATIENT, List.of("memory"),
                        "read_patient_memory", Map.of()));

        assertThat(result.receipt().status()).isEqualTo("failed");
        assertThat(result.receipt().detail().get("error")).isEqualTo("MCP_UNAVAILABLE");
        assertThat(result.receipt().detail().toString())
                .doesNotContain("jdbc:", "password", "IllegalStateException", "xxx");
    }

    // ------------------------------------------------------------ 回执视图

    @Test
    @DisplayName("running 超过 35 秒在视图显示 interrupted，库里仍是 running")
    void staleRunningShowsInterrupted() {
        AgentMcpReceipt receipt = new AgentMcpReceipt();
        receipt.setId("amcp_test");
        receipt.setPatientId(PATIENT);
        receipt.setKind("call");
        receipt.setStatus("running");
        receipt.setCreatedAt(OffsetDateTime.now().minusSeconds(40));
        receipt.setRequest("{}");
        receipt.setDetail("{}");

        AgentLabContract.ReceiptView view = service.receiptView(receipt);
        assertThat(view.status()).isEqualTo("interrupted");
        assertThat(view.completedAt()).isNull();
        // 视图派生不回写库：running 仍是 running。
        assertThat(receipt.getStatus()).isEqualTo("running");

        receipt.setCreatedAt(OffsetDateTime.now());
        assertThat(service.receiptView(receipt).status()).isEqualTo("running");
    }

    // ------------------------------------------------------------ 传输层可替换

    @Test
    @DisplayName("服务端声明多于授权集 → AGENT_DISCOVERY_MISMATCH，detail 记 missing/extra")
    void discoveryMismatchIsReported() {
        McpServerBridge bridge = mock(McpServerBridge.class);
        when(bridge.transport()).thenReturn("in-process");
        // 服务端"多报"了一个范围外的工具：这正是漂移要被抓住的形态
        when(bridge.handshake(any(), anyString(), anyString())).thenReturn(
                new McpServerBridge.Handshake("2024-11-05", "patient-business", "agent-lab-m6-v1",
                        List.of("read_patient_memory", "get_pipeline_status"), 12L, 3L));

        AgentLabContract.OperationView result = withBridge(bridge).probe(OWNER, SERVICE_ID,
                new AgentLabContract.ProbeRequest(PATIENT, List.of("memory")));

        assertThat(result.receipt().status()).isEqualTo("failed");
        assertThat(result.receipt().detail().get("error")).isEqualTo("AGENT_DISCOVERY_MISMATCH");
        assertThat(result.receipt().detail().get("extraTools")).isEqualTo(List.of("get_pipeline_status"));
        assertThat(result.receipt().detail().get("missingTools")).isEqualTo(List.of());
        // 握手耗时与 initialize 耗时都取自握手结果，不自己编
        // （detail 落库前会过一遍 JSON，数字回来是 Integer，用 longValue 比较）
        assertThat(((Number) result.receipt().detail().get("handshakeMs")).longValue()).isEqualTo(12L);
        assertThat(events(result).get(0)).containsEntry("method", "initialize");
        assertThat(((Number) events(result).get(0).get("durationMs")).longValue()).isEqualTo(3L);
        assertThat(events(result).get(1)).containsEntry("method", "tools/list")
                .containsEntry("status", "failed");
    }

    @Test
    @DisplayName("tools/list 阶段失败：initialize 仍记 ok，对外错误码收敛为 MCP_UNAVAILABLE")
    void handshakeFailureKeepsStageAttribution() {
        McpServerBridge bridge = mock(McpServerBridge.class);
        when(bridge.transport()).thenReturn("stdio");
        when(bridge.handshake(any(), anyString(), anyString()))
                .thenThrow(new McpBridgeException("MCP_TIMEOUT", "20s 内没有响应", "tools/list", null));

        AgentLabContract.OperationView result = withBridge(bridge).probe(OWNER, SERVICE_ID,
                new AgentLabContract.ProbeRequest(PATIENT, List.of("memory")));

        assertThat(result.receipt().status()).isEqualTo("failed");
        assertThat(result.receipt().detail().get("error")).isEqualTo("MCP_UNAVAILABLE");
        assertThat(result.receipt().detail().get("failedAt")).isEqualTo("tools/list");
        assertThat(result.receipt().detail()).containsKey("handshakeMs");
        assertThat(events(result)).extracting(e -> String.valueOf(e.get("method")))
                .containsExactly("initialize", "tools/list");
        assertThat(events(result).get(0)).containsEntry("status", "ok");
        assertThat(events(result).get(1)).containsEntry("status", "error");
        // 失败原因只留在日志，回执里不留子进程原话
        assertThat(result.receipt().detail().toString()).doesNotContain("20s 内没有响应");
    }

    @Test
    @DisplayName("换成 stdio 形态：目录显示 STDIO，call 走 bridge.callTool")
    void catalogShowsConfiguredTransportAndCallUsesBridge() {
        McpServerBridge bridge = mock(McpServerBridge.class);
        when(bridge.transport()).thenReturn("stdio");
        when(bridge.handshake(any(), anyString(), anyString())).thenReturn(
                new McpServerBridge.Handshake("2024-11-05", "patient-business", "agent-lab-m6-v1",
                        List.of("read_patient_memory"), 40L, 25L));
        when(bridge.callTool(any(), anyString(), anyString(), anyString(), any()))
                .thenReturn(Map.of("status", "available"));

        AgentLabService withBridge = withBridge(bridge);
        assertThat(withBridge.catalog(OWNER).services().get(0).transport()).isEqualTo("STDIO");

        AgentLabContract.OperationView result = withBridge.call(OWNER, SERVICE_ID,
                new AgentLabContract.CallRequest(PATIENT, List.of("memory"),
                        "read_patient_memory", Map.of()));

        assertThat(result.receipt().status()).isEqualTo("succeeded");
        assertThat(result.receipt().detail().get("transport")).isEqualTo("stdio");
        assertThat(events(result)).extracting(e -> String.valueOf(e.get("method")))
                .containsExactly("initialize", "tools/list", "tools/call");
        assertThat(result.output()).isInstanceOf(AgentLabContract.ToolCallOutput.class);
    }

    // ------------------------------------------------------------ 夹具

    /** 组装一个"传输层可替换"的 AgentLabService；工具服务在 4 参构造里不参与编排。 */
    private AgentLabService withBridge(McpServerBridge bridge) {
        return new AgentLabService(receiptMapper, mock(AgentLabToolService.class), patientMapper, bridge);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> events(AgentLabContract.OperationView result) {
        return (List<Map<String, Object>>) result.receipt().detail().get("events");
    }

    private static AgentMcpReceipt probe(String status, long ageSeconds) {
        AgentMcpReceipt receipt = new AgentMcpReceipt();
        receipt.setKind("probe");
        receipt.setStatus(status);
        receipt.setCreatedAt(OffsetDateTime.now().minusSeconds(ageSeconds));
        receipt.setCompletedAt(OffsetDateTime.now().minusSeconds(ageSeconds));
        return receipt;
    }
}
