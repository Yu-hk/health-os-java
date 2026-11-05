package com.kangheng.healthos.mapper.routing;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import com.kangheng.healthos.model.routing.ModelRouteVersion;

import org.apache.ibatis.annotations.Mapper;

/** 不可变配置版本的数据访问。 */
@Mapper
public interface ModelRouteVersionMapper extends BaseMapper<ModelRouteVersion> {
}
