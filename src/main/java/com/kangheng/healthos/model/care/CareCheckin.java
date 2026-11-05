package com.kangheng.healthos.model.care;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 打卡记录：计划里每个任务一条。
 *
 * <p>对应原工程 {@code care_checkins}。{@code version} 做乐观锁——
 * 两个人同时打卡同一个任务时，后到的那次要能明确失败并告知当前版本，
 * 而不是静默覆盖掉前一个人的操作。
 */
@Data
@TableName("care_checkin")
public class CareCheckin {

    @TableId(type = IdType.INPUT)
    private String id;
    private String documentId;
    private String owner;
    private String taskId;
    private Boolean completed;
    private Integer version;
    private String actor;
    private OffsetDateTime updatedAt;
}
