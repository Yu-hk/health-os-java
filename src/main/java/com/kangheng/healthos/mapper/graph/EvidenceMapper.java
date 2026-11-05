package com.kangheng.healthos.mapper.graph;

import java.util.List;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import com.kangheng.healthos.model.graph.EvidenceEntity;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface EvidenceMapper extends BaseMapper<EvidenceEntity> {

    /** 按置信度倒序取候选证据，供问答检索使用。 */
    @Select("SELECT * FROM evidence WHERE document_id = #{documentId} "
            + "ORDER BY confidence DESC, char_start ASC LIMIT #{limit}")
    List<EvidenceEntity> findTopByDocument(@Param("documentId") String documentId,
                                           @Param("limit") int limit);
}
