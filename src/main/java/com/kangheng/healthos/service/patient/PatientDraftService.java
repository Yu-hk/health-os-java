package com.kangheng.healthos.service.patient;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Set;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import com.kangheng.healthos.common.BizException;
import com.kangheng.healthos.common.Ids;
import com.kangheng.healthos.common.Json;
import com.kangheng.healthos.contract.patient.PatientDraftContract;
import com.kangheng.healthos.mapper.patient.PatientIntakeExamMapper;
import com.kangheng.healthos.mapper.patient.PatientReviewDraftMapper;
import com.kangheng.healthos.model.patient.PatientIntakeExam;
import com.kangheng.healthos.model.patient.PatientReviewDraft;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 患者资料录入审阅草稿。
 *
 * <p>对齐原工程 {@code patient_draft_api.py} 的 {@code ReviewDraft}。<b>最关键的一条语义</b>：
 * 服务端草稿绝不改动临床修订与确认状态——本类只 {@code select} 录入单来读 revision，
 * 从不 {@code update} 它，草稿也没有"提交/确认"端点；正式写入走既有的
 * {@code /exams/{examId}/confirm}。这样草稿怎么编辑都不会污染已核对的病历。
 *
 * <p>另一条容易 regress 的是<b>丢响应重试</b>：第一次 PUT 已经落库但响应丢了，
 * 客户端带着同一个 expectedVersion 重发时，朴素的乐观锁会误判成版本冲突。
 * 所以冲突判定之前先做一次幂等命中：version == expectedVersion+1 且 payload、
 * baseRevision 都相同，就直接返回既有草稿，不报冲突、不推进版本。
 *
 * <p>所有端点先校验 exam 归属（按 owner 过滤查询），查不到一律 404 而不是 403——
 * 403 等于承认"这个录入单确实存在"，本身就是信息泄露。
 */
@Service
public class PatientDraftService {

    public static final String SCOPE_BASIC = "basic";
    public static final String SCOPE_CLINICAL = "clinical";
    public static final String ERROR_VERSION_CONFLICT = "DRAFT_VERSION_CONFLICT";
    public static final String ERROR_REVISION_CONFLICT = "DRAFT_REVISION_CONFLICT";

    /** payload 序列化后上限 1MB：草稿是暂存字段，不该被当成无限大的对象存储。 */
    private static final int MAX_PAYLOAD_BYTES = 1024 * 1024;
    /** scope → 允许的 payload key。每个值还必须是 JSON object（Map）。 */
    private static final Set<String> BASIC_KEYS = Set.of("basic_info");
    private static final Set<String> CLINICAL_KEYS =
            Set.of("basic_info", "indicators", "source_doctor_findings");

    private final PatientIntakeExamMapper examMapper;
    private final PatientReviewDraftMapper draftMapper;

    public PatientDraftService(PatientIntakeExamMapper examMapper,
                               PatientReviewDraftMapper draftMapper) {
        this.examMapper = examMapper;
        this.draftMapper = draftMapper;
    }

    // ------------------------------------------------------------ 查询

    /** GET：返回 draft 视图；不存在返回 {@code draft:null}。 */
    public PatientDraftContract.DraftResponse get(String examId, String scope, String owner) {
        requireScope(scope);
        requireExam(examId, owner);
        PatientReviewDraft row = findDraft(owner, examId, scope);
        return new PatientDraftContract.DraftResponse(row == null ? null : view(row));
    }

    // ------------------------------------------------------------ 写入

    /**
     * PUT：首次隐式创建（expectedVersion=0 → version=1），之后按乐观锁推进。
     *
     * <p>判定顺序有意为之，别随手调整：
     * <ol>
     *   <li><b>丢响应重试幂等</b>：命中就返回既有草稿；</li>
     *   <li><b>版本冲突</b>：当前 version != expectedVersion → DRAFT_VERSION_CONFLICT；</li>
     *   <li><b>修订冲突</b>：exam.revision != baseRevision → DRAFT_REVISION_CONFLICT，
     *       <b>草稿保留，不清空</b>（对齐原工程：修订变了草稿不能被静默丢弃，
     *       要让录入员看到"依据已过期"并自己决定）；</li>
     *   <li>全部通过才落库，version = expectedVersion + 1。</li>
     * </ol>
     */
    @Transactional
    public PatientDraftContract.DraftResponse put(String examId, String scope,
                                                  PatientDraftContract.PutDraftRequest request,
                                                  String owner) {
        requireScope(scope);
        PatientIntakeExam exam = requireExam(examId, owner);  // 所有端点先校验 exam 归属
        validateRequest(request, scope);
        int examRevision = exam.getRevision() == null ? 0 : exam.getRevision();

        PatientReviewDraft existing = findDraft(owner, examId, scope);
        String payloadJson = Json.write(request.payload());

        // 丢响应重试幂等：version == expectedVersion+1 且 payload 与 baseRevision 都相同 → 直接返回既有。
        // 用 Map 相等比对而非字符串，避免 JSON key 顺序差异把一次合法重试误判成冲突。
        if (existing != null
                && versionOf(existing) == request.expectedVersion() + 1
                && baseRevisionOf(existing) == request.baseRevision()
                && samePayload(existing.getPayload(), request.payload())) {
            return new PatientDraftContract.DraftResponse(view(existing));
        }

        // 乐观锁：当前 version != expectedVersion → 409 DRAFT_VERSION_CONFLICT。
        int currentVersion = existing == null ? 0 : versionOf(existing);
        if (currentVersion != request.expectedVersion()) {
            throw new BizException(ERROR_VERSION_CONFLICT,
                    "草稿版本冲突：当前 version=" + currentVersion
                            + "，请求 expectedVersion=" + request.expectedVersion(),
                    HttpStatus.CONFLICT);
        }

        // 修订冲突：草稿保留，不清空、不推进版本（不 delete、不 update）。
        if (examRevision != request.baseRevision()) {
            throw new BizException(ERROR_REVISION_CONFLICT,
                    "临床修订冲突：exam.revision=" + examRevision
                            + "，请求 baseRevision=" + request.baseRevision() + "，草稿已保留",
                    HttpStatus.CONFLICT);
        }

        // 通过：首次 expectedVersion=0 → version=1；否则 version = expectedVersion + 1。
        int newVersion = request.expectedVersion() + 1;
        OffsetDateTime now = OffsetDateTime.now();
        if (existing == null) {
            PatientReviewDraft created = new PatientReviewDraft();
            created.setId(Ids.newId("prd"));
            created.setOwner(owner);
            created.setExamId(examId);
            created.setScope(scope);
            created.setBaseRevision(request.baseRevision());
            created.setVersion(newVersion);
            created.setPayload(payloadJson);
            created.setUpdatedAt(now);
            draftMapper.insert(created);
            return new PatientDraftContract.DraftResponse(view(created));
        }
        // 原地改 + updateById：所有字段都非 null，不受 MyBatis-Plus 默认 NOT_NULL 更新策略影响。
        existing.setVersion(newVersion);
        existing.setBaseRevision(request.baseRevision());
        existing.setPayload(payloadJson);
        existing.setUpdatedAt(now);
        draftMapper.updateById(existing);
        return new PatientDraftContract.DraftResponse(view(existing));
    }

