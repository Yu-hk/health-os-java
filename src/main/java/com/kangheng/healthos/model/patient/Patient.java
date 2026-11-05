package com.kangheng.healthos.model.patient;

import java.time.LocalDate;
import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("patient")
public class Patient {

    @TableId(type = IdType.INPUT)
    private String id;
    private String name;
    private String gender;
    private LocalDate birthDate;
    private String phone;
    /** UNKNOWN / LOW / MEDIUM / HIGH，按指标最高异常等级推导。 */
    private String riskLevel;
    /** DRAFT / ACTIVE / ARCHIVED / DELETED。删除是不可逆终态。 */
    private String status;
    /**
     * 乐观锁版本号。每次成功修改 +1，客户端带上 expectedRevision 才能改，
     * 避免两个页面同时编辑时后提交的把前一个人的修改无声覆盖。
     */
    private Integer revision;
    /** 外部业务号（如体检系统里的档案号），同一创建者内唯一，批次重跑靠它幂等。 */
    private String externalRef;
    private String createdBy;
    private OffsetDateTime deletedAt;
    private OffsetDateTime archivedAt;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
