package com.kangheng.healthos.contract.patient;

import java.time.OffsetDateTime;
import java.util.Map;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 患者资料录入审阅草稿契约。
 *
 * <p>对齐原工程 {@code patient_draft_api.py} 的 {@code ReviewDraft}。这里要把一条边界
 * 写在最前面，免得调用方凭接口名猜：<b>服务端草稿绝不改动临床修订与确认状态</b>，
 * 草稿没有"提交/确认"端点；正式写入走既有的 {@code /exams/{examId}/confirm}。
 * 草稿只是录入员在确认前的"暂存区"，它存的是待核对的字段，不是已签发的病历。
 */
public final class PatientDraftContract {

    private PatientDraftContract() {
    }

    /** GET/PUT/DELETE 的统一响应；草稿不存在时 {@code draft} 为 null（DELETE 永远返回 null）。 */
    public record DraftResponse(DraftView draft) {
    }

    /** 草稿视图。payload 原样反序列化为 Map，服务端不解释其内部结构。 */
    public record DraftView(int version, int baseRevision, Map<String, Object> payload,
                            OffsetDateTime updatedAt) {
    }

    /**
     * PUT 请求。
     *
     * @param baseRevision   该草稿对应的录入单 revision，必须 >= 1；与 exam 当前 revision 不一致即冲突
     * @param expectedVersion 乐观锁版本；0 表示"首次隐式创建"（成功落库后 version=1）
     * @param payload         白名单内的字段集合，每个值必须是 JSON object
     */
    public record PutDraftRequest(@NotNull @Min(1) Integer baseRevision,
                                  @NotNull @Min(0) Integer expectedVersion,
                                  Map<String, Object> payload) {
    }
}
