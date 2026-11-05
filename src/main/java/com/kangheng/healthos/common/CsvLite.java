package com.kangheng.healthos.common;

import java.util.ArrayList;
import java.util.List;

/**
 * 最小 CSV 解析器：只做批量导入需要的那一档（引号、转义引号、CRLF）。
 *
 * <p>为什么不用现成库：导入格式是我们自己定的几列，引一个 commons-csv 只为这点事
 * 并不划算。但要清楚它的边界——<b>不处理跨行字段里的裸换行</b>，
 * 这种数据在导入时会被当成两行，行号因此能对上（每行都会被单独校验并报错），
 * 不会静默吞掉数据。
 */
public final class CsvLite {

    private CsvLite() {
    }

    public static List<List<String>> parse(String text) {
        List<List<String>> rows = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return rows;
        }
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    field.append(c);
                }
                continue;
            }
            switch (c) {
                case '"' -> quoted = true;
                case ',' -> {
                    row.add(field.toString());
                    field.setLength(0);
                }
                case '\n' -> {
                    row.add(field.toString());
                    rows.add(row);
                    row = new ArrayList<>();
                    field.setLength(0);
                }
                case '\r' -> {
                    // 交给 '\n' 处理，避免 CRLF 被当成空行
                }
                default -> field.append(c);
            }
        }
        if (field.length() > 0 || !row.isEmpty()) {
            row.add(field.toString());
            rows.add(row);
        }
        return rows;
    }

    public static boolean isBlank(List<String> row) {
        return row.stream().allMatch(value -> value == null || value.isBlank());
    }
}
