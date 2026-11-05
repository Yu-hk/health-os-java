package com.kangheng.healthos.model.graph;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/** 图谱边。每条边必须能挂到一条证据上，否则视为不可溯源。 */
@Data
@TableName("graph_relation")
public class GraphEdgeEntity {

    @TableId(type = IdType.INPUT)
    private String id;
    private String documentId;
    private String sourceEntityId;
    private String targetEntityId;
    private String relationType;
    private BigDecimal confidence;
    private String evidenceId;
    private OffsetDateTime createdAt;
}
