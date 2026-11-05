package com.kangheng.healthos.provider.qa;

import java.math.BigDecimal;
import java.util.List;

import com.kangheng.healthos.model.patient.PatientMetric;
import com.kangheng.healthos.spi.qa.BranchContext;
import com.kangheng.healthos.spi.qa.BranchResult;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ModernBranchTest {

    private final ModernBranch branch = new ModernBranch();

    @Test
    @DisplayName("没传 patientId：该路 SKIPPED 并说明原因，不拿模型常识充数")
    void withoutPatientIsSkipped() {
        BranchResult result = branch.answer(context(null, List.of()));

        assertThat(result.status()).isEqualTo(BranchResult.SKIPPED);
        assertThat(result.answer()).isNull();
        assertThat(result.note()).contains("patientId");
    }

    @Test
    @DisplayName("有 patientId 但没录指标：判无依据")
    void patientWithoutMetricsIsInsufficient() {
        BranchResult result = branch.answer(context("pat_1", List.of()));

        assertThat(result.status()).isEqualTo(BranchResult.INSUFFICIENT);
    }

    @Test
    @DisplayName("有指标：按参考区间标注状态，引用指向指标行")
    void rendersMetricsWithReferenceRange() {
        PatientMetric metric = new PatientMetric();
        metric.setId("pmt_1");
        metric.setPatientId("pat_1");
        metric.setCode("SBP");
        metric.setName("收缩压");
        metric.setValue(new BigDecimal("150"));
        metric.setUnit("mmHg");
        metric.setReferenceLow(new BigDecimal("90"));
        metric.setReferenceHigh(new BigDecimal("140"));
        metric.setStatus("HIGH");

        BranchResult result = branch.answer(context("pat_1", List.of(metric)));

        assertThat(result.status()).isEqualTo(BranchResult.SUCCEEDED);
        assertThat(result.basis()).isEqualTo(BranchResult.BASIS_PATIENT_RECORD);
        assertThat(result.answer()).contains("收缩压 150 mmHg").contains("90–140").contains("HIGH");
        assertThat(result.citations()).singleElement()
                .satisfies(citation -> assertThat(citation.evidenceId()).isEqualTo("pmt_1"));
    }

    private static BranchContext context(String patientId, List<PatientMetric> metrics) {
        return new BranchContext("doc_1", "四君子汤", "我的血压怎么样", patientId,
                List.of(), null, null, metrics);
    }
}
