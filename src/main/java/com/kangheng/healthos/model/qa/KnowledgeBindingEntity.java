package com.kangheng.healthos.model.qa;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 患者与外部知识库（RAGFlow dataset）的绑定。
 *
 * <p>存在的理由：不同患者的资料不该互相串味。本工程原来的问答只能看"某个典籍文档"，
 * 挂上绑定后，患者的问答可以额外看到挂在他名下的资料库。
 *
 * <p>{@code status} 两态：
 * <ul>
 *   <li>{@code ACTIVE} —— 参与检索；</li>
 *   <li>{@code STALE} —— 对账发现库没了，保留记录但不参与检索。</li>
 * </ul>
 * 不做"发现库没了就删记录"：库是谁什么时候删的、这个患者什么时候开始少了依据，
 * 都是需要能被看见的事实。
 */
@Data
@TableName("qa_knowledge_binding")
public class KnowledgeBindingEntity {

    @TableId(type = IdType.INPUT)
    private String id;
    private String owner;
    private String patientId;
    /** RAGFlow dataset id。 */
    private String datasetId;
    private String datasetName;
    private String note;
    /** ACTIVE / STALE。 */
    private String status;
    private OffsetDateTime lastCheckedAt;
    private String lastError;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
