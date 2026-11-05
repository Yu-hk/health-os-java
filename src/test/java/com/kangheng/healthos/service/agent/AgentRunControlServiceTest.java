package com.kangheng.healthos.service.agent;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.kangheng.healthos.common.AccessControl;
import com.kangheng.healthos.common.BizException;
import com.kangheng.healthos.contract.agent.AgentRunContract;
import com.kangheng.healthos.mapper.agent.AgentNodeMapper;
import com.kangheng.healthos.mapper.agent.AgentRunMapper;
import com.kangheng.healthos.model.agent.AgentNode;
import com.kangheng.healthos.model.agent.AgentRun;
import com.kangheng.healthos.service.outbox.OutboxService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Agent Run 控制面：取消 / 重试 / 节点正文 / 列表的状态机约束。 */
class AgentRunControlServiceTest {

    private static final String OWNER = "admin";
    private static final String RUN = "arun_1";

    private AgentRunMapper runMapper;
    private AgentNodeMapper nodeMapper;
    private AgentRunEventService eventService;
    private OutboxService outboxService;
    private AccessControl accessControl;
    private AgentRunControlService service;
    private Map<String, AgentRun> runStore;
    private Map<String, AgentNode> nodeStore;
    private List<String> emitted;

    @BeforeEach
    void setUp() {
        runMapper = mock(AgentRunMapper.class);
        nodeMapper = mock(AgentNodeMapper.class);
        eventService = mock(AgentRunEventService.class);
        outboxService = mock(OutboxService.class);
        accessControl = mock(AccessControl.class);
        service = new AgentRunControlService(runMapper, nodeMapper, eventService,
                outboxService, accessControl);

        runStore = new LinkedHashMap<>();
        nodeStore = new LinkedHashMap<>();
        emitted = new ArrayList<>();

        AgentRun run = new AgentRun();
        run.setId(RUN);
        run.setOwner(OWNER);
        run.setPatientId("pat_1");
        run.setScenario("progress");
        run.setStatus("QUEUED");
        run.setEventSeq(3);
        run.setVersion(2);
        run.setCreatedAt(OffsetDateTime.now());
        runStore.put(RUN, run);

        when(eventService.owned(anyString(), anyString()))
                .thenAnswer(invocation -> {
                    String owner = invocation.getArgument(0);
                    AgentRun row = runStore.get(invocation.getArgument(1));
                    if (row == null || !OWNER.equals(owner)) {
                        throw BizException.notFound("协同运行");
                    }
                    return row;
                });
        when(eventService.emit(anyString(), anyString(), any(), anyString(), any()))
                .thenAnswer(invocation -> {
                    emitted.add(invocation.getArgument(1));
                    return null;
                });
        when(runMapper.updateById(any(AgentRun.class))).thenReturn(1);
        when(runMapper.update(any(), any())).thenReturn(1);
        when(nodeMapper.selectById(anyString()))
                .thenAnswer(invocation -> nodeStore.get(invocation.getArgument(0)));
        when(nodeMapper.selectList(any())).thenAnswer(invocation -> new ArrayList<>(nodeStore.values()));
        when(nodeMapper.updateById(any(AgentNode.class))).thenReturn(1);
        when(runMapper.selectList(any())).thenAnswer(invocation -> new ArrayList<>(runStore.values()));
    }

    private AgentNode node(String id, String status, OffsetDateTime createdAt) {
        AgentNode node = new AgentNode();
        node.setId(id);
        node.setRunId(RUN);
        node.setRole("patient_memory_manager");
        node.setKind("role");
        node.setName("patient_memory_manager");
        node.setStatus(status);
        node.setOutput("{\"role_id\":\"patient_memory_manager\",\"answer\":{\"summary\":\"结论\"},"
                + "\"api_key\":\"不该外泄\"}");
        node.setCreatedAt(createdAt);
        nodeStore.put(id, node);
        return node;
    }

    @Test
    @DisplayName("取消排队运行：置终态、版本递增、running 节点一起取消并发事件")
    void cancelQueuedRun() {
        node("anode_1", "running", OffsetDateTime.now());
        node("anode_2", "succeeded", OffsetDateTime.now());

        AgentRunContract.ControlView view = service.cancel(OWNER, RUN, 2);

        assertThat(view.status()).isEqualTo("CANCELLED");
        assertThat(view.version()).isEqualTo(3);
        assertThat(view.idempotent()).isFalse();
        assertThat(runStore.get(RUN).getError()).isEqualTo("AGENT_CANCELLED");
        assertThat(nodeStore.get("anode_1").getStatus()).isEqualTo("cancelled");
        assertThat(nodeStore.get("anode_2").getStatus()).isEqualTo("succeeded");
        assertThat(emitted).contains(AgentRunContract.NODE_CANCELLED,
                AgentRunContract.RUN_CANCELLED);
    }

