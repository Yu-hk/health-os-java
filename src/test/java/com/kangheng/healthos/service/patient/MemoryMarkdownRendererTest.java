package com.kangheng.healthos.service.patient;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 记忆 Markdown 的三条纪律：确定性、缺事实说"暂无"、来源性质标清楚。
 *
 * <p>确定性不是洁癖：渲染结果要参与 contentHash 计算。如果因为 HashMap 遍历顺序
 * 或当前时间产生字节差异，同一个版本导出两次就会得到两个哈希，校验形同虚设。
 */
class MemoryMarkdownRendererTest {

    private final MemoryMarkdownRenderer renderer = new MemoryMarkdownRenderer();

    @Test
    @DisplayName("同一份快照渲染两次字节完全一致")
    void deterministic() {
        Map<String, Object> data = sampleData();
        List<Map<String, Object>> sources = sampleSources();
        String first = renderer.render("pat_1", 3, data, sources, "2026-11-04T10:00:00Z");
        String second = renderer.render("pat_1", 3, data, sources, "2026-11-04T10:00:00Z");
        assertThat(first).isEqualTo(second);
    }

    @Test
    @DisplayName("随访原文逐字保留，且明确标为患者自述而非医学核验")
    void followupVerbatim() {
        String markdown = renderer.render("pat_1", 1, sampleData(), sampleSources(), "2026-11-04T10:00:00Z");
        assertThat(markdown).contains("## 5. 随访反馈原文（患者自述，非医学核验）");
        assertThat(markdown).contains("> 今天  晨起头晕，自测 150/95");
        assertThat(markdown).contains("躯体症状感受");
        // 原文里的连续空格不能被 trim 掉
        assertThat(markdown).contains("> 今天  晨起头晕");
    }

    @Test
    @DisplayName("没有报告/计划/随访时写「暂无」，绝不补一句综合判断")
    void missingFactsAreExplicit() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("schemaVersion", "memory-1");
        String markdown = renderer.render("pat_1", 1, data, List.of(), "2026-11-04T10:00:00Z");
        assertThat(markdown).contains("暂无已发布的档案修订快照");
        assertThat(markdown).contains("暂无指标记录");
        assertThat(markdown).contains("暂无当前签发报告");
        assertThat(markdown).contains("暂无已确认生效计划");
        assertThat(markdown).contains("暂无随访反馈");
        assertThat(markdown).contains("本版本没有可登记的来源");
        assertThat(markdown).doesNotContain("综合来看", "建议患者", "考虑为");
    }

    @Test
    @DisplayName("当前指标采样被明确标注为「非已发布修订」")
    void metricsAreMarkedAsSnapshot() {
        String markdown = renderer.render("pat_1", 1, sampleData(), sampleSources(), "2026-11-04T10:00:00Z");
        assertThat(markdown).contains("## 2. 当前指标采样（非已发布修订）");
        assertThat(markdown).contains("不可作为历史趋势引用");
    }

    @Test
    @DisplayName("来源清单逐条列出 SHA256 与来源性质")
    void sourceListIsAuditable() {
        String markdown = renderer.render("pat_1", 1, sampleData(), sampleSources(), "2026-11-04T10:00:00Z");
        assertThat(markdown).contains("## 来源清单");
        assertThat(markdown).contains("patient_followup · fup_1 r1 · SHA256 abc123");
        assertThat(markdown).contains("患者自述原文，非医学核验事实");
    }

    private Map<String, Object> sampleData() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("schemaVersion", "memory-1");
        Map<String, Object> baseline = new LinkedHashMap<>();
        baseline.put("available", true);
        baseline.put("name", "李建国");
        baseline.put("revision", 2);
        baseline.put("snapshotSha256", "feedbeef");
        data.put("baseline", baseline);
        Map<String, Object> metric = new LinkedHashMap<>();
        metric.put("name", "收缩压");
        metric.put("code", "SBP");
        metric.put("value", 150);
        metric.put("unit", "mmHg");
        metric.put("referenceLow", 90);
        metric.put("referenceHigh", 139);
        metric.put("status", "HIGH");
        data.put("metrics", List.of(metric));
        data.put("report", Map.of());
        data.put("plan", Map.of());
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("category", "symptoms");
        entry.put("categoryLabel", "躯体症状感受");
        entry.put("content", "今天  晨起头晕，自测 150/95");
        Map<String, Object> followup = new LinkedHashMap<>();
        followup.put("feedbackId", "fup_1");
        followup.put("occurredAt", "2026-11-04T09:00:00Z");
        followup.put("createdAt", "2026-11-04T09:01:00Z");
        followup.put("actor", "alice");
        followup.put("entries", List.of(entry));
        data.put("followups", List.of(followup));
        return data;
    }

    private List<Map<String, Object>> sampleSources() {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("kind", "patient_followup");
        source.put("id", "fup_1");
        source.put("revision", 1);
        source.put("contentHash", "abc123");
        source.put("note", "患者自述原文，非医学核验事实");
        return List.of(source);
    }
}
