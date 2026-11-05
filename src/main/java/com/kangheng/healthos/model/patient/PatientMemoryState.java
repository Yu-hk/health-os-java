package com.kangheng.healthos.model.patient;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 当前生效的记忆指针。
 *
 * <p>与版本链分开是刻意的：一个是历史（只追加），一个是"现在看哪一版"（原地更新）。
 * 两者混在一张表里就得靠 {@code is_current} 标志维护，漏改一次会出现两个当前版本。
 */
@Data
@TableName("patient_memory_state")
public class PatientMemoryState {

    @TableId(type = IdType.INPUT)
    private String patientId;
    private String owner;
    private String currentMemoryId;
    private Integer version;
    private OffsetDateTime updatedAt;
}
