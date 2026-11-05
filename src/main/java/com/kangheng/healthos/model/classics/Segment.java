package com.kangheng.healthos.model.classics;

/**
 * 文本分段。[charStart, charEnd) 是原文中的精确区间，重叠部分由相邻分段共享。
 *
 * @param index     顺序号，仅用于稳定排序；检查点键不使用它，避免二分后序号变化导致缓存失效
 * @param charStart 起始字符偏移（含）
 * @param charEnd   结束字符偏移（不含）
 * @param text      分段正文
 * @param sha256    正文 SHA-256，用于检查点键与变更检测
 */
public record Segment(int index, int charStart, int charEnd, String text, String sha256) {

    public int length() {
        return charEnd - charStart;
    }

    /** 检查点键：只由区间与内容决定，同一份原文重跑必然命中。 */
    public String checkpointKey() {
        return charStart + "-" + charEnd + "-" + sha256.substring(0, 16);
    }

    public Segment withText(int newStart, String newText, String newSha256) {
        return new Segment(index, newStart, newStart + newText.length(), newText, newSha256);
    }

    public Segment reindexed(int newIndex) {
        return new Segment(newIndex, charStart, charEnd, text, sha256);
    }
}