    @Test
    @DisplayName("终态取消幂等：不重复写事件、不再改版本")
    void cancelIsIdempotent() {
        runStore.get(RUN).setStatus("SUCCEEDED");
        AgentRunContract.ControlView view = service.cancel(OWNER, RUN, 2);
        assertThat(view.idempotent()).isTrue();
        assertThat(view.version()).isEqualTo(2);
        assertThat(emitted).isEmpty();
        verify(outboxService, never()).append(anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("版本落后于服务端时取消被拒（409）")
    void staleVersionRejected() {
        assertThatThrownBy(() -> service.cancel(OWNER, RUN, 1))
                .isInstanceOfSatisfying(BizException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(AgentRunContract.ERROR_VERSION_CONFLICT));
        assertThat(runStore.get(RUN).getStatus()).isEqualTo("QUEUED");
    }

    @Test
    @DisplayName("重试失败运行：退回 QUEUED、清错误、重新派发、发 retry 事件")
    void retryFailedRun() {
        AgentRun run = runStore.get(RUN);
        run.setStatus("FAILED");
        run.setError("AGENT_REQUIRED_ROLE_FAILED");
        run.setResult("{\"answer\":{}}");
        run.setCompletedAt(OffsetDateTime.now());

        AgentRunContract.ControlView view = service.retry(OWNER, RUN, 2);

        assertThat(view.status()).isEqualTo("QUEUED");
        assertThat(view.version()).isEqualTo(3);
        assertThat(run.getError()).isNull();
        assertThat(run.getResult()).isNull();
        assertThat(run.getCompletedAt()).isNull();
        assertThat(run.getDeadlineAt()).isAfter(OffsetDateTime.now());
        assertThat(emitted).contains(AgentRunContract.RUN_RETRY_REQUESTED);
        verify(outboxService).append(eq("agent_run"), eq(RUN),
                eq(AgentCollaborationService.EVENT_EXECUTE), any());
    }

    @Test
    @DisplayName("成功/取消的运行不可重试")
    void terminalRunCannotRetry() {
        runStore.get(RUN).setStatus("SUCCEEDED");
        assertThatThrownBy(() -> service.retry(OWNER, RUN, 2))
                .isInstanceOfSatisfying(BizException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(AgentRunContract.ERROR_RUN_STATE));
        verify(outboxService, never()).append(anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("排队/执行中的重试视为响应丢失对账，幂等返回且不重复排队")
    void retryInFlightIsIdempotent() {
        AgentRunContract.ControlView view = service.retry(OWNER, RUN, 2);
        assertThat(view.idempotent()).isTrue();
        assertThat(view.version()).isEqualTo(2);
        verify(outboxService, never()).append(anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("节点正文默认脱敏；full 需要全局读权限并落审计事件")
    void nodePayloadRedactionAndAudit() {
        AgentNode fresh = node("anode_1", "succeeded", OffsetDateTime.now());
        AgentRunContract.NodePayloadView redacted =
                service.nodePayload(OWNER, RUN, "anode_1", "redacted");
        assertThat(redacted.mode()).isEqualTo("redacted");
        assertThat(String.valueOf(redacted.output())).contains("正文按权限隐藏")
                .doesNotContain("不该外泄");

        assertThatThrownBy(() -> service.nodePayload(OWNER, RUN, "anode_1", "full"))
                .isInstanceOfSatisfying(BizException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(AgentRunContract.ERROR_PAYLOAD_FORBIDDEN));

        when(accessControl.isTraceGlobalReader(OWNER)).thenReturn(true);
        AgentRunContract.NodePayloadView full =
                service.nodePayload(OWNER, RUN, "anode_1", "full");
        assertThat(full.canReadFull()).isTrue();
        assertThat(String.valueOf(full.output())).contains("<hidden>")
                .doesNotContain("不该外泄");
        assertThat(emitted).contains(AgentRunContract.NODE_PAYLOAD_READ);
        assertThat(fresh.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("超过 7 天保留期的 full 读取返回 410")
    void expiredPayloadRejected() {
        node("anode_1", "succeeded", OffsetDateTime.now().minusDays(8));
        when(accessControl.isAdmin(OWNER)).thenReturn(true);
        assertThatThrownBy(() -> service.nodePayload(OWNER, RUN, "anode_1", "full"))
                .isInstanceOfSatisfying(BizException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(AgentRunContract.ERROR_PAYLOAD_EXPIRED));
    }

    @Test
    @DisplayName("运行列表只列自己的 run，键集分页返回游标")
    void pageAndCursor() {
        AgentRunContract.RunPage page = service.page(OWNER, null, 1, null);
        assertThat(page.items()).hasSize(1);
        assertThat(page.nextCursor()).isNull();

        AgentRun second = new AgentRun();
        second.setId("arun_2");
        second.setOwner(OWNER);
        second.setPatientId("pat_1");
        second.setStatus("QUEUED");
        second.setCreatedAt(OffsetDateTime.now().minusMinutes(1));
        runStore.put(second.getId(), second);

        AgentRunContract.RunPage paged = service.page(OWNER, null, 1, null);
        assertThat(paged.items()).hasSize(1);
        assertThat(paged.nextCursor()).isNotNull();
        assertThat(service.page(OWNER, null, 20, paged.nextCursor()).items()).isNotEmpty();
    }

    @Test
    @DisplayName("跨账号控制返回 404")
    void ownerIsolation() {
        assertThatThrownBy(() -> service.cancel("clinician", RUN, null))
                .isInstanceOf(BizException.class);
        assertThat(runStore.get(RUN).getStatus()).isEqualTo("QUEUED");
    }
}
