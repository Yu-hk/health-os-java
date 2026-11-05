package com.kangheng.healthos.service.patient;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import com.kangheng.healthos.common.BizException;
import com.kangheng.healthos.common.Ids;
import com.kangheng.healthos.common.PageResult;
import com.kangheng.healthos.contract.patient.PatientContract;
import com.kangheng.healthos.mapper.patient.PatientMapper;
import com.kangheng.healthos.mapper.patient.PatientMetricMapper;
import com.kangheng.healthos.model.patient.MetricEvaluator;
import com.kangheng.healthos.model.patient.Patient;
import com.kangheng.healthos.model.patient.PatientMetric;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 患者指标管理与读取。
 *
 * <p>建档与状态流转在 {@link PatientLifecycleService}——那里要管版本号与状态机，
 * 逻辑复杂到不适合塞进一个"读写指标"的服务里。这里只负责指标覆盖语义与视图装配。
 *
 * <p>指标写入采用「同 code 覆盖」语义：同一指标重复上报只保留最新值，
 * 避免同一份体检报告被传两次后指标翻倍。
 */
@Service
public class PatientService {

    private final PatientMapper patientMapper;
    private final PatientMetricMapper metricMapper;
    private final PatientLifecycleService lifecycle;

    public PatientService(PatientMapper patientMapper, PatientMetricMapper metricMapper,
                          PatientLifecycleService lifecycle) {
        this.patientMapper = patientMapper;
        this.metricMapper = metricMapper;
        this.lifecycle = lifecycle;
    }

    @Transactional
    public PatientContract.PatientDetail upsertMetrics(String patientId,
                                                       List<PatientContract.MetricInput> inputs,
                                                       String owner) {
        Patient patient = lifecycle.require(patientId, owner);
        if (inputs == null || inputs.isEmpty()) {
            throw BizException.badRequest("指标列表为空");
        }
        List<PatientMetric> saved = new ArrayList<>();
        for (PatientContract.MetricInput input : inputs) {
            metricMapper.delete(new LambdaQueryWrapper<PatientMetric>()
                    .eq(PatientMetric::getPatientId, patientId)
                    .eq(PatientMetric::getOwner, owner)
                    .eq(PatientMetric::getCode, input.code()));
            MetricEvaluator.Status status =
                    MetricEvaluator.evaluate(input.value(), input.referenceLow(), input.referenceHigh());
            PatientMetric metric = new PatientMetric();
            metric.setId(Ids.newId("pme"));
            metric.setPatientId(patientId);
            metric.setOwner(owner);
            metric.setCode(input.code());
            metric.setName(input.name());
            metric.setValue(input.value());
            metric.setUnit(input.unit());
            metric.setReferenceLow(input.referenceLow());
            metric.setReferenceHigh(input.referenceHigh());
            metric.setStatus(status.name());
            metric.setMeasuredAt(input.measuredAt());
            metric.setCreatedAt(OffsetDateTime.now());
            metricMapper.insert(metric);
            saved.add(metric);
        }
        refreshRiskLevel(patientId, owner);
        return detail(patientId, owner);
    }

    public PatientContract.PatientDetail detail(String patientId, String owner) {
        Patient patient = lifecycle.require(patientId, owner);
        List<PatientMetric> metrics = metricMapper.selectList(new LambdaQueryWrapper<PatientMetric>()
                .eq(PatientMetric::getPatientId, patientId)
                .eq(PatientMetric::getOwner, owner)
                .orderByAsc(PatientMetric::getCode));
        Map<String, Long> abnormal = new LinkedHashMap<>();
        abnormal.put("LOW", metrics.stream().filter(m -> "LOW".equals(m.getStatus())).count());
        abnormal.put("HIGH", metrics.stream().filter(m -> "HIGH".equals(m.getStatus())).count());
        abnormal.put("NORMAL", metrics.stream().filter(m -> "NORMAL".equals(m.getStatus())).count());
        List<PatientContract.MetricView> views = metrics.stream().map(this::toMetricView).toList();
        return new PatientContract.PatientDetail(toView(patient), views, abnormal);
    }

    /** 列表按归属人过滤：默认只看到自己的档案。 */
    public PageResult<PatientContract.PatientView> list(int page, int size, String owner) {
        Page<Patient> result = patientMapper.selectPage(
                new Page<>(PageResult.clampPage(page), PageResult.clampSize(size)),
                new LambdaQueryWrapper<Patient>()
                        .eq(Patient::getCreatedBy, owner)
                        .orderByDesc(Patient::getCreatedAt));
        return PageResult.of(result, PatientService::toView);
    }

    private void refreshRiskLevel(String patientId, String owner) {
        List<PatientMetric> metrics = metricMapper.selectList(new LambdaQueryWrapper<PatientMetric>()
                .eq(PatientMetric::getPatientId, patientId)
                .eq(PatientMetric::getOwner, owner));
        List<MetricEvaluator.Status> statuses = new ArrayList<>();
        for (PatientMetric metric : metrics) {
            statuses.add(parse(metric.getStatus()));
        }
        Patient update = new Patient();
        update.setId(patientId);
        update.setRiskLevel(MetricEvaluator.riskLevel(statuses));
        update.setUpdatedAt(OffsetDateTime.now());
        patientMapper.updateById(update);
    }

    private MetricEvaluator.Status parse(String status) {
        try {
            return MetricEvaluator.Status.valueOf(status);
        } catch (RuntimeException ex) {
            return MetricEvaluator.Status.UNKNOWN;
        }
    }

    /** 视图装配集中在一处：生命周期服务与查询都走它，避免两处字段对不齐。 */
    public static PatientContract.PatientView toView(Patient patient) {
        return new PatientContract.PatientView(patient.getId(), patient.getName(), patient.getGender(),
                patient.getBirthDate(), patient.getPhone(), patient.getRiskLevel(), patient.getStatus(),
                patient.getRevision(), patient.getExternalRef(), patient.getCreatedBy(),
                patient.getCreatedAt(), patient.getDeletedAt(), patient.getArchivedAt());
    }

    private PatientContract.MetricView toMetricView(PatientMetric metric) {
        return new PatientContract.MetricView(metric.getId(), metric.getCode(), metric.getName(),
                metric.getValue(), metric.getUnit(), metric.getReferenceLow(), metric.getReferenceHigh(),
                metric.getStatus(), metric.getMeasuredAt());
    }
}
