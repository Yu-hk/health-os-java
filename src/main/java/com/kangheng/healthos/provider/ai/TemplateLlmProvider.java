package com.kangheng.healthos.provider.ai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.kangheng.healthos.config.HealthOsProperties;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import com.kangheng.healthos.spi.ai.LlmProvider;

/**
 * 离线确定性"模型"：用规则从中医典籍文本里抽实体与关系。
 *
 * <p>存在的意义有三个：
 * <ol>
 *   <li>整条流水线在没有任何 API Key 的情况下可端到端跑通；</li>
 *   <li>单元测试有稳定的期望值，不依赖真实模型；</li>
 *   <li>演示时输出可复现，便于对照。</li>
 * </ol>
 * 它<b>不是</b>模型替代品，抽取覆盖面远小于真实 LLM，README 已注明。
 */
@Component
@ConditionalOnProperty(name = "healthos.ai.provider", havingValue = "template", matchIfMissing = true)
public class TemplateLlmProvider implements LlmProvider {

    /** 方剂：以剂型字结尾。 */
    private static final Pattern FORMULA = Pattern.compile(
            "([\\u4e00-\\u9fa5]{2,10}?(?:汤|散|丸|膏|丹|饮|煎))");

    /** 「X 主治 A、B」：给出 主治 关系。 */
    private static final Pattern TREAT = Pattern.compile(
            "([\\u4e00-\\u9fa5]{2,10}?(?:汤|散|丸|膏|丹|饮|煎|药))"
                    + "(?:主治|主之|用于治疗|可治|善治)([\\u4e00-\\u9fa5、，,]{2,40})");

    /** 「X 具有 A、B 的功效」。 */
    private static final Pattern EFFECT = Pattern.compile(
            "([\\u4e00-\\u9fa5]{2,10}?)(?:具有|具)([\\u4e00-\\u9fa5、，,]{2,30}?)(?:的功效|之功效|功效)");

    /** 「X 性温，归 Y 经」：给出 归经 关系。 */
    private static final Pattern MERIDIAN = Pattern.compile(
            "([\\u4e00-\\u9fa5]{2,10}?)(?:性[温凉寒热平]，?)?归([\\u4e00-\\u9fa5、，,]{1,12}?)经");

    private static final String[] HERB_HINTS = {
            "参", "芪", "草", "姜", "枣", "桂", "芍", "苓", "术", "归", "芎", "地", "皮", "根", "子", "花", "叶", "仁"
    };

    private static final Pattern SENTENCE = Pattern.compile("[^。；;\\n]+[。；;]?");

    /**
     * 条目式正文的字段行，如 {@code 功效：益气健脾。}、{@code 主治 : 脾胃气虚证}。
     *
     * <p>冒号两侧允许空格：OCR 输出几乎不会贴着写"主治："，而是"主治 : "。
     * 这是扫描件典籍最常见、也最容易被漏掉的一种结构。
     */
    private static final Pattern SECTION = Pattern.compile(
            "^(组成|功效|主治|出处|用法|禁忌)\\s*[:：]\\s*(.+)$");

    /** 独立成行的方剂名，作为后续字段行的归属主体。 */
    private static final Pattern FORMULA_TITLE = Pattern.compile(
            "^[\\u4e00-\\u9fa5]{2,10}(?:汤|散|丸|膏|丹|饮|煎)$");

    private final HealthOsProperties properties;

    public TemplateLlmProvider(HealthOsProperties properties) {
        this.properties = properties;
    }

    @Override
    public String name() {
        return "template-rule-based";
    }

    @Override
    public ExtractionResult extract(String documentTitle, String segmentText) {
        if (segmentText == null || segmentText.isBlank()) {
            return ExtractionResult.empty();
        }
        Map<String, ExtractedEntity> entities = new LinkedHashMap<>();
        List<ExtractedRelation> relations = new ArrayList<>();

        for (String sentence : sentences(segmentText)) {
            extractTreat(sentence, entities, relations);
            extractEffect(sentence, entities, relations);
            extractMeridian(sentence, entities, relations);
            extractBareFormulas(sentence, entities);
        }
        extractSectioned(segmentText, entities, relations);

        int maxEntities = properties.getExtraction().getMaxEntitiesPerSegment();
        int maxRelations = properties.getExtraction().getMaxRelationsPerSegment();
        List<ExtractedEntity> limitedEntities = entities.values().stream().limit(maxEntities).toList();
        List<ExtractedRelation> limitedRelations = relations.stream()
                .distinct()
                .limit(maxRelations)
                .toList();
        return new ExtractionResult(limitedEntities, limitedRelations);
    }

