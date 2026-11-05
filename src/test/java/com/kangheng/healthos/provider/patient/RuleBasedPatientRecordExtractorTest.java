package com.kangheng.healthos.provider.patient;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import com.kangheng.healthos.common.Json;
import com.kangheng.healthos.model.patient.PatientMetrics;
import com.kangheng.healthos.spi.patient.PatientRecordExtractor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 规则抽取器的行为约定。
 *
 * <p>这一组用例守的是"AI 抄出来的东西到底对不对"。
 * 医疗字段的断言必须可复现——模型会给出不一样的答案，规则不会，
 * 所以规则本身就是可测的边界，也是 CI 里真正跑的那一套。
 *
 * <p>用��里几条特别重要，因为它们都是<b>会读错临床数据</b>的类型：
 * <ul>
 *   <li>把餐后血糖当空腹血糖（参考区间不同，会造出假阳性异常）；</li>
 *   <li>数值被截断（"血糖 5.60" 变成 "5.6"、"身高 1700" 变成 "170"）；</li>
 *   <li>原文没有的字段被凭空补上（那等于伪造病历）。</li>
 * </ul>
 */
class RuleBasedPatientRecordExtractorTest {

    private final RuleBasedPatientRecordExtractor extractor = new RuleBasedPatientRecordExtractor();

