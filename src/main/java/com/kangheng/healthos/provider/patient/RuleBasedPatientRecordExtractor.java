package com.kangheng.healthos.provider.patient;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.kangheng.healthos.common.Json;
import com.kangheng.healthos.model.patient.PatientIntakeFields;
import com.kangheng.healthos.model.patient.PatientMetrics;
import com.kangheng.healthos.spi.patient.PatientRecordExtractor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 规则抽取器：离线、确定性、可单测。
 *
 * <p>这是默认实现，也是 CI 里真正跑的那一套。医疗字段抽取必须能被逐条断言
 * （"血压 130/80" 必须抽出 SBP=130、DBP=80），靠模型是断言不了的。
 *
 * <p>抽取结果的结构：
 * <pre>
 * {
 *   "fields":  { "name": "张三", "gender": "男", ... },
 *   "metrics": [ { "code": "SBP", "name": "收缩压", "value": 130, ... } ],
 *   "source":  "physical_exam"
 * }
 * </pre>
 *
 * <p><b>不造值</b>：原文里没有的字段一律不出现在 {@code fields} 里。
 * 缺字段是"待人工补"，不是"模型猜一个"。
 */
@Component
@ConditionalOnProperty(name = "healthos.patient.intake.extractor", havingValue = "rule-based",
        matchIfMissing = true)
public class RuleBasedPatientRecordExtractor implements PatientRecordExtractor {

    private static final String VALUE_GROUP = "value";

    @Override
    public String name() {
        return "rule-based";
    }

    @Override
    public String extract(String partKey, String text) {
        if (text == null || text.isBlank()) {
            throw new PatientRecordExtractor.PatientExtractionException("分片内容为空：" + partKey);
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        for (PatientIntakeFields.FieldRule rule : PatientIntakeFields.PROFILE_RULES) {
            String value = matchFirst(rule.pattern(), text);
            if (value != null) {
                fields.putIfAbsent(rule.key(), value);
            }
        }

        List<Map<String, Object>> metrics = new ArrayList<>();
        for (PatientIntakeFields.MetricRule rule : PatientIntakeFields.METRIC_RULES) {
            String value = null;
            for (Pattern pattern : rule.patterns()) {
                value = matchFirst(pattern, text, rule.rejectBefore());
                if (value != null) {
                    break;
                }
            }
            if (value == null) {
                continue;
            }
            BigDecimal number;
            try {
                number = new BigDecimal(value);
            } catch (NumberFormatException ex) {
                // 抽到了但不是数字，说明正则写松了。宁可不要这个指标，也不要错的值。
                continue;
            }
            metrics.add(PatientMetrics.metric(rule.code(), rule.name(), number, rule.unit(),
                    rule.lowValue(), rule.highValue()));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("fields", fields);
        result.put("metrics", metrics);
        result.put("source", partKey);
        return Json.write(result);
    }

    private static String matchFirst(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        String value = matcher.group(VALUE_GROUP);
        return value == null ? null : value.strip();
    }

    /**
     * 带禁忌前缀判定的匹配。
     *
     * <p>命中之后，还要看标签<b>紧邻前面</b>是不是禁忌写法。
     * "餐后2小时血糖 7.8" 用裸 "血糖" 的模式能匹配上 7.8，
     * 但它前面挨着 "餐后2小时"，这个值属于餐后血糖，不能算空腹血糖。
     *
     * <p>禁忌判定看的是前缀的<b>尾部</b>：禁忌写法必须紧贴标签。
     * "主诉：餐后不适，今日血糖 5.6" 里的 "餐后" 离标签够远，不该被否决。
     */
    private static String matchFirst(Pattern pattern, String text, List<Pattern> rejectBefore) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            String value = matcher.group(VALUE_GROUP);
            if (value == null) {
                continue;
            }
            if (!rejectedByPrefix(matcher.start(), text, rejectBefore)) {
                return value.strip();
            }
        }
        return null;
    }

    /** 标签前面紧邻的禁忌写法判定。 */
    private static boolean rejectedByPrefix(int matchStart, String text, List<Pattern> rejectBefore) {
        if (rejectBefore == null || rejectBefore.isEmpty()) {
            return false;
        }
        int from = Math.max(0, matchStart - 16);
        String prefix = text.substring(from, matchStart);
        for (Pattern forbidden : rejectBefore) {
            if (forbidden.matcher(prefix).find()) {
                return true;
            }
        }
        return false;
    }
}
