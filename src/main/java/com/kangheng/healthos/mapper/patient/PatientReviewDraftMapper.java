package com.kangheng.healthos.mapper.patient;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import com.kangheng.healthos.model.patient.PatientReviewDraft;

import org.apache.ibatis.annotations.Mapper;

/** 患者资料录入审阅草稿的数据访问。@Mapper 不能漏，否则启动时不进 MyBatis 扫描。 */
@Mapper
public interface PatientReviewDraftMapper extends BaseMapper<PatientReviewDraft> {
}
