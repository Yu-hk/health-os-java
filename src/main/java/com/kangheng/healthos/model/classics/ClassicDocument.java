package com.kangheng.healthos.model.classics;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/** 典籍文档。status: UPLOADED / PROCESSING / READY / FAILED。 */
@Data
@TableName("classic_document")
public class ClassicDocument {

    @TableId(type = IdType.INPUT)
    private String id;
    private String title;
    private String sourceObjectKey;
    private String contentType;
    private String sha256;
    private String status;
    private String currentNode;
    private String errorCode;
    private String errorMessage;
    /** 上传者。流水线是异步的，只能靠它把轨迹归属还原到人。 */
    private String createdBy;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
