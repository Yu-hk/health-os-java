package com.kangheng.healthos.provider.qa;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import com.kangheng.healthos.model.qa.Citation;
import com.kangheng.healthos.model.patient.PatientMetric;
import com.kangheng.healthos.spi.qa.AnswerBranch;
import com.kangheng.healthos.spi.qa.BranchContext;
import com.kangheng.healthos.spi.qa.BranchResult;

import org.springframework.stereotype.Component;

/**
 * 现代医学视角分支（对应原工程 {@code modern} 路）。
 *
 * <p>原工程这一路的凭据可能是 {@code patient_record}（患者记录）或 {@code model_knowledge}
 * （模型常识）。本实现<b>只做前者</b>：读出该患者最近一次指标并按参考区间标注越界状态。
 *
 * <p>为什么不做模型常识那一半：没有患者上下文时，"现代医学怎么看"只能靠模型自由发挥，
 * 而这是本项目明确禁止的（无出处不作答）。所以没传 {@code patientId} 时这一路直接 SKIPPED，
 * 由汇总如实告知"该视角未启用"，而不是编一段看起来专业的解释。
 */
@Component
public class ModernBranch implements AnswerBranch {

    private static final String KEY = "modern";
    private static final String LABEL = "现代医学";

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public String label() {
        return LABEL;
    }

    @Override
    public int order() {
        return 30;
    }

    @Override
    public BranchResult answer(BranchContext context) {
        long started = System.nanoTime();
        if (context.patientId() == null || context.patientId().isBlank()) {
            return BranchResult.skipped(KEY, LABEL,
                    "未提供 patientId，缺少患者指标准据，该视角不比模型常识更可靠，故不启用",
                    elapsed(started));
        }
        if (context.metrics().isEmpty()) {
            return BranchResult.insufficient(KEY, LABEL,
                    "该患者没有录入任何指标", elapsed(started));
        }

        StringBuilder answer = new StringBuilder("患者指标（按参考区间判定）：\n");
        List<Citation> citations = new ArrayList<>();
        for (PatientMetric metric : context.metrics()) {
            answer.append("· ").append(metric.getName()).append(' ')
                    .append(format(metric.getValue()));
            if (metric.getUnit() != null && !metric.getUnit().isBlank()) {
                answer.append(' ').append(metric.getUnit());
            }
            answer.append("（参考 ")
                    .append(format(metric.getReferenceLow())).append("–")
                    .append(format(metric.getReferenceHigh())).append("）→ ")
                    .append(metric.getStatus()).append('\n');
            citations.add(new Citation(metric.getId(),
                    metric.getName() + " " + format(metric.getValue()) + " "
                            + (metric.getUnit() == null ? "" : metric.getUnit()),
                    null, null, null, 1.0));
        }
        answer.append("以上为录入指标的区间判定结果，不构成诊断结论。");
        return BranchResult.succeeded(KEY, LABEL, BranchResult.BASIS_PATIENT_RECORD,
                answer.toString(), citations, elapsed(started));
    }

    private static String format(BigDecimal value) {
        return value == null ? "-" : value.stripTrailingZeros().toPlainString();
    }

    private static long elapsed(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }
}
