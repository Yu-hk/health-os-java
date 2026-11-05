package com.kangheng.healthos.provider.patient;

import java.util.List;
import java.util.Map;

import com.kangheng.healthos.common.Json;
import com.kangheng.healthos.spi.ai.LlmException;
import com.kangheng.healthos.spi.ai.LlmProvider;
import com.kangheng.healthos.spi.patient.PatientRecordExtractor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 用真实模型做字段抽取。
 *
 * <p>默认不启用（{@code healthos.patient.intake.extractor=llm} 才开）。
 * 两个原因：
 * <ol>
 *   <li>模型会"补全"原文没有的字段——那等于伪造病历，所以必须要求"找不到就留空"，
 *       并且解析后逐字段核对是否真能在原文里找到；</li>
 *   <li>医疗字段的断言必须可复现，规则版才做得到（见 {@link RuleBasedPatientRecordExtractor}）。</li>
 * </ol>
 *
 * <p>即使开了这个实现，<b>人工核查两道仍然是硬要求</b>：模型抽取只是"抄"，不是"判"。
 */
@Component
@ConditionalOnProperty(name = "healthos.patient.intake.extractor", havingValue = "llm")
public class LlmPatientRecordExtractor implements PatientRecordExtractor {

    private static final String SYSTEM_PROMPT = """
            你是医疗资料结构化助手。任务：从给定的病历/体检报告片段中抽取字段。
            规则：
            1. 只输出 JSON 对象，不要任何解释文字。
            2. 格式：{"fields":{...},"metrics":[...],"source":"分片键"}。
            3. fields 只放原文里明确出现的字段（如姓名、性别、出生日期、身高、体重）。
               原文没有的字段一律不要输出，不许推测、不许补全。
            4. metrics 的每一项形如
               {"code":"SBP","name":"收缩压","value":130,"unit":"mmHg"}，
               数值必须是原文出现的数字。
            5. 数值型字段的值必须是 JSON 数字或数字字符串，不要带单位文字。
            """;

    private final LlmProvider llmProvider;

    public LlmPatientRecordExtractor(LlmProvider llmProvider) {
        this.llmProvider = llmProvider;
    }

    @Override
    public String name() {
        return "llm:" + llmProvider.name();
    }

    @Override
    public String extract(String partKey, String text) {
        return extractInternal(null, partKey, text);
    }

    /** 走 PATIENT_RECORD 主线：按业务实例冻结的模型配置调用，而不是全局 ai.*。 */
    @Override
    public String extract(LlmProvider.RouteContext route, String partKey, String text) {
        return extractInternal(route, partKey, text);
    }

    private String extractInternal(LlmProvider.RouteContext route, String partKey, String text) {
        if (text == null || text.isBlank()) {
            throw new PatientRecordExtractor.PatientExtractionException("分片内容为空：" + partKey);
        }
        String instruction = "请从下面这段资料中抽取字段，source 固定为 " + partKey + "。\n\n"
                + text;
        String raw;
        try {
            raw = route == null
                    ? llmProvider.chat(SYSTEM_PROMPT, instruction)
                    : llmProvider.chat(route, SYSTEM_PROMPT, instruction);
        } catch (LlmException ex) {
            throw new PatientRecordExtractor.PatientExtractionException(
                    "模型抽取失败：" + ex.getMessage(), ex);
        }
        String json = stripFence(raw);
        Map<String, Object> parsed = Json.readMap(json);
        if (parsed == null || parsed.isEmpty()) {
            throw new PatientRecordExtractor.PatientExtractionException("模型返回的不是 JSON 对象：" + raw);
        }
        Object metrics = parsed.get("metrics");
        if (metrics != null && !(metrics instanceof List<?>)) {
            throw new PatientRecordExtractor.PatientExtractionException("metrics 必须是数组：" + json);
        }
        parsed.put("source", partKey);
        return Json.write(parsed);
    }

    /** 模型爱给 markdown 代码围栏，先剥掉再解析。 */
    private static String stripFence(String raw) {
        String text = raw.strip();
        if (!text.startsWith("```")) {
            return text;
        }
        int firstNewline = text.indexOf('\n');
        int lastFence = text.lastIndexOf("```");
        if (firstNewline < 0 || lastFence <= firstNewline) {
            return text;
        }
        return text.substring(firstNewline + 1, lastFence).strip();
    }
}
