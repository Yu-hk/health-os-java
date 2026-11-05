package com.kangheng.healthos.provider.ocr;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import com.kangheng.healthos.model.ocr.OcrResult;

import static org.assertj.core.api.Assertions.assertThat;

class OcrResponseParserTest {

    @Test
    @DisplayName("generic 协议：正常解析文本、置信度与包围盒")
    void parsesGenericResponse() {
        String body = """
                {"engine":"tesseract","lines":[
                  {"text":"四君子汤","confidence":0.93,"box":[100,200,300,240]},
                  {"text":"益气健脾","confidence":0.88,"box":[100,260,320,300]}
                ]}
                """;
        OcrResult result = OcrResponseParser.parseGeneric(body, "generic");
        assertThat(result.lines()).hasSize(2);
        assertThat(result.lines().get(0).text()).isEqualTo("四君子汤");
        assertThat(result.lines().get(0).confidence()).isEqualTo(0.93);
        assertThat(result.lines().get(0).x1()).isEqualTo(100);
        assertThat(result.lines().get(1).y2()).isEqualTo(300);
    }

    @Test
    @DisplayName("裸文本行会被跳过，不产生空行噪声")
    void skipsBlankLines() {
        String body = """
                {"lines":[{"text":"","confidence":0.9,"box":[0,0,1,1]},
                          {"text":"   ","confidence":0.9,"box":[0,0,1,1]},
                          {"text":"人参","confidence":0.5,"box":[0,0,1,1]}]}
                """;
        assertThat(OcrResponseParser.parseGeneric(body, "generic").lines()).hasSize(1);
    }

    @Test
    @DisplayName("缺 box 字段时坐标为 0，不抛异常")
    void toleratesMissingBox() {
        String body = "{\"lines\":[{\"text\":\"白术\",\"confidence\":0.7}]}";
        OcrResult.Line line = OcrResponseParser.parseGeneric(body, "generic").lines().get(0);
        assertThat(line.x1()).isZero();
        assertThat(line.y2()).isZero();
    }

    @Test
    @DisplayName("PaddleOCR 四点坐标转外接矩形（坐标顺序不固定）")
    void parsesPaddleFourPointRegion() {
        String body = """
                {"status":"000","results":[{
                  "text":["四君子汤","主治脾胃气虚"],
                  "confidence":[0.97,0.91],
                  "text_region":[
                     [[120,260],[320,258],[322,300],[118,302]],
                     [[120,320],[520,318],[522,360],[118,362]]
                  ]
                }]}
                """;
        OcrResult result = OcrResponseParser.parsePaddle(body, "paddle");
        assertThat(result.lines()).hasSize(2);
        OcrResult.Line first = result.lines().get(0);
        assertThat(first.text()).isEqualTo("四君子汤");
        assertThat(first.x1()).isEqualTo(118);
        assertThat(first.y1()).isEqualTo(258);
        assertThat(first.x2()).isEqualTo(322);
        assertThat(first.y2()).isEqualTo(302);
        assertThat(first.confidence()).isEqualTo(0.97);
    }

    @Test
    @DisplayName("PaddleOCR 三数组长度不一致时按最短安全取值")
    void handlesMismatchedPaddleArrays() {
        String body = """
                {"results":[{"text":["甲","乙"],"confidence":[0.9],
                  "text_region":[[[0,0],[10,0],[10,10],[0,10]]]}]}
                """;
        OcrResult result = OcrResponseParser.parsePaddle(body, "paddle");
        assertThat(result.lines()).hasSize(2);
        // 第二条没有置信度与坐标，退化为默认值而不是数组越界
        assertThat(result.lines().get(1).confidence()).isEqualTo(1.0);
        assertThat(result.lines().get(1).x1()).isZero();
    }

    @Test
    @DisplayName("非法 JSON、空响应、缺 results 都返回空结果而不是抛异常")
    void degradesToEmptyOnMalformedInput() {
        assertThat(OcrResponseParser.parseGeneric("not json", "g").isEmpty()).isTrue();
        assertThat(OcrResponseParser.parseGeneric(null, "g").isEmpty()).isTrue();
        assertThat(OcrResponseParser.parsePaddle("{\"results\":[]}", "p").isEmpty()).isTrue();
        assertThat(OcrResponseParser.parsePaddle("{\"error\":\"boom\"}", "p").isEmpty()).isTrue();
    }
}