    @Override
    public String answer(String question, String contextBlock) {
        if (contextBlock == null || contextBlock.isBlank()) {
            return "";
        }
        List<String> lines = contextBlock.lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .limit(6)
                .toList();
        StringBuilder builder = new StringBuilder("依据典籍原文，与「").append(question).append("」相关的记载如下：\n");
        for (String line : lines) {
            builder.append("· ").append(line).append('\n');
        }
        builder.append("以上内容仅来自所列条目，未覆盖的细节请补充原文。");
        return builder.toString();
    }

    /**
     * 条目式抽取：跟踪"当前方剂名"，把后续的 组成/功效/主治 行归到它名下。
     *
     * <p>为什么单独加这条路径：叙述式句子（"四君子汤主治脾胃气虚"）与条目式
     * （标题一行 + "主治 : xxx" 一行）是完全不同的排版，而 OCR 出来的典籍几乎全是后者。
     * 只支持叙述式会得到"OCR 成功但抽取 0 关系"的假成功。
     */
    private void extractSectioned(String text, Map<String, ExtractedEntity> entities,
                                  List<ExtractedRelation> relations) {
        String current = null;
        for (String rawLine : text.split("\\R")) {
            String line = rawLine.strip();
            if (line.isEmpty()) {
                continue;
            }
            Matcher section = SECTION.matcher(line);
            if (section.matches()) {
                String field = section.group(1);
                String value = section.group(2).strip();
                if (current == null || value.isEmpty()) {
                    continue;
                }
                switch (field) {
                    case "组成" -> addIngredients(current, value, line, entities, relations);
                    case "功效" -> addTargets(current, value, line, "功效", "EFFECT", 0.80, entities, relations);
                    case "主治" -> addTargets(current, value, line, "主治", "SYMPTOM", 0.85, entities, relations);
                    default -> {
                        // 出处/用法/禁忌：暂不作为关系，避免把版本信息混进医学结论
                    }
                }
                continue;
            }
            if (FORMULA_TITLE.matcher(line).matches()) {
                current = line;
                entities.putIfAbsent(current, new ExtractedEntity(current, "FORMULA", List.of()));
                continue;
            }
            // OCR 会把方剂名认错字（实测"四君子汤"常被认成"四君子注"），此时严格规则失效，
            // 整段条目的关系会全部丢失。降级方案：当还没有归属主体时，把"短、纯中文、无标点"的行
            // 当作标题。只在 current==null 时启用，避免把药材列表当成新标题。
            if (current == null && isProbableTitle(line)) {
                current = line;
                entities.putIfAbsent(current, new ExtractedEntity(current, "FORMULA", List.of()));
            }
        }
    }

    /** 疑似标题：2–10 个汉字、不含标点、不以句读结尾。扫描件标题行几乎都符合。 */
    static boolean isProbableTitle(String line) {
        if (line == null) {
            return false;
        }
        String value = line.strip();
        int length = value.length();
        if (length < 2 || length > 10) {
            return false;
        }
        for (char ch : value.toCharArray()) {
            if (ch < '\u4e00' || ch > '\u9fa5') {
                return false;
            }
        }
        return true;
    }

    private void addIngredients(String formula, String value, String evidence,
                                Map<String, ExtractedEntity> entities, List<ExtractedRelation> relations) {
        for (String raw : value.split("[、，,]")) {
            String name = raw.strip();
            // "甘草各等分" → "甘草"：剂量描述不属于药名
            int cut = name.indexOf('各');
            if (cut > 0) {
                name = name.substring(0, cut);
            }
            name = name.replaceAll("[。；;\\s]+$", "").strip();
            if (name.length() < 2 || name.length() > 6) {
                continue;
            }
            entities.putIfAbsent(name, new ExtractedEntity(name, "HERB", List.of()));
            relations.add(new ExtractedRelation(formula, name, "组成", 0.90, evidence));
        }
    }