    // ------------------------------------------------------------ 删除

    /** DELETE：幂等，草稿不存在也返回 {@code draft:null}；但 exam 不归属仍 404。 */
    @Transactional
    public PatientDraftContract.DraftResponse delete(String examId, String scope, String owner) {
        requireScope(scope);
        requireExam(examId, owner);  // 所有端点先校验 exam 归属
        draftMapper.delete(new LambdaQueryWrapper<PatientReviewDraft>()
                .eq(PatientReviewDraft::getOwner, owner)
                .eq(PatientReviewDraft::getExamId, examId)
                .eq(PatientReviewDraft::getScope, scope));
        return new PatientDraftContract.DraftResponse(null);
    }

    // ------------------------------------------------------------ 内部

    /** 只读录入单校验归属与取 revision，绝不 update 它——草稿不碰临床修订与确认状态。 */
    private PatientIntakeExam requireExam(String examId, String owner) {
        PatientIntakeExam exam = examMapper.selectOne(new LambdaQueryWrapper<PatientIntakeExam>()
                .eq(PatientIntakeExam::getId, examId)
                .eq(PatientIntakeExam::getOwner, owner)
                .last("limit 1"));
        if (exam == null) {
            throw BizException.notFound("录入单");  // 查不到 404，不是 403
        }
        return exam;
    }

    private void requireScope(String scope) {
        if (!SCOPE_BASIC.equals(scope) && !SCOPE_CLINICAL.equals(scope)) {
            throw BizException.badRequest("草稿 scope 只能是 basic / clinical：" + scope);
        }
    }

    private void validateRequest(PatientDraftContract.PutDraftRequest request, String scope) {
        if (request == null) {
            throw BizException.badRequest("缺少草稿请求体");
        }
        if (request.baseRevision() == null || request.baseRevision() < 1) {
            throw BizException.badRequest("baseRevision 必填且 >= 1");
        }
        if (request.expectedVersion() == null || request.expectedVersion() < 0) {
            throw BizException.badRequest("expectedVersion 必填且 >= 0");
        }
        Map<String, Object> payload = request.payload();
        if (payload == null) {
            throw BizException.badRequest("payload 必填");
        }
        Set<String> allowed = SCOPE_BASIC.equals(scope) ? BASIC_KEYS : CLINICAL_KEYS;
        for (Map.Entry<String, Object> entry : payload.entrySet()) {
            if (!allowed.contains(entry.getKey())) {
                throw BizException.badRequest("scope=" + scope + " 不允许 payload key：" + entry.getKey());
            }
            if (!(entry.getValue() instanceof Map<?, ?>)) {
                throw BizException.badRequest("payload." + entry.getKey() + " 必须是 JSON object（Map）");
            }
        }
        if (Json.write(payload).getBytes(StandardCharsets.UTF_8).length > MAX_PAYLOAD_BYTES) {
            throw BizException.badRequest("payload 序列化后超过 1MB 上限");
        }
    }

    private PatientReviewDraft findDraft(String owner, String examId, String scope) {
        return draftMapper.selectOne(new LambdaQueryWrapper<PatientReviewDraft>()
                .eq(PatientReviewDraft::getOwner, owner)
                .eq(PatientReviewDraft::getExamId, examId)
                .eq(PatientReviewDraft::getScope, scope)
                .last("limit 1"));
    }

    /** Map 相等做幂等比对：不受 JSON key 顺序影响，丢响应重试最稳。 */
    private boolean samePayload(String storedJson, Map<String, Object> requested) {
        return Json.readMap(storedJson).equals(requested);
    }

    private PatientDraftContract.DraftView view(PatientReviewDraft row) {
        return new PatientDraftContract.DraftView(versionOf(row), baseRevisionOf(row),
                Json.readMap(row.getPayload()), row.getUpdatedAt());
    }

    private static int versionOf(PatientReviewDraft row) {
        return row.getVersion() == null ? 0 : row.getVersion();
    }

    private static int baseRevisionOf(PatientReviewDraft row) {
        return row.getBaseRevision() == null ? 0 : row.getBaseRevision();
    }
}
