package com.kangheng.healthos.model.graph;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/** 图谱节点。normalized_name 用于去重，原项目同名实体合并后保留别名。 */
@Data
@TableName("graph_entity")
public class GraphNodeEntity {

    @TableId(type = IdType.INPUT)
    private String id;
    private String documentId;
    private String name;
    private String normalizedName;
    private String entityType;
    private String aliases;
    private OffsetDateTime createdAt;
}
