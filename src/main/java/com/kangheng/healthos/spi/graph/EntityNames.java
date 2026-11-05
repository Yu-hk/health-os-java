package com.kangheng.healthos.spi.graph;

/**
 * 实体名的归一化规则。
 *
 * <p><b>为什么必须只有一份</b>：归一化后的名字同时用于两处——合并时的去重键，
 * 和图节点 ID（{@code stableId("gnd", documentId, type|名称)}）的构成。
 * 两边规则一旦不一致，合并器认为"同一个实体"的两条记录，在图存储里会变成
 * 两个节点，多跳查询立刻断链，而且这种错不会报错，只会让图谱悄悄变稀疏。
 *
 * <p>所以它放在 {@code spi.graph}：{@code service}（合并器）与 {@code provider}
 * （图存储实现）都依赖契约层，谁也不能私自改口径。
 */
public final class EntityNames {

    private EntityNames() {
    }

    /**
     * 去掉所有空白（含全角空格 U+3000）并修剪；{@code null} 归一化为空串。
     *
     * <p>只去空白不做同义归并："人参"与"人參"是不同的书写形式，是否需要视为同一个
     * 实体取决于业务判断，不该由工具函数隐式决定——那会让去重变得不可解释。
     */
    public static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("[\\s\\u3000]+", "").trim();
    }
}
