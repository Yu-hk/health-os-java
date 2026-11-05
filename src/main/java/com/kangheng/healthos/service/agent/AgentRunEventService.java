package com.kangheng.healthos.service.agent;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.kangheng.healthos.common.BizException;
import com.kangheng.healthos.common.Ids;
import com.kangheng.healthos.common.Json;
import com.kangheng.healthos.contract.agent.AgentRunContract;
import com.kangheng.healthos.mapper.agent.AgentRunEventMapper;
import com.kangheng.healthos.mapper.agent.AgentRunMapper;
import com.kangheng.healthos.model.agent.AgentRun;
import com.kangheng.healthos.model.agent.AgentRunEvent;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Agent Run 的唯一事件写入口与增量查询入口。
 *
 * <p><b>为什么要集中到一个入口</b>：事件序号必须与状态变更在同一事务里分配并提交。
 * 如果各处自己 insert，迟早会出现「状态已经 SUCCEEDED，但最后一轮事件没落库」的窗口，
 * 轮询客户端就会永远停不下来。
 *
 * <p>对齐原工程 {@code agent_run_service.emit}：事件类型与节点状态一起记，
 * detail 只放元数据，正文留在节点里。
 */
@Service
public class AgentRunEventService {

    private final AgentRunMapper runMapper;
    private final AgentRunEventMapper eventMapper;

    public AgentRunEventService(AgentRunMapper runMapper, AgentRunEventMapper eventMapper) {
        this.runMapper = runMapper;
        this.eventMapper = eventMapper;
    }

    /** 归属校验：查不到、或不是自己的 run，一律 404（不泄漏资源是否存在）。 */
    public AgentRun owned(String owner, String runId) {
        AgentRun run = runMapper.selectById(runId);
        if (run == null || !Objects.equals(run.getOwner(), owner)) {
            throw BizException.notFound("协同运行");
        }
        return run;
    }

    /**
     * 写一条事件。
     *
     * <p>seq 用 {@code UPDATE ... RETURNING} 原子分配；detail 里绝不写模型输出、
     * 凭据或患者正文，只写可用于定位的节点元数据。
     */
    @Transactional
    public AgentRunEvent emit(String runId, String type, String nodeId, String status,
                              Map<String, Object> detail) {
        Integer seq = eventMapper.nextSeq(runId);
        if (seq == null) {
            throw BizException.notFound("协同运行");
        }
        AgentRunEvent event = new AgentRunEvent();
        event.setId(Ids.newId("arev"));
        event.setRunId(runId);
        event.setSeq(seq);
        event.setType(type);
        event.setNodeId(nodeId);
        event.setStatus(status);
        event.setDetail(Json.write(detail == null ? Map.of() : detail));
        event.setCreatedAt(OffsetDateTime.now());
        eventMapper.insert(event);
        return event;
    }

    /** 当前 run 的事件总序号。 */
    public int currentSeq(String owner, String runId) {
        return seqOf(owned(owner, runId));
    }

    /**
     * 增量事件页。
     *
     * <p>多取一条用于判 {@code hasMore}；返回给客户端的 items 严格不超过 limit。
     * {@code nextSeq} 无数据时回显 afterSeq，客户端可以直接拿它继续轮询。
     */
    public AgentRunContract.EventPage events(String owner, String runId, int afterSeq, int limit) {
        AgentRun run = owned(owner, runId);
        int safeAfter = Math.max(0, afterSeq);
        int safeLimit = Math.min(200, Math.max(1, limit));
        List<AgentRunEvent> rows = eventMapper.listAfter(runId, safeAfter, safeLimit + 1);
        if (rows == null) {
            rows = List.of();
        }
        boolean hasMore = rows.size() > safeLimit;
        List<AgentRunEvent> page = hasMore ? new ArrayList<>(rows.subList(0, safeLimit)) : rows;
        List<AgentRunContract.EventView> items = page.stream()
                .map(row -> new AgentRunContract.EventView(row.getSeq(), row.getType(),
                        row.getNodeId(), row.getStatus(), String.valueOf(row.getCreatedAt())))
                .toList();
        int nextSeq = page.isEmpty() ? safeAfter : page.get(page.size() - 1).getSeq();
        return new AgentRunContract.EventPage(runId, items, nextSeq, hasMore,
                seqOf(run), run.getStatus());
    }

    /** 旧行的 event_seq 可能为 null（加列前），按 0 处理。 */
    public static int seqOf(AgentRun run) {
        return run.getEventSeq() == null ? 0 : run.getEventSeq();
    }

    /** 旧行的 version 可能为 null（加列前），按 1 处理。 */
    public static int versionOf(AgentRun run) {
        return run.getVersion() == null ? 1 : run.getVersion();
    }
}
