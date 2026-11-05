package com.kangheng.healthos.mapper.retrieval;

import java.util.List;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import com.kangheng.healthos.model.retrieval.ChunkVectorEntity;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 切片向量读写。
 *
 * <p>只写方言无关的 SQL（{@code real[]}/pgvector 的算子都不出现在这里），
 * 相似度计算放在 Java 侧：这样换存储（pgvector / 独立的向量库）时
 * 只需要替换 {@code ChunkVectorService}，mapper 契约不动。
 */
@Mapper
public interface ChunkVectorMapper extends BaseMapper<ChunkVectorEntity> {

    @Select("SELECT * FROM chunk_vector WHERE document_id = #{documentId} AND model = #{model} "
            + "ORDER BY chunk_id")
    List<ChunkVectorEntity> listByDocument(@Param("documentId") String documentId, @Param("model") String model);

    @Select("SELECT COUNT(*) FROM chunk_vector WHERE document_id = #{documentId} AND model = #{model}")
    int countByDocument(@Param("documentId") String documentId, @Param("model") String model);
}
