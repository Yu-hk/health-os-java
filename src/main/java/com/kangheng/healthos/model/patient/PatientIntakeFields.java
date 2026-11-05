package com.kangheng.healthos.model.patient;

import java.math.BigDecimal;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 资料录入的字段口径：分片怎么切、抽哪些字段、指标参考区间是多少。
 *
 * <p>集中放在一处，是为了让"AI 抄出来的到底是什么"有唯一答案。
 * 改这里的规则 = 改抽取口径，必须同时更新 `PatientIntakeExtractorTest`。
 *
 * <p><b>参考区间是"常用成人参考值"，不是诊断标准。</b>
 * 真正的诊断阈值要看指南、要看实验室方法学、要看个体情况。
 * 所以这里只做"偏离常用区间的提示"，异常不等于确诊——这是医疗软件的边界。
 */
public final class PatientIntakeFields {

    private PatientIntakeFields() {
    }

    /**
     * 一个分片：键、中文名、用于在正文里定位它的标题词。
     *
     * <p>标题词要能容忍"体格检查" / "体格检查：" / "体格检查结果"这类写法，
     * 所以匹配时按"包含 + 行首"处理，不要求整行完全相等。
     */
    public record Part(String key, String label, List<String> cues) {
    }

    public static final List<Part> PARTS = List.of(
            new Part("chief_complaint", "主诉", List.of("主诉")),
            new Part("present_illness", "现病史", List.of("现病史", "本次发病", "病情简介")),
            new Part("past_history", "既往史", List.of("既往史", "既往病史")),
            new Part("medication_history", "用药史", List.of("用药史", "目前用药", "正在服用", "药物治疗")),
            new Part("allergy_history", "过敏史", List.of("过敏史", "药物过敏")),
            new Part("physical_exam", "体格检查", List.of("体格检查", "查体", "体格检查所见", "一般情况")),
            new Part("lab_exam", "检查检验", List.of("检查检验", "检验", "化验", "辅助检查", "影像", "实验室检查")),
            new Part("family_history", "家族史", List.of("家族史")),
            new Part("lifestyle", "生活方式", List.of("生活方式", "饮食", "作息", "运动习惯", "吸烟", "饮酒")),
            new Part("psychology", "心理状态", List.of("心理状态", "心理", "情绪", "精神状态")));

    /**
     * 基础字段抽取规则。
     *
     * <p>{@code (?<value>...)} 是取值组；正则必须能容忍全角冒号、空格与单位写法差异。
     */
    public record FieldRule(String key, Pattern pattern) {
    }

    public static final List<FieldRule> PROFILE_RULES = List.of(
            new FieldRule("name", Pattern.compile(
                    "姓\\s*名\\s*[:：]?\\s*(?<value>[\\u4e00-\\u9fa5][\\u4e00-\\u9fa5·]{1,7})")),
            new FieldRule("gender", Pattern.compile(
                    "性\\s*别\\s*[:：]?\\s*(?<value>男性|女性|男|女|MALE|FEMALE)")),
            // 出生日期：OCR 常见的标签缺字要能容错——"出生日期"被识别成"生日期"很常见，
            // 所以标签写成"出?生日期"（"出"可有可无），并另给"生日"这种简写。
            new FieldRule("birthDate", Pattern.compile(
                    "(?:出?生\\s*(?:日期|年月\\s*日?)|生\\s*日)\\s*[:：]?\\s*"
                            + "(?<value>\\d{4}\\s*[-/年.]\\s*\\d{1,2}\\s*[-/月.]\\s*\\d{1,2}\\s*日?)")),
            new FieldRule("phone", Pattern.compile("(?<value>1[3-9]\\d{9})")),
            // 身高体重：带单位的写法优先（"170cm"），无单位的次之（"身高 170"）。
            // 数值后必须有边界，否则 "1700" 会被截成 "170"。
            new FieldRule("heightCm", Pattern.compile(
                    "身\\s*高\\s*[:：]?\\s*(?<value>\\d{2,3}(?:\\.\\d{1,2})?)\\s*(?:cm|CM|厘米|公分)(?![\\d.])")),
            new FieldRule("heightCm", Pattern.compile(
                    "身\\s*高\\s*[:：]?\\s*(?<value>\\d{2,3}(?:\\.\\d{1,2})?)(?![\\s]*(?:cm|CM|厘米|公分)|[\\d.])")),
            new FieldRule("weightKg", Pattern.compile(
                    "体\\s*重\\s*[:：]?\\s*(?<value>\\d{2,3}(?:\\.\\d{1,2})?)\\s*(?:kg|KG|公斤|千克)(?![\\d.])")),
            new FieldRule("weightKg", Pattern.compile(
                    "体\\s*重\\s*[:：]?\\s*(?<value>\\d{2,3}(?:\\.\\d{1,2})?)(?![\\s]*(?:kg|KG|公斤|千克)|[\\d.])")));

