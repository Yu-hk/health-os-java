package com.kangheng.healthos.text;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 证据定位：把模型给出的原文引用映射回 markdown 的精确字符区间与页码。
 *
 * <p>模型（尤其真实 LLM）经常会改动空白、换行或标点周围的空格，
 * 所以先做精确匹配，失败后把两边的连续空白折叠成单个空格再匹配，
 * 并保留"折叠后位置 → 原始位置"的映射以还原真实偏移。
 * 页码按 PDF 抽取时插入的换页符 {@code \f} 计数。
 */
public final class EvidenceLocator {

    private static final char PAGE_BREAK = '\f';

    private EvidenceLocator() {
    }

    public record Located(String quote, int charStart, int charEnd, int page) {
    }

    public static Optional<Located> locate(String markdown, String quote) {
        if (markdown == null || markdown.isEmpty() || quote == null || quote.isBlank()) {
            return Optional.empty();
        }
        String needle = quote.strip();
        int exact = markdown.indexOf(needle);
        if (exact >= 0) {
            return Optional.of(new Located(needle, exact, exact + needle.length(), pageAt(markdown, exact)));
        }
        Optional<Located> collapsed = locateCollapsed(markdown, needle);
        if (collapsed.isPresent()) {
            return collapsed;
        }
        for (int length : new int[] {40, 24, 12}) {
            if (needle.length() > length) {
                String prefix = needle.substring(0, length);
                int index = markdown.indexOf(prefix);
                if (index >= 0) {
                    int end = Math.min(index + length, markdown.length());
                    return Optional.of(new Located(markdown.substring(index, end), index, end,
                            pageAt(markdown, index)));
                }
            }
        }
        return Optional.empty();
    }

    /** 页码从 1 开始；无换页符时恒为 1。 */
    public static int pageAt(String markdown, int offset) {
        if (markdown == null || offset <= 0) {
            return 1;
        }
        int page = 1;
        int limit = Math.min(offset, markdown.length());
        for (int i = 0; i < limit; i++) {
            if (markdown.charAt(i) == PAGE_BREAK) {
                page++;
            }
        }
        return page;
    }

    private static Optional<Located> locateCollapsed(String markdown, String needle) {
        StringBuilder collapsed = new StringBuilder(markdown.length());
        List<Integer> origin = new ArrayList<>(markdown.length());
        for (int i = 0; i < markdown.length(); i++) {
            char ch = markdown.charAt(i);
            // 空白全部剔除而不是折叠成单空格：模型既可能合并多个空白，也可能整段去掉空格
            if (Character.isWhitespace(ch) || ch == '\u3000') {
                continue;
            }
            collapsed.append(ch);
            origin.add(i);
        }
        String haystack = collapsed.toString();
        String target = needle.replaceAll("[\\s\\u3000]+", "");
        if (target.isEmpty()) {
            return Optional.empty();
        }
        int index = haystack.indexOf(target);
        if (index < 0) {
            return Optional.empty();
        }
        int start = origin.get(index);
        int end = origin.get(index + target.length() - 1) + 1;
        return Optional.of(new Located(markdown.substring(start, end), start, end, pageAt(markdown, start)));
    }
}
