package com.kangheng.healthos.provider.ai;

import java.util.List;

import com.kangheng.healthos.config.HealthOsProperties;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import com.kangheng.healthos.spi.ai.LlmProvider;

import static org.assertj.core.api.Assertions.assertThat;

class TemplateLlmProviderTest {

    private final TemplateLlmProvider provider = new TemplateLlmProvider(new HealthOsProperties());

    private static final String SAMPLE =
            "四君子汤主治脾胃气虚、食少便溏。人参归脾经。黄芪具有补气升阳的功效。";

    @Test
    @DisplayName("抽取方剂、药材实体并区分类型")
    void extractsEntitiesWithTypes() {
        LlmProvider.ExtractionResult result = provider.extract("方剂学", SAMPLE);

        assertThat(result.entities())
                .extracting(LlmProvider.ExtractedEntity::name)
                .contains("四君子汤", "人参", "黄芪");
        assertThat(result.entities())
                .filteredOn(entity -> entity.name().equals("四君子汤"))
                .extracting(LlmProvider.ExtractedEntity::type)
                .containsExactly("FORMULA");
        assertThat(result.entities())
                .filteredOn(entity -> entity.name().equals("黄芪"))
                .extracting(LlmProvider.ExtractedEntity::type)
                .containsExactly("HERB");
    }

    @Test
    @DisplayName("抽取主治、归经、功效三种关系")
    void extractsRelationsWithEvidence() {
        LlmProvider.ExtractionResult result = provider.extract("方剂学", SAMPLE);

        assertThat(result.relations())
                .extracting(LlmProvider.ExtractedRelation::type)
                .contains("主治", "归经", "功效");

        LlmProvider.ExtractedRelation treat = result.relations().stream()
                .filter(relation -> relation.type().equals("主治"))
                .findFirst()
                .orElseThrow();
        assertThat(treat.source()).isEqualTo("四君子汤");
        assertThat(treat.evidence()).contains("四君子汤").contains("主治");
        assertThat(treat.confidence()).isBetween(0.5, 1.0);
    }

    @Test
    @DisplayName("空分段返回空结果而不是抛错")
    void handlesBlankSegment() {
        assertThat(provider.extract("标题", "").isEmpty()).isTrue();
        assertThat(provider.extract("标题", null).isEmpty()).isTrue();
        assertThat(provider.extract("标题", "   \n\t ").isEmpty()).isTrue();
    }

    @Test
    @DisplayName("抽取结果受每段实体上限约束")
    void respectsEntityLimit() {
        HealthOsProperties properties = new HealthOsProperties();
        properties.getExtraction().setMaxEntitiesPerSegment(2);
        TemplateLlmProvider limited = new TemplateLlmProvider(properties);

        LlmProvider.ExtractionResult result = limited.extract("方剂学", SAMPLE);
        assertThat(result.entities()).hasSizeLessThanOrEqualTo(2);
    }

    @Test
    @DisplayName("回答只引用给定上下文，不引入外部内容")
    void answerStaysWithinContext() {
        String answer = provider.answer("四君子汤主治什么",
                "[1] 四君子汤主治脾胃气虚。\n[2] 人参补气。");
        assertThat(answer).contains("四君子汤主治脾胃气虚。");
        assertThat(answer).contains("四君子汤主治什么");
        assertThat(provider.answer("问题", "")).isEmpty();
    }

    @Test
    @DisplayName("条目式正文：方剂名独立成行时，后续字段行归属到该方剂")
    void extractsSectionedLayout() {
        String sectioned = """
                四君子汤
                出处 : 《太平惠民和剂局方》
                组成 : 人参、白术、茯苓、甘草各等分。
                功效 : 益气健脾。
                主治 : 脾胃气虚证，面色萎白，
                """;
        LlmProvider.ExtractionResult result = provider.extract("方剂学", sectioned);

        assertThat(result.relations())
                .extracting(LlmProvider.ExtractedRelation::type)
                .contains("组成", "功效", "主治");

        LlmProvider.ExtractedRelation ingredient = result.relations().stream()
                .filter(relation -> relation.type().equals("组成"))
                .findFirst()
                .orElseThrow();
        assertThat(ingredient.source()).isEqualTo("四君子汤");
        // "甘草各等分" 里的剂量描述必须被剥掉，否则会造出"甘草各等分"这个假药材
        assertThat(result.relations())
                .filteredOn(relation -> relation.type().equals("组成"))
                .extracting(LlmProvider.ExtractedRelation::target)
                .containsExactly("人参", "白术", "茯苓", "甘草");

        assertThat(result.relations())
                .filteredOn(relation -> relation.type().equals("主治"))
                .extracting(LlmProvider.ExtractedRelation::target)
                .contains("脾胃气虚证");

        // 证据必须是整行原文，才能被 EvidenceLocator 在 markdown 里定位
        assertThat(ingredient.evidence()).isEqualTo("组成 : 人参、白术、茯苓、甘草各等分。");
    }

    @Test
    @DisplayName("条目式正文：没有方剂标题时字段行被忽略，不硬凑关系")
    void ignoresSectionedWithoutFormula() {
        LlmProvider.ExtractionResult result = provider.extract("方剂学", "功效 : 益气健脾。\n主治 : 脾胃气虚证。");
        assertThat(result.relations()).isEmpty();
    }

    @Test
    @DisplayName("OCR 把方剂名认错字时仍能归位（汤→注）")
    void recoversTitleWithOcrTypo() {
        // 实测 tesseract 输出："四君子汤" -> "四君子注"
        String ocrText = """
                四君子注
                组成 : 人参、白术、茯苓、甘草各等分。
                功效 : 益气健脾。
                主治 : 脾胃气虚证，
                """;
        LlmProvider.ExtractionResult result = provider.extract("方剂学", ocrText);
        assertThat(result.relations())
                .extracting(LlmProvider.ExtractedRelation::source)
                .containsOnly("四君子注");
        assertThat(result.relations()).hasSizeGreaterThanOrEqualTo(6);
    }

    @Test
    @DisplayName("疑似标题判定：纯中文短行才算，带标点或过长的不算")
    void probableTitleRule() {
        assertThat(TemplateLlmProvider.isProbableTitle("四君子注")).isTrue();
        assertThat(TemplateLlmProvider.isProbableTitle("六味地黄丸")).isTrue();
        assertThat(TemplateLlmProvider.isProbableTitle("人参")).isTrue();
        assertThat(TemplateLlmProvider.isProbableTitle("组成 ：人参、白术")).isFalse();
        assertThat(TemplateLlmProvider.isProbableTitle("x")).isFalse();
        assertThat(TemplateLlmProvider.isProbableTitle("这是一段明显过长的正文句子内容")).isFalse();
    }

    @Test
    @DisplayName("提供者名称可用于快照留痕")
    void exposesName() {
        assertThat(provider.name()).isEqualTo("template-rule-based");
    }

    @Test
    @DisplayName("同一输入结果稳定，便于作为测试基线")
    void deterministic() {
        LlmProvider.ExtractionResult first = provider.extract("方剂学", SAMPLE);
        LlmProvider.ExtractionResult second = provider.extract("方剂学", SAMPLE);
        assertThat(first.entities()).isEqualTo(second.entities());
        assertThat(first.relations()).isEqualTo(second.relations());
        assertThat(first.entities()).isInstanceOf(List.class);
    }
}
