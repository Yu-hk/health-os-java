package com.kangheng.healthos.contract.qa;

import java.time.OffsetDateTime;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 患者知识库绑定契约。
 *
 * <p>对应原工程 {@code qa_api} 的 {@code rag-bindings} 一族。三个语义差别写在契约里，
 * 免得调用方猜：
 * <ul>
 *   <li>绑定只存映射，<b>不复制、不创建</b>远端数据集——库还是在 RAGFlow 里管；</li>
 *   <li>解绑只删映射，<b>不删库</b>。删库是不可逆操作，不应该由一个"解绑"按钮顺手做掉；</li>
 *   <li>对账（reconcile）把库已消失的绑定标成 {@code STALE}，保留记录但不参与检索。</li>
 * </ul>
 */
public final class KnowledgeBindingContract {

    private KnowledgeBindingContract() {
    }

    public record BindRequest(@NotBlank String datasetId,
                              @Size(max = 400) String note) {
    }

    /** 绑定视图。{@code status}: ACTIVE / STALE。 */
    public record BindingView(String id, String patientId, String datasetId, String datasetName,
                              String note, String status, String lastError,
                              OffsetDateTime lastCheckedAt, OffsetDateTime createdAt) {
    }

    /** 可绑定的数据集；{@code bound} 表示这个患者已经绑过。 */
    public record DatasetView(String id, String name, int chunkCount, int documentCount,
                              boolean bound) {
    }

    /**
     * 患者知识库总览。
     *
     * @param availableNote RAGFlow 不可用时的说明；此时 {@code available} 为空但
     *                      {@code bindings} 仍然返回（已经绑过的东西不能因为外部服务挂了就看不见）
     */
    public record Overview(String patientId, List<BindingView> bindings,
                           List<DatasetView> available, String availableNote) {
    }
}
