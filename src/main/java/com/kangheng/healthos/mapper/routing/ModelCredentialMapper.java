package com.kangheng.healthos.mapper.routing;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import com.kangheng.healthos.model.routing.ModelCredential;

import org.apache.ibatis.annotations.Mapper;

/** 加密凭据的数据访问。 */
@Mapper
public interface ModelCredentialMapper extends BaseMapper<ModelCredential> {
}
