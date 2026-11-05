package com.kangheng.healthos.common;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CsvLiteTest {

    @Test
    @DisplayName("普通行按逗号切分，CRLF 不产生空行")
    void parsesSimpleRows() {
        List<List<String>> rows = CsvLite.parse("a,b\r\n1,2\r\n");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsExactly("a", "b");
        assertThat(rows.get(1)).containsExactly("1", "2");
    }

    @Test
    @DisplayName("引号内的逗号不当分隔符")
    void keepsCommasInsideQuotes() {
        List<List<String>> rows = CsvLite.parse("name,note\n张三,\"高血压,糖尿病\"\n");
        assertThat(rows.get(1)).containsExactly("张三", "高血压,糖尿病");
    }

    @Test
    @DisplayName("两个连续引号还原成一个")
    void unescapesDoubleQuotes() {
        List<List<String>> rows = CsvLite.parse("a\n\"他说\"\"你好\"\"\"\n");
        assertThat(rows.get(1)).containsExactly("他说\"你好\"");
    }

    @Test
    @DisplayName("空行可被识别，不会当成一条数据")
    void detectsBlankRows() {
        List<List<String>> rows = CsvLite.parse("a,b\n,\n1,2\n");
        assertThat(CsvLite.isBlank(rows.get(1))).isTrue();
        assertThat(CsvLite.isBlank(rows.get(0))).isFalse();
    }

    @Test
    @DisplayName("空输入返回空表，不抛异常")
    void emptyInputYieldsEmptyList() {
        assertThat(CsvLite.parse("")).isEmpty();
        assertThat(CsvLite.parse(null)).isEmpty();
    }
}
