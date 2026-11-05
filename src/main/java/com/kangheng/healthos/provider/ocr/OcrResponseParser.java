package com.kangheng.healthos.provider.ocr;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

import com.kangheng.healthos.common.Json;
import com.kangheng.healthos.model.ocr.OcrResult;

/**
 * OCR 响应解析。
 *
 * <p>支持两种协议，都是纯函数，单测直接喂 JSON 字符串：
 * <ul>
 *   <li><b>generic</b>：本项目 {@code tools/ocr-service} 的契约，行结构扁平，字段直白；</li>
 *   <li><b>paddle</b>：PaddleOCR hubserving 的 {@code /predict/ocr_system}，
 *       响应把 text / confidence / text_region 拆成三个等长数组，坐标是四点四边形。</li>
 * </ul>
 * 解析失败不抛异常，返回空结果——OCR 服务偶尔多返回字段不应该让整条流水线失败，
 * 但"识别出 0 行"会由上层判定为空文档并明确报错，不会静默产出空报告。
 */
public final class OcrResponseParser {

    private OcrResponseParser() {
    }

    /** 解析 generic 协议：{@code {"lines":[{"text":..,"confidence":..,"box":[x1,y1,x2,y2]}]}} */
    public static OcrResult parseGeneric(String body, String engine) {
        JsonNode root = readTree(body);
        if (root == null) {
            return OcrResult.empty(engine);
        }
        JsonNode lines = root.path("lines");
        if (!lines.isArray()) {
            return OcrResult.empty(engine);
        }
        List<OcrResult.Line> parsed = new ArrayList<>();
        for (JsonNode node : lines) {
            String text = node.path("text").asText("");
            if (text.isBlank()) {
                continue;
            }
            double confidence = node.path("confidence").asDouble(1.0);
            JsonNode box = node.path("box");
            int x1 = 0;
            int y1 = 0;
            int x2 = 0;
            int y2 = 0;
            if (box.isArray() && box.size() >= 4) {
                x1 = box.get(0).asInt();
                y1 = box.get(1).asInt();
                x2 = box.get(2).asInt();
                y2 = box.get(3).asInt();
            }
            parsed.add(new OcrResult.Line(text, confidence, x1, y1, x2, y2));
        }
        return new OcrResult(engine, parsed);
    }

    /**
     * 解析 PaddleOCR 协议：
     * {@code {"results":[{"text":[..],"confidence":[..],"text_region":[[[x,y] x4], ..]}]}}
     */
    public static OcrResult parsePaddle(String body, String engine) {
        JsonNode root = readTree(body);
        if (root == null) {
            return OcrResult.empty(engine);
        }
        JsonNode results = root.path("results");
        if (!results.isArray() || results.isEmpty()) {
            return OcrResult.empty(engine);
        }
        JsonNode first = results.get(0);
        JsonNode texts = first.path("text");
        JsonNode confidences = first.path("confidence");
        JsonNode regions = first.path("text_region");
        if (!texts.isArray()) {
            return OcrResult.empty(engine);
        }
        List<OcrResult.Line> parsed = new ArrayList<>();
        for (int i = 0; i < texts.size(); i++) {
            String text = texts.get(i).asText("");
            if (text.isBlank()) {
                continue;
            }
            double confidence = confidences.isArray() && i < confidences.size()
                    ? confidences.get(i).asDouble(1.0) : 1.0;
            int[] box = boundingBox(regions, i);
            parsed.add(new OcrResult.Line(text, confidence, box[0], box[1], box[2], box[3]));
        }
        return new OcrResult(engine, parsed);
    }

    /** 四点四边形 → 外接矩形；PaddleOCR 的坐标顺序不固定，因此取 min/max 而不是拿第 0/2 个点。 */
    private static int[] boundingBox(JsonNode regions, int index) {
        if (regions == null || !regions.isArray() || index >= regions.size()) {
            return new int[]{0, 0, 0, 0};
        }
        JsonNode region = regions.get(index);
        if (!region.isArray() || region.isEmpty()) {
            return new int[]{0, 0, 0, 0};
        }
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (JsonNode point : region) {
            if (!point.isArray() || point.size() < 2) {
                continue;
            }
            int x = point.get(0).asInt();
            int y = point.get(1).asInt();
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
        }
        if (minX == Integer.MAX_VALUE) {
            return new int[]{0, 0, 0, 0};
        }
        return new int[]{minX, minY, maxX, maxY};
    }

    private static JsonNode readTree(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return Json.mapper().readTree(body);
        } catch (Exception ex) {
            return null;
        }
    }
}