    /**
     * 指标抽取规则。
     *
     * <p>{@code low}/{@code high} 是常用成人参考区间；{@code null} 表示这一端不判。
     * 单测里逐条验证边界值，避免"规则写错了但没人发现"。
     *
     * <p>一个指标可能有多条写法（如"收缩压 130" 与"血压 130/80"），
     * 所以是模式列表而不是单个正则——Java 不允许同名的重复捕获组。
     *
     * <p><b>数值后面必须跟边界断言</b>（{@code (?![\d.])}）。少了它，
     * "血糖 5.60" 会被截成 "5.6"（丢精度），"身高 1700" 会被截成 "170"（读错数据）。
     *
     * <p>{@code rejectBefore} 是<b>紧邻标签的禁忌前缀</b>。它解决的是这一类错误：
     * "餐后2小时血糖 7.8" 里若把 7.8 记进空腹血糖，参考区间用错，
     * 就会被判成"空腹血糖升高"——一个会造成临床误判的错误。
     * 用正则负向前查覆盖不全（"餐后2小时"、"餐后两小时"、"餐后 2 小时"、
     * 中间还可能有空格），所以改成在匹配位置上做前缀尾部判定。
     */
    public record MetricRule(String code, String name, String unit, List<Pattern> patterns,
                             String low, String high, List<Pattern> rejectBefore) {

        public MetricRule(String code, String name, String unit, List<Pattern> patterns,
                          String low, String high) {
            this(code, name, unit, patterns, low, high, List.of());
        }

        public BigDecimal lowValue() {
            return low == null ? null : new BigDecimal(low);
        }

        public BigDecimal highValue() {
            return high == null ? null : new BigDecimal(high);
        }
    }

    /** 数值后的边界断言：后面不能再跟数字或小数点，否则会截断精度。 */
    private static final String VALUE = "(?<value>\\d+(?:\\.\\d+)?)(?![\\d.])";

    /**
     * 禁忌前缀：标签前紧邻这些写法时，本次匹配作废。
     *
     * <p>写成正则而不是字符串列表，是因为 "餐后2小时"、"餐后两小时"、"餐后 2 h"
     * 这些变体穷举不尽——而且正则是按<b>尾部</b>匹配的，
     * "主诉：餐后不适，今日血糖 5.6" 里的 "餐后" 离标签够远，不会被误否决。
     */
    private static final Pattern REJECT_POSTPRANDIAL =
            Pattern.compile("(?:餐\\s*后|餐\\s*后\\s*(?:2|两|二|1|一)?\\s*(?:小时|h|H)?)\\s*[:：]?\\s*$");
    private static final Pattern REJECT_RANDOM = Pattern.compile("随机\\s*[:：]?\\s*$");

