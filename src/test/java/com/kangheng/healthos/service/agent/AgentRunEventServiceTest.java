package com.kangheng.healthos.service.agent;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.kangheng.healthos.common.BizException;
import com.kangheng.healthos.contract.agent.AgentRunContract;
import com.kangheng.healthos.mapper.agent.AgentRunEventMapper;
import com.kangheng.healthos.mapper.agent.AgentRunMapper;
import com.kangheng.healthos.model.agent.AgentRun;
import com.kangheng.healthos.model.agent.AgentRunEvent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Agent Run 事件序号与增量查询的约束。 */
class AgentRunEventServiceTest {

    private static final String OWNER = "admin";
    private static final String RUN = "arun_1";

    private AgentRunMapper runMapper;
    private AgentRunEventMapper eventMapper;
    private AgentRunEventService service;
    private Map<String, AgentRun> runStore;
    private List<AgentRunEvent> events;

    @BeforeEach
    void setUp() {
        runMapper = mock(AgentRunMapper.class);
        eventMapper = mock(AgentRunEventMapper.class);
        service = new AgentRunEventService(runMapper, eventMapper);
        runStore = new LinkedHashMap<>();
        events = new ArrayList<>();

        AgentRun run = new AgentRun();
        run.setId(RUN);
        run.setOwner(OWNER);
        run.setStatus("RUNNING");
        run.setEventSeq(0);
        run.setVersion(1);
        run.setCreatedAt(OffsetDateTime.now());
        runStore.put(RUN, run);

        when(runMapper.selectById(anyString()))
                .thenAnswer(invocation -> runStore.get(invocation.getArgument(0)));
        when(eventMapper.nextSeq(anyString())).thenAnswer(invocation -> {
            AgentRun row = runStore.get(invocation.getArgument(0));
            if (row == null) {
                return null;
            }
            row.setEventSeq(AgentRunEventService.seqOf(row) + 1);
            return row.getEventSeq();
        });
        when(eventMapper.insert(any(AgentRunEvent.class))).thenAnswer(invocation -> {
            events.add(invocation.getArgument(0));
            return 1;
        });
        when(eventMapper.listAfter(anyString(), anyInt(), anyInt())).thenAnswer(invocation -> {
            int after = invocation.getArgument(1);
            int limit = invocation.getArgument(2);
            return events.stream()
                    .filter(event -> event.getSeq() > after)
                    .limit(limit)
                    .toList();
        });
    }

    @Test
    @DisplayName("事件序号连续递增，且与 run.event_seq 同步")
    void seqIsMonotonic() {
        AgentRunContract.EventPage empty = service.events(OWNER, RUN, 0, 10);
        assertThat(empty.items()).isEmpty();
        assertThat(empty.nextSeq()).isZero();

        service.emit(RUN, AgentRunContract.RUN_STARTED, null, "RUNNING", Map.of("attempt", "outbox"));
        service.emit(RUN, AgentRunContract.NODE_COMPLETED, "anode_1", "succeeded",
                Map.of("nodeId", "anode_1"));

        assertThat(events).extracting(AgentRunEvent::getSeq).containsExactly(1, 2);
        AgentRunContract.EventPage page = service.events(OWNER, RUN, 0, 10);
        assertThat(page.items()).extracting(AgentRunContract.EventView::seq).containsExactly(1, 2);
        assertThat(page.items()).extracting(AgentRunContract.EventView::type)
                .containsExactly(AgentRunContract.RUN_STARTED, AgentRunContract.NODE_COMPLETED);
        assertThat(page.eventSeq()).isEqualTo(2);
        assertThat(page.status()).isEqualTo("RUNNING");
    }

    @Test
    @DisplayName("after_seq 增量拉取；limit 只返回本页，hasMore 靠多取一条判定")
    void incrementalPage() {
        for (int i = 1; i <= 5; i++) {
            service.emit(RUN, AgentRunContract.NODE_COMPLETED, "anode_" + i, "succeeded", Map.of());
        }
        AgentRunContract.EventPage first = service.events(OWNER, RUN, 0, 2);
        assertThat(first.items()).hasSize(2);
        assertThat(first.nextSeq()).isEqualTo(2);
        assertThat(first.hasMore()).isTrue();

        AgentRunContract.EventPage second = service.events(OWNER, RUN, first.nextSeq(), 2);
        assertThat(second.items()).extracting(AgentRunContract.EventView::seq).containsExactly(3, 4);
        assertThat(second.hasMore()).isTrue();

        AgentRunContract.EventPage last = service.events(OWNER, RUN, 5, 2);
        assertThat(last.items()).isEmpty();
        assertThat(last.nextSeq()).isEqualTo(5);
        assertThat(last.hasMore()).isFalse();
    }

    @Test
    @DisplayName("limit 越界被夹到 1..200，负数 after_seq 按 0 处理")
    void clampBounds() {
        service.emit(RUN, AgentRunContract.RUN_QUEUED, null, "QUEUED", Map.of());
        assertThat(service.events(OWNER, RUN, -8, 9999).items()).hasSize(1);
        assertThat(service.events(OWNER, RUN, 0, -5).items()).hasSize(1);
    }

    @Test
    @DisplayName("事件 detail 只放元数据，不把业务正文带进事件表")
    void detailIsMetadataOnly() {
        service.emit(RUN, AgentRunContract.NODE_COMPLETED, "anode_1", "succeeded",
                Map.of("nodeId", "anode_1", "kind", "role"));
        String detail = events.get(0).getDetail();
        assertThat(detail).contains("nodeId").doesNotContain("summary", "content", "api_key");
        // 事件视图里没有 detail 字段：正文必须走节点详情端点。
        assertThat(AgentRunContract.EventView.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("seq", "type", "nodeId", "status", "createdAt");
    }

    @Test
    @DisplayName("跨账号读事件返回 404，不泄漏运行是否存在")
    void ownerIsolation() {
        assertThatThrownBy(() -> service.events("clinician", RUN, 0, 10))
                .isInstanceOf(BizException.class)
                .satisfies(error -> assertThat(((BizException) error).getCode()).isEqualTo("NOT_FOUND"));
        assertThatThrownBy(() -> service.owned(OWNER, "arun_missing"))
                .isInstanceOf(BizException.class);
    }
}