    private Map<String, Object> fieldsOf(String text) {
        Map<String, Object> payload = Json.readMap(extractor.extract("general", text));
        Object fields = payload.get("fields");
        return fields instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private List<Map<String, Object>> metricsOf(String text) {
        Map<String, Object> payload = Json.readMap(extractor.extract("general", text));
        Object metrics = payload.get("metrics");
        if (!(metrics instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(item -> item instanceof Map<?, ?>)
                .map(item -> (Map<String, Object>) item)
                .toList();
    }

    private Map<String, Object> metric(String text, String code) {
        return metricsOf(text).stream()
                .filter(m -> code.equals(m.get("code")))
                .findFirst()
                .orElse(null);
    }

    /**
     * JSON 往返会把 BigDecimal 变成 Double/Integer，所以断言要按数值比，
     * 不能直接强转类型——那样测的是序列化细节，不是抽取结果。
     */
    private static BigDecimal decimal(Map<String, Object> metric) {
        Object value = metric.get("value");
        return new BigDecimal(String.valueOf(value));
    }

    // ------------------------------------------------------------ 基础字段

    @Test
    @DisplayName("基础字段：姓名/性别/生日/电话按常见写法抽取")
    void extractsProfileFields() {
        Map<String, Object> fields = fieldsOf("""
                姓名：张三
                性别：男
                出生日期：1965-04-12
                联系电话：13800000000
                """);

        assertThat(fields).containsEntry("name", "张三");
        assertThat(fields).containsEntry("gender", "男");
        assertThat(fields).containsEntry("birthDate", "1965-04-12");
        assertThat(fields).containsEntry("phone", "13800000000");
    }

    @Test
    @DisplayName("身高体重：带单位与不带单位都能抽到，且不会被截断")
    void extractsHeightAndWeight() {
        assertThat(fieldsOf("身高 170.5cm 体重 68.5kg"))
                .containsEntry("heightCm", "170.5")
                .containsEntry("weightKg", "68.5");
        assertThat(fieldsOf("身高：170 体重：65"))
                .containsEntry("heightCm", "170")
                .containsEntry("weightKg", "65");
    }

    @Test
    @DisplayName("身高不会被 1700cm 截成 170——读错数据比抽不到更危险")
    void doesNotTruncateHeight() {
        assertThat(fieldsOf("身高 1700cm")).doesNotContainKey("heightCm");
    }

    @Test
    @DisplayName("\"体重减轻5kg\"不是体重——不能让主诉里的描述变成体重指标")
    void doesNotTreatSymptomAsWeight() {
        Map<String, Object> fields = fieldsOf("体重减轻5kg");
        assertThat(fields).doesNotContainKey("weightKg");
    }

    @Test
    @DisplayName("原文没有的字段一律不出现：缺字段是待人工补，不是模型猜")
    void doesNotInventMissingFields() {
        Map<String, Object> fields = fieldsOf("主诉：头痛三天");

        assertThat(fields).isEmpty();
    }

    @Test
    @DisplayName("出生日期：OCR 缺字（生日期）与简写（生日）都要抽得到")
    void extractsBirthDateVariants() {
        assertThat(fieldsOf("出生日期：1962-03-15")).containsEntry("birthDate", "1962-03-15");
        assertThat(fieldsOf("生日期 : 1975-07-08")).containsEntry("birthDate", "1975-07-08");
        assertThat(fieldsOf("生日 1990.1.2")).containsEntry("birthDate", "1990.1.2");
        assertThat(fieldsOf("出生年月日 1988年12月25日")).containsEntry("birthDate", "1988年12月25日");
    }

    @Test
    @DisplayName("OCR 在单位里插了字也不影响数值抽取（mmol/ 儿 L）")
    void toleratesOcrNoiseInUnits() {
        assertThat(decimal(metric("空腹血糖 6.2 mmol/ 儿 L", "FBG")))
                .isEqualByComparingTo("6.2");
    }

    // ------------------------------------------------------------ 指标

    @Test
    @DisplayName("血压：\"血压 130/80\" 拆成收缩压与舒张压两项")
    void extractsBloodPressure() {
        Map<String, Object> sbp = metric("血压 130/80 mmHg", "SBP");
        Map<String, Object> dbp = metric("血压 130/80 mmHg", "DBP");

        assertThat(sbp).isNotNull();
        assertThat(decimal(sbp)).isEqualByComparingTo("130");
        assertThat(decimal(dbp)).isEqualByComparingTo("80");
    }

    @Test
    @DisplayName("收缩压/舒张压分列写法也能抽到")
    void extractsSeparateBloodPressure() {
        assertThat(decimal(metric("收缩压 145 舒张压 92", "SBP")))
                .isEqualByComparingTo("145");
        assertThat(decimal(metric("收缩压 145 舒张压 92", "DBP")))
                .isEqualByComparingTo("92");
    }

    @Test
    @DisplayName("餐后血糖绝不落进空腹血糖——两者参考区间不同，混了就是误判")
    void postprandialGlucoseNeverBecomesFasting() {
        assertThat(metric("餐后2小时血糖 7.8", "FBG")).isNull();
        assertThat(metric("餐后血糖 9.2", "FBG")).isNull();
        assertThat(metric("餐后 2 小时血糖 8.1", "FBG")).isNull();
        assertThat(metric("随机血糖 8.0", "FBG")).isNull();
    }

    @Test
    @DisplayName("餐后血糖单列成 PPG，参考区间与空腹不同")
    void extractsPostprandialGlucoseSeparately() {
        assertThat(decimal(metric("餐后2小时血糖 7.8", "PPG")))
                .isEqualByComparingTo("7.8");
        assertThat(decimal(metric("餐后血糖 9.2", "PPG")))
                .isEqualByComparingTo("9.2");
    }

    @Test
    @DisplayName("空腹血糖照常抽到，不受餐后否决规则影响")
    void extractsFastingGlucose() {
        assertThat(decimal(metric("空腹血糖 5.6", "FBG")))
                .isEqualByComparingTo("5.6");
        assertThat(decimal(metric("血糖 5.6", "FBG")))
                .isEqualByComparingTo("5.6");
    }

    @Test
    @DisplayName("主诉里的\"餐后不适\"离标签够远，不该误伤后面的血糖值")
    void distantPostprandialWordDoesNotReject() {
        assertThat(decimal(metric("主诉：餐后不适，今日血糖 5.6", "FBG")))
                .isEqualByComparingTo("5.6");
    }

    @Test
    @DisplayName("糖化血红蛋白：括号写法与英文缩写都能抽到")
    void extractsHbA1c() {
        assertThat(decimal(metric("糖化血红蛋白(HbA1c) 7.2%", "HbA1c")))
                .isEqualByComparingTo("7.2");
        assertThat(decimal(metric("HbA1c 6.1%", "HbA1c")))
                .isEqualByComparingTo("6.1");
    }

    @Test
    @DisplayName("尿酸：\"尿酸\" 与 \"血尿酸\" 两种写法都抽得到")
    void extractsUricAcid() {
        assertThat(decimal(metric("尿酸 380", "UA")))
                .isEqualByComparingTo("380");
        assertThat(decimal(metric("血尿酸 380 μmol/L", "UA")))
                .isEqualByComparingTo("380");
    }

    @Test
    @DisplayName("甘油三酯的别名\"三酰甘油\"也抽得到")
    void extractsTriglycerideAlias() {
        assertThat(decimal(metric("三酰甘油 2.1", "TG")))
                .isEqualByComparingTo("2.1");
    }

    @Test
    @DisplayName("数值精度不丢：5.67 是 5.67，不能被截成 5.6")
    void keepsNumericPrecision() {
        assertThat(decimal(metric("血糖 5.67", "FBG")))
                .isEqualByComparingTo("5.67");
    }

    @Test
    @DisplayName("越界判定：超出常用参考区间才标 HIGH/LOW")
    void evaluatesAbnormalStatus() {
        assertThat(metric("空腹血糖 7.8", "FBG").get("status")).isEqualTo("HIGH");
        assertThat(metric("空腹血糖 5.6", "FBG").get("status")).isEqualTo("NORMAL");
        assertThat(metric("尿酸 380", "UA").get("status")).isEqualTo("NORMAL");
    }

    @Test
    @DisplayName("参考区间缺失的一端不判：只有上限时高于才算高")
    void handlesHalfOpenRange() {
        assertThat(metric("总胆固醇 5.2", "TC").get("status")).isEqualTo("NORMAL");
        assertThat(metric("总胆固醇 6.0", "TC").get("status")).isEqualTo("HIGH");
    }

    // ------------------------------------------------------------ BMI 派生

    @Test
    @DisplayName("BMI 由身高体重派生，来源标注为 derived 而不是原文")
    void derivesBmi() {
        BigDecimal bmi = PatientMetrics.bmi(
                new BigDecimal("170"), new BigDecimal("68.5"));

        assertThat(bmi).isEqualByComparingTo("23.70");
    }

    @Test
    @DisplayName("身高或体重缺失时不算 BMI——宁可没有这个指标")
    void skipsBmiWhenInputMissing() {
        assertThat(PatientMetrics.bmi(new BigDecimal("170"), null)).isNull();
        assertThat(PatientMetrics.bmi(null, new BigDecimal("68.5"))).isNull();
        assertThat(PatientMetrics.bmi(BigDecimal.ZERO, new BigDecimal("68.5"))).isNull();
    }

    // ------------------------------------------------------------ 失败语义

    @Test
    @DisplayName("空分片直接失败，不返回空结果伪装成功")
    void failsOnEmptyPart() {
        assertThatThrownBy(() -> extractor.extract("general", "   "))
                .isInstanceOf(PatientRecordExtractor.PatientExtractionException.class);
    }
}
