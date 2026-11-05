package com.kangheng.healthos.service.patient;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import com.kangheng.healthos.common.AccessControl;
import com.kangheng.healthos.common.BizException;
import com.kangheng.healthos.common.Ids;
import com.kangheng.healthos.common.Json;
import com.kangheng.healthos.contract.patient.MemoryAgentNoteContract;
import com.kangheng.healthos.contract.patient.PatientMemoryContract;
import com.kangheng.healthos.mapper.patient.MemoryAgentNoteMapper;
import com.kangheng.healthos.mapper.patient.PatientFollowupMapper;
import com.kangheng.healthos.mapper.patient.PatientMapper;
import com.kangheng.healthos.model.patient.MemoryAgentNote;
import com.kangheng.healthos.model.patient.Patient;
import com.kangheng.healthos.model.patient.PatientFollowup;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 记忆 Agent 摘录写入（对齐原工程 {@code memory_agent_service.py} 的收敛版）。
 *
 * <p>Java 侧<b>不启 STDIO 子进程</b>：候选摘录由本服务在进程内生成，状态先落 PENDING，
 * <b>不自动写记忆版本</b>——对齐原工程"显式授权 + 候选由独立 worker 发布"的分工，
 * 也避免把患者自述原文悄悄升级成已核验的医学事实。
 *
 * <p>三处"为什么"必须留着，防 regress：
 * <ol>
 *   <li><b>observations 是来源快照 JSON 文档而非裸数组</b>：文档里 {@code observations}
 *       成员是 {@code [{entryIndex,quote}]} 数组（对外 API 只返回这个数组，满足"observations
 *       为数组"），同时冻结 {@code entries}（随访原文）、{@code memoryVersionId}、
 *       {@code agentNotes[]}。规格要求把 entries 与发布时追加的 agent_notes 都放进
 *       "observations 来源快照 JSON 列"，而给定 DDL 没有独立的快照列——塞进同一列才能让
 *       候选、原文、发布凭据随同一行原子落库，且不新增 DDL 列。</li>
 *   <li><b>quote 逐字校验</b>：候选由"截每条 content 前 N 字符"生成，天然是连续子串，
 *       但仍统一过 {@code assertValidObservations} 守卫（entryIndex 集合 == 0..size-1
 *       且 {@code content.contains(quote)}）。发布时再跑一遍，防止候选被改过或来源漂移。</li>
 *   <li><b>发布不改正式记忆版本</b>：见 {@link #publish} 注释。发布凭据落在 note 表内。</li>
 * </ol>
 *
 * <p>所有入口先校验患者归属，查不到 404（不是 403）；患者域逐表带 owner 隔离。
 */
@Service
public class MemoryAgentNoteService {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_PUBLISHED = "PUBLISHED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    public static final String ERROR_EVIDENCE_INVALID = "MEMORY_AGENT_EVIDENCE_INVALID";
    public static final String ERROR_CANDIDATE_CONFLICT = "CANDIDATE_CONFLICT";
    public static final String ERROR_SOURCE_CHANGED = "MEMORY_AGENT_SOURCE_CHANGED";
    public static final String ERROR_NOTE_NOT_FOUND = "MEMORY_AGENT_NOTE_NOT_FOUND";
    public static final String ERROR_STATE = "MEMORY_AGENT_STATE";

    /** 每条摘录取 content 的前 N 字符，保证是连续原文（contains 成立）。 */
    private static final int QUOTE_CHARS = 40;

    // 来源快照 JSON 文档的 key
    private static final String KEY_OBSERVATIONS = "observations";
    private static final String KEY_ENTRIES = "entries";
    private static final String KEY_MEMORY_VERSION_ID = "memoryVersionId";
    private static final String KEY_INSTRUCTION = "instruction";
    private static final String KEY_AGENT_NOTES = "agentNotes";

    private final MemoryAgentNoteMapper noteMapper;
    private final PatientMapper patientMapper;
    private final PatientFollowupMapper followupMapper;
    /** 复用其公开方法 currentId(patientId) 读取当前记忆版本 id；发布不调用 publish()，理由见 publish 注释。 */
    private final PatientMemoryService memoryService;

    public MemoryAgentNoteService(MemoryAgentNoteMapper noteMapper,
                                  PatientMapper patientMapper,
                                  PatientFollowupMapper followupMapper,
                                  PatientMemoryService memoryService) {
        this.noteMapper = noteMapper;
        this.patientMapper = patientMapper;
        this.followupMapper = followupMapper;
        this.memoryService = memoryService;
    }

    // ------------------------------------------------------------ 提交（202）

    /**
     * 生成摘录候选并落 PENDING。
     *
     * <p>幂等：{@code (owner, patientId, operationId)} 唯一，命中直接返回既有视图（不重复发布）。
     * 优先 {@code followupId} 从既有 patient_followup 读 entries；无 followupId 时收
     * {@code entries} 并<b>冻结进 note 的来源快照 JSON</b>。
     *
     * <p><b>为什么不复用既有随访创建方法</b>：PatientMemoryService 根本没有公开的随访创建方法；
     * 随访创建在 PatientMemoryJobService.submitFollowup，签名要求 operationId(8-64 位)、
     * expectedMemoryId、occurredAt 与 FollowupRequest，且会向 outbox 投递一条 publish 事件、
     * 由 worker 生成新记忆版本——这与"状态 PENDING、不自动写记忆版本"直接冲突，也会把本
     * 候选绑进记忆刷新流水线。签名/行为都不适合直接调用，故按规格的退路：把 entries 存进
     * 本次 note 的来源快照 JSON 列，并在上面类注释里说明。
     */
    @Transactional
    public MemoryAgentNoteContract.NoteView submit(String owner, String patientId,
                                                  MemoryAgentNoteContract.AgentFollowupRequest request) {
        requireOwner(owner, patientId);
        validateRequest(request);

        MemoryAgentNote existing = findByOperation(owner, patientId, request.operationId());
        if (existing != null) {
            return view(existing);  // 幂等：不重复生成、不重复发布
        }

        List<MemoryAgentNoteContract.AgentEntry> entries;
        String feedbackId = null;
        if (StringUtils.hasText(request.followupId())) {
            feedbackId = request.followupId();
            entries = readFollowupEntries(owner, patientId, feedbackId);
        } else {
            entries = validateEntries(request.entries());
        }

        List<MemoryAgentNoteContract.Observation> observations = buildCandidate(entries);
        // 生成后仍过一遍守卫：候选必须覆盖全部分类、逐字摘录（防"生成器以后改坏"的 regress）
        assertValidObservations(entries, observations);

        String memoryVersionId = memoryService.currentId(patientId);
        String candidateHash = Ids.sha256Hex(Json.write(observations));
        String sourceHash = Ids.sha256Hex(Json.write(sourceHashBody(entries, memoryVersionId)));

        Map<String, Object> doc = newDoc(entries, memoryVersionId, request.instruction(), observations);

        MemoryAgentNote note = new MemoryAgentNote();
        note.setId(Ids.newId("mnote"));
        note.setOwner(owner);
        note.setPatientId(patientId);
        note.setFeedbackId(feedbackId);
        note.setOperationId(request.operationId());
        note.setStatus(STATUS_PENDING);  // 不自动写记忆版本
        note.setObservations(Json.write(doc));
        note.setCandidateHash(candidateHash);
        note.setSourceHash(sourceHash);
        // jobId / modelNodeId 保持 null：收敛版进程内生成、无 STDIO 子进程、无独立 worker/模型节点
        note.setCreatedAt(OffsetDateTime.now());
        noteMapper.insert(note);
        return view(note);
    }

    // ------------------------------------------------------------ 列表

    /** GET：按 owner + patient 过滤，createdAt 倒序。 */
    public MemoryAgentNoteContract.NoteListView list(String owner, String patientId) {
        requireOwner(owner, patientId);
        List<MemoryAgentNote> rows = noteMapper.selectList(new LambdaQueryWrapper<MemoryAgentNote>()
                .eq(MemoryAgentNote::getOwner, owner)
                .eq(MemoryAgentNote::getPatientId, patientId)
                .orderByDesc(MemoryAgentNote::getCreatedAt));
        return new MemoryAgentNoteContract.NoteListView(rows.stream().map(this::view).toList());
    }

    // ------------------------------------------------------------ 发布

    /**
     * 发布一条 PENDING 候选。
     *
     * <p><b>发布在 note 表内完成，不改动正式记忆版本，发布凭据落 note 表</b>。
     * 为什么不复用 PatientMemoryService.publish() / PatientMemoryJobService 的公开方法生成
     * 新记忆版本：publish() 会经 MemorySourceService.capture() 从"已发布修订 / 指标当前态 /
     * 照护文档 / 随访"重新采样一份全新记忆，既没有注入任意 {@code agent_notes} 的钩子，
     * 又会推进版本计数器、移动指针——那等于把 Agent 摘录悄悄混进正式记忆，且与
     * "候选由独立 worker 发布"的分工相悖。submitRefresh/submitFollowup 同样会投递 outbox
     * 触发 publish。因此签名/语义都不适合直接调用，改为在 note 表内完成发布语义：
     * <ul>
     *   <li>{@code memory_id} = 当前记忆版本 id（发布凭据，不是新版本）；</li>
     *   <li>把 {@code agent_notes} 按 noteId 去重追加到 note 的来源快照 JSON；</li>
     *   <li>状态置 PUBLISHED、写 published_at。</li>
     * </ul>
     *
     * <p>发布前的四道校验：candidateHash 一致（否则 CANDIDATE_CONFLICT）、状态 PENDING、
     * 逐字摘录仍成立（否则 MEMORY_AGENT_EVIDENCE_INVALID）、sourceHash 未变
     * （否则 MEMORY_AGENT_SOURCE_CHANGED）。已 PUBLISHED 且 hash 相同直接幂等返回。
     */
    @Transactional
    public MemoryAgentNoteContract.NoteView publish(String owner, String patientId, String noteId,
                                                    MemoryAgentNoteContract.PublishRequest request) {
        requireOwner(owner, patientId);
        if (request == null || !StringUtils.hasText(request.expectedCandidateHash())) {
            throw BizException.badRequest("expectedCandidateHash 必填");
        }
        MemoryAgentNote note = findNote(owner, patientId, noteId);
        if (note == null) {
            throw new BizException(ERROR_NOTE_NOT_FOUND, "摘录记录不存在: " + noteId,
                    HttpStatus.NOT_FOUND);
        }

        // 幂等：已 PUBLISHED 且 hash 相同 → 直接返回既有视图
        if (STATUS_PUBLISHED.equals(note.getStatus())
                && note.getCandidateHash().equals(request.expectedCandidateHash())) {
            return view(note);
        }
        // hash 一致：防止基于过期候选发布
        if (!note.getCandidateHash().equals(request.expectedCandidateHash())) {
            throw new BizException(ERROR_CANDIDATE_CONFLICT,
                    "候选摘要不一致，可能已刷新，请重新生成候选", HttpStatus.CONFLICT);
        }
        // 状态必须是 PENDING
        if (!STATUS_PENDING.equals(note.getStatus())) {
            throw new BizException(ERROR_STATE,
                    "当前状态 " + note.getStatus() + " 不能发布", HttpStatus.CONFLICT);
        }

        Map<String, Object> doc = readDoc(note.getObservations());
        List<MemoryAgentNoteContract.AgentEntry> sourceEntries =
                resolveSourceEntries(owner, patientId, note, doc);
        List<MemoryAgentNoteContract.Observation> observations = observationsFrom(doc);

        // 逐字摘录仍成立 + 覆盖全部分类
        assertValidObservations(sourceEntries, observations);

        // sourceHash 未变：随访内容 + 当前记忆版本 id（重新读取，检测期间是否漂移）
        String currentMemoryId = memoryService.currentId(patientId);
        String recomputed = Ids.sha256Hex(Json.write(sourceHashBody(sourceEntries, currentMemoryId)));
        if (!recomputed.equals(note.getSourceHash())) {
            throw new BizException(ERROR_SOURCE_CHANGED,
                    "随访内容或当前记忆版本已变化，候选已失效", HttpStatus.CONFLICT);
        }

        // 通过：note 表内发布语义，未改动正式记忆版本，发布凭据落 note 表
        appendAgentNote(doc, note, currentMemoryId, observations);
        note.setObservations(Json.write(doc));
        note.setMemoryId(currentMemoryId);
        note.setStatus(STATUS_PUBLISHED);
        note.setPublishedAt(OffsetDateTime.now());
        noteMapper.updateById(note);
        return view(note);
    }

    // ------------------------------------------------------------ 摘录守卫（package-private 便于单测）

    /**
     * 校验摘录覆盖全部分类且逐字成立。
     *
     * <p>package-private 而非 private：这是最容易被后续改动悄悄破坏的规则，
     * 单测要能直接拿一条"不在原文里的 quote"打进来验证守卫仍然生效。
     */
    void assertValidObservations(List<MemoryAgentNoteContract.AgentEntry> entries,
                                 List<MemoryAgentNoteContract.Observation> observations) {
        int size = entries.size();
        if (observations == null || observations.size() != size) {
            throw invalid("摘录未覆盖全部分类（entries=" + size + "，observations="
                    + (observations == null ? 0 : observations.size()) + "）");
        }
        Set<Integer> indices = new LinkedHashSet<>();
        for (MemoryAgentNoteContract.Observation ob : observations) {
            if (ob == null) {
                throw invalid("摘录项不能为空");
            }
            int idx = ob.entryIndex();
            if (idx < 0 || idx >= size) {
                throw invalid("entryIndex 越界：" + idx);
            }
            if (!indices.add(idx)) {
                throw invalid("entryIndex 重复：" + idx);
            }
            String quote = ob.quote();
            if (quote == null) {
                throw invalid("quote 不能为空");
            }
            // 逐字摘录：quote 必须是该条 content 的连续原文片段
            if (!entries.get(idx).content().contains(quote)) {
                throw invalid("quote 不是 entries[" + idx + "].content 的连续原文摘录");
            }
        }
        // size 相等 + 下标互异 + 均在 [0,size) 已经蕴含集合 == 0..size-1，这里显式断言留痕
        if (indices.size() != size) {
            throw invalid("摘录未覆盖全部分类");
        }
    }

    // ------------------------------------------------------------ 内部

    private void requireOwner(String owner, String patientId) {
        // 与 PatientMemoryService.requirePatient 一致：越权一律 404，不返回 403
        Patient patient = patientMapper.selectById(patientId);
        if (patient == null || !AccessControl.sameUser(owner, patient.getCreatedBy())) {
            throw BizException.notFound("患者档案");
        }
    }

    private void validateRequest(MemoryAgentNoteContract.AgentFollowupRequest request) {
        if (request == null) {
            throw BizException.badRequest("缺少摘录请求体");
        }
        if (!StringUtils.hasText(request.operationId())) {
            throw BizException.badRequest("operationId 必填");
        }
        if (!StringUtils.hasText(request.followupId())
                && (request.entries() == null || request.entries().isEmpty())) {
            throw BizException.badRequest("需提供 followupId 或 entries");
        }
    }

    /** 优先复用既有随访：按 id + owner + patientId 读原文，越权/不存在 404。 */
    private List<MemoryAgentNoteContract.AgentEntry> readFollowupEntries(String owner,
                                                                          String patientId,
                                                                          String followupId) {
        PatientFollowup row = followupMapper.selectOne(new LambdaQueryWrapper<PatientFollowup>()
                .eq(PatientFollowup::getId, followupId)
                .eq(PatientFollowup::getOwner, owner)
                .eq(PatientFollowup::getPatientId, patientId)
                .last("limit 1"));
        if (row == null) {
            throw BizException.notFound("随访记录");
        }
        List<PatientMemoryContract.FeedbackEntry> parsed =
                Json.readList(row.getEntries(), PatientMemoryContract.FeedbackEntry.class);
        List<MemoryAgentNoteContract.AgentEntry> result = new ArrayList<>();
        for (PatientMemoryContract.FeedbackEntry entry : parsed) {
            result.add(new MemoryAgentNoteContract.AgentEntry(entry.category(), entry.content()));
        }
        return validateEntries(result);
    }

    /** 校验并固化 entries：分类互异（覆盖全部分类）、原文非空；不改写、不摘要。 */
    private List<MemoryAgentNoteContract.AgentEntry> validateEntries(
            List<MemoryAgentNoteContract.AgentEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            throw BizException.badRequest("entries 不能为空");
        }
        Set<String> categories = new LinkedHashSet<>();
        List<MemoryAgentNoteContract.AgentEntry> result = new ArrayList<>(entries.size());
        for (MemoryAgentNoteContract.AgentEntry entry : entries) {
            if (entry == null || !StringUtils.hasText(entry.category())) {
                throw BizException.badRequest("随访分类不能为空");
            }
            if (!StringUtils.hasText(entry.content())) {
                throw BizException.badRequest("随访原文不能为空");
            }
            if (!categories.add(entry.category())) {
                throw BizException.badRequest("同次随访分类不能重复：" + entry.category());
            }
            result.add(new MemoryAgentNoteContract.AgentEntry(entry.category(), entry.content()));
        }
        return result;
    }

    /** 生成候选：逐条截 content 前 N 字符，天然是连续原文，entryIndex 覆盖 0..size-1。 */
    private List<MemoryAgentNoteContract.Observation> buildCandidate(
            List<MemoryAgentNoteContract.AgentEntry> entries) {
        List<MemoryAgentNoteContract.Observation> result = new ArrayList<>(entries.size());
        for (int i = 0; i < entries.size(); i++) {
            String content = entries.get(i).content();
            String quote = content.substring(0, Math.min(QUOTE_CHARS, content.length()));
            result.add(new MemoryAgentNoteContract.Observation(i, quote));
        }
        return result;
    }

    /** sourceHash 绑定：随访原文 + 当前记忆版本 id（null 归一成空串，避免 NON_NULL 序列化漂移）。 */
    private Map<String, Object> sourceHashBody(List<MemoryAgentNoteContract.AgentEntry> entries,
                                               String memoryVersionId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(KEY_ENTRIES, entries);
        body.put(KEY_MEMORY_VERSION_ID, memoryVersionId == null ? "" : memoryVersionId);
        return body;
    }

    private Map<String, Object> newDoc(List<MemoryAgentNoteContract.AgentEntry> entries,
                                       String memoryVersionId, String instruction,
                                       List<MemoryAgentNoteContract.Observation> observations) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put(KEY_OBSERVATIONS, observations);
        doc.put(KEY_ENTRIES, entries);
        doc.put(KEY_MEMORY_VERSION_ID, memoryVersionId == null ? "" : memoryVersionId);
        if (StringUtils.hasText(instruction)) {
            doc.put(KEY_INSTRUCTION, instruction);
        }
        doc.put(KEY_AGENT_NOTES, new ArrayList<Map<String, Object>>());
        return doc;
    }

    private Map<String, Object> readDoc(String json) {
        // 包成可变 LinkedHashMap：发布时要往 agentNotes 追加
        return new LinkedHashMap<>(Json.readMap(json));
    }

    /** 发布时重新解析来源：有 followupId 回读既有随访原文，否则用冻结的来源快照。 */
    private List<MemoryAgentNoteContract.AgentEntry> resolveSourceEntries(
            String owner, String patientId, MemoryAgentNote note, Map<String, Object> doc) {
        if (StringUtils.hasText(note.getFeedbackId())) {
            return readFollowupEntries(owner, patientId, note.getFeedbackId());
        }
        return entriesFrom(doc);
    }

    @SuppressWarnings("unchecked")
    private List<MemoryAgentNoteContract.AgentEntry> entriesFrom(Map<String, Object> doc) {
        Object raw = doc.get(KEY_ENTRIES);
        List<MemoryAgentNoteContract.AgentEntry> result = new ArrayList<>();
        if (!(raw instanceof List<?> list)) {
            return result;
        }
        for (Object item : list) {
            if (item instanceof MemoryAgentNoteContract.AgentEntry entry) {
                result.add(entry);  // 内存态（未序列化前）
            } else if (item instanceof Map<?, ?> map) {
                Object category = map.get("category");
                Object content = map.get("content");
                result.add(new MemoryAgentNoteContract.AgentEntry(
                        category == null ? null : String.valueOf(category),
                        content == null ? null : String.valueOf(content)));
            }
        }
        return result;
    }

    private List<MemoryAgentNoteContract.Observation> observationsFrom(Map<String, Object> doc) {
        Object raw = doc.get(KEY_OBSERVATIONS);
        List<MemoryAgentNoteContract.Observation> result = new ArrayList<>();
        if (!(raw instanceof List<?> list)) {
            return result;
        }
        for (Object item : list) {
            if (item instanceof MemoryAgentNoteContract.Observation ob) {
                result.add(ob);  // 内存态（未序列化前）
            } else if (item instanceof Map<?, ?> map) {
                Object idx = map.get("entryIndex");
                Object quote = map.get("quote");
                int index = idx instanceof Number number ? number.intValue()
                        : Integer.parseInt(String.valueOf(idx));
                result.add(new MemoryAgentNoteContract.Observation(index,
                        quote == null ? null : String.valueOf(quote)));
            }
        }
        return result;
    }

    /** 追加 agent_notes，按 noteId 去重（对齐原工程按 run_id 去重）。 */
    @SuppressWarnings("unchecked")
    private void appendAgentNote(Map<String, Object> doc, MemoryAgentNote note,
                                 String currentMemoryId,
                                 List<MemoryAgentNoteContract.Observation> observations) {
        List<Map<String, Object>> agentNotes = new ArrayList<>();
        Object raw = doc.get(KEY_AGENT_NOTES);
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    agentNotes.add((Map<String, Object>) map);
                }
            }
        }
        for (Map<String, Object> existing : agentNotes) {
            if (note.getId().equals(existing.get("noteId"))) {
                return;  // 按 noteId 去重：重复发布不重复追加
            }
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("noteId", note.getId());
        entry.put("candidateHash", note.getCandidateHash());
        entry.put("memoryId", currentMemoryId);
        entry.put("observations", observations);
        entry.put("publishedAt", OffsetDateTime.now().toString());
        agentNotes.add(entry);
        doc.put(KEY_AGENT_NOTES, agentNotes);
    }

    private MemoryAgentNote findByOperation(String owner, String patientId, String operationId) {
        return noteMapper.selectOne(new LambdaQueryWrapper<MemoryAgentNote>()
                .eq(MemoryAgentNote::getOwner, owner)
                .eq(MemoryAgentNote::getPatientId, patientId)
                .eq(MemoryAgentNote::getOperationId, operationId)
                .last("limit 1"));
    }

    private MemoryAgentNote findNote(String owner, String patientId, String noteId) {
        return noteMapper.selectOne(new LambdaQueryWrapper<MemoryAgentNote>()
                .eq(MemoryAgentNote::getId, noteId)
                .eq(MemoryAgentNote::getOwner, owner)
                .eq(MemoryAgentNote::getPatientId, patientId)
                .last("limit 1"));
    }

    private MemoryAgentNoteContract.NoteView view(MemoryAgentNote note) {
        return new MemoryAgentNoteContract.NoteView(
                note.getId(), note.getPatientId(), note.getStatus(), note.getCandidateHash(),
                observationsFrom(readDoc(note.getObservations())), note.getCreatedAt());
    }

    private BizException invalid(String message) {
        return new BizException(ERROR_EVIDENCE_INVALID, message, HttpStatus.BAD_REQUEST);
    }
}