    public static final List<MetricRule> METRIC_RULES = List.of(
            new MetricRule("SBP", "收缩压", "mmHg", List.of(
                    Pattern.compile("收\\s*缩\\s*压\\s*[:：]?\\s*" + VALUE),
                    Pattern.compile("血\\s*压\\s*[:：]?\\s*(?<value>\\d{2,3})(?![\\d.])\\s*/")),
                    "90", "139"),
            new MetricRule("DBP", "舒张压", "mmHg", List.of(
                    Pattern.compile("舒\\s*张\\s*压\\s*[:：]?\\s*" + VALUE),
                    Pattern.compile("血\\s*压\\s*[:：]?\\s*\\d{2,3}(?![\\d.])\\s*/\\s*" + VALUE)),
                    "60", "89"),
            // 空腹血糖：只认明确带"空腹"或裸"血糖"的写法。
            // "餐后2小时血糖"绝不能落进 FBG——两者参考区间不同，
            // 混进来会让餐后血糖被判成"空腹血糖升高"，是会造成临床误判的错误。
            // 禁忌前缀用正则描述：标签前紧邻 "餐后（2小时|两小时|2h|1小时）" 或 "随机" 时否决。
            new MetricRule("FBG", "空腹血糖", "mmol/L", List.of(
                    Pattern.compile("空\\s*腹\\s*血\\s*糖\\s*[:：]?\\s*" + VALUE),
                    Pattern.compile("血\\s*糖\\s*[:：]?\\s*" + VALUE)),
                    "3.9", "6.1", List.of(REJECT_POSTPRANDIAL, REJECT_RANDOM)),
            // 餐后血糖单列一项：参考区间与空腹不同，混在一起必错。
            new MetricRule("PPG", "餐后血糖", "mmol/L", List.of(
                    Pattern.compile("餐\\s*后\\s*(?:2|两|二)?\\s*(?:小时|h|H)?\\s*血\\s*糖\\s*[:：]?\\s*" + VALUE)),
                    null, "7.8"),
            new MetricRule("HbA1c", "糖化血红蛋白", "%", List.of(
                    Pattern.compile("糖化血红蛋白(?:\\s*[（(][^）)]*[）)])?\\s*[:：]?\\s*" + VALUE),
                    Pattern.compile("Hb\\s*A1c\\s*[:：]?\\s*" + VALUE, Pattern.CASE_INSENSITIVE)),
                    "4.0", "6.0"),
            new MetricRule("TC", "总胆固醇", "mmol/L", List.of(
                    Pattern.compile("总胆固醇\\s*[:：]?\\s*" + VALUE)),
                    null, "5.2"),
            new MetricRule("TG", "甘油三酯", "mmol/L", List.of(
                    Pattern.compile("(?:甘油三酯|三酰甘油)\\s*[:：]?\\s*" + VALUE)),
                    null, "1.7"),
            new MetricRule("HDL", "高密度脂蛋白胆固醇", "mmol/L", List.of(
                    Pattern.compile("高密度脂蛋白(?:胆固醇)?\\s*[:：]?\\s*" + VALUE)),
                    "1.0", null),
            new MetricRule("LDL", "低密度脂蛋白胆固醇", "mmol/L", List.of(
                    Pattern.compile("低密度脂蛋白(?:胆固醇)?\\s*[:：]?\\s*" + VALUE)),
                    null, "3.4"),
            new MetricRule("ALT", "丙氨酸氨基转移酶", "U/L", List.of(
                    Pattern.compile("(?:丙氨酸氨基转移酶|谷丙转氨酶|ALT)\\s*[:：]?\\s*" + VALUE,
                            Pattern.CASE_INSENSITIVE)),
                    null, "40"),
            new MetricRule("AST", "天门冬氨酸氨基转移酶", "U/L", List.of(
                    Pattern.compile("(?:天门冬氨酸氨基转移酶|谷草转氨酶|AST)\\s*[:：]?\\s*" + VALUE,
                            Pattern.CASE_INSENSITIVE)),
                    null, "40"),
            new MetricRule("Cr", "肌酐", "μmol/L", List.of(
                    Pattern.compile("肌酐\\s*[:：]?\\s*" + VALUE)),
                    "44", "133"),
            new MetricRule("UA", "尿酸", "μmol/L", List.of(
                    Pattern.compile("(?:血\\s*)?尿\\s*酸\\s*[:：]?\\s*" + VALUE)),
                    "90", "420"));

    /** 身高体重的单位统一成 cm / kg 后的 BMI。 */
    public static final MetricRule BMI = new MetricRule("BMI", "体质指数", "kg/m²",
            List.of(), "18.5", "23.9");

    /** 分片键 → 中文名。 */
    public static String partLabel(String partKey) {
        for (Part part : PARTS) {
            if (part.key().equals(partKey)) {
                return part.label();
            }
        }
        return partKey;
    }
}
