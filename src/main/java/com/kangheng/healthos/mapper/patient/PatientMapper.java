package com.kangheng.healthos.mapper.patient;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import com.kangheng.healthos.model.patient.Patient;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface PatientMapper extends BaseMapper<Patient> {

    /**
     * 在事务内锁住患者行，用于给"记忆版本发布 / 请求序号分配"串行化。
     *
     * <p>本工程单库单实例、没有节点级租约，跨进程并发发布靠的就是这一把行锁。
     * 调用方<b>必须</b>在 {@code @Transactional} 里调用，否则锁立刻释放等于没锁。
     */
    @Select("SELECT * FROM patient WHERE id = #{patientId} FOR UPDATE")
    Patient lockForUpdate(@Param("patientId") String patientId);
}