    private void addTargets(String formula, String value, String evidence, String relationType,
                            String entityType, double confidence,
                            Map<String, ExtractedEntity> entities, List<ExtractedRelation> relations) {
        for (String raw : value.split("[、，,]")) {
            String target = raw.replaceAll("[。；;\\s]+$", "").strip();
            if (target.length() < 2 || target.length() > 20) {
                continue;
            }
            entities.putIfAbsent(target, new ExtractedEntity(target, entityType, List.of()));
            relations.add(new ExtractedRelation(formula, target, relationType, confidence, evidence));
        }
    }

    private void extractTreat(String sentence, Map<String, ExtractedEntity> entities,
                              List<ExtractedRelation> relations) {
        Matcher matcher = TREAT.matcher(sentence);
        while (matcher.find()) {
            String formula = matcher.group(1);
            entities.putIfAbsent(formula, new ExtractedEntity(formula, typeOf(formula), List.of()));
            for (String raw : matcher.group(2).split("[、，,]")) {
                String target = raw.trim();
                if (target.length() < 2 || target.length() > 20) {
                    continue;
                }
                entities.putIfAbsent(target, new ExtractedEntity(target, "SYMPTOM", List.of()));
                relations.add(new ExtractedRelation(formula, target, "主治", 0.85, sentence.trim()));
            }
        }
    }

    private void extractEffect(String sentence, Map<String, ExtractedEntity> entities,
                               List<ExtractedRelation> relations) {
        Matcher matcher = EFFECT.matcher(sentence);
        while (matcher.find()) {
            String source = matcher.group(1);
            if (source.length() < 2) {
                continue;
            }
            entities.putIfAbsent(source, new ExtractedEntity(source, typeOf(source), List.of()));
            for (String raw : matcher.group(2).split("[、，,]")) {
                String target = raw.trim();
                if (target.length() < 2) {
                    continue;
                }
                entities.putIfAbsent(target, new ExtractedEntity(target, "EFFECT", List.of()));
                relations.add(new ExtractedRelation(source, target, "功效", 0.8, sentence.trim()));
            }
        }
    }

    private void extractMeridian(String sentence, Map<String, ExtractedEntity> entities,
                                 List<ExtractedRelation> relations) {
        Matcher matcher = MERIDIAN.matcher(sentence);
        while (matcher.find()) {
            String source = matcher.group(1);
            String meridians = matcher.group(2);
            if (source.length() < 2) {
                continue;
            }
            entities.putIfAbsent(source, new ExtractedEntity(source, typeOf(source), List.of()));
            for (String raw : meridians.split("[、，,]")) {
                String target = raw.trim();
                if (target.length() < 1) {
                    continue;
                }
                String meridian = target + "经";
                entities.putIfAbsent(meridian, new ExtractedEntity(meridian, "MERIDIAN", List.of()));
                relations.add(new ExtractedRelation(source, meridian, "归经", 0.75, sentence.trim()));
            }
        }
    }

    private void extractBareFormulas(String sentence, Map<String, ExtractedEntity> entities) {
        Matcher matcher = FORMULA.matcher(sentence);
        while (matcher.find()) {
            String formula = matcher.group(1);
            if (formula.length() >= 3) {
                entities.putIfAbsent(formula, new ExtractedEntity(formula, "FORMULA", List.of()));
            }
        }
    }

    private List<String> sentences(String text) {
        List<String> result = new ArrayList<>();
        Matcher matcher = SENTENCE.matcher(text);
        while (matcher.find()) {
            String value = matcher.group().trim();
            if (!value.isEmpty()) {
                result.add(value);
            }
        }
        return result;
    }

    static String typeOf(String name) {
        if (name == null) {
            return "UNKNOWN";
        }
        if (name.endsWith("汤") || name.endsWith("散") || name.endsWith("丸") || name.endsWith("膏")
                || name.endsWith("丹") || name.endsWith("饮") || name.endsWith("煎")) {
            return "FORMULA";
        }
        for (String hint : HERB_HINTS) {
            if (name.endsWith(hint)) {
                return "HERB";
            }
        }
        return "CONCEPT";
    }
}
