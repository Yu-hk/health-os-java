package com.kangheng.healthos.spi.patient;

/**
 * 从病历/体检资料里抽取结构化字段。
 *
 * <p>对应原工程 {@code patient_provider.py} + {@code model_route_service} 里
 * "客户资料标准化提取"这条业务主线的能力。
 *
 * <p>为什么单独抽一个 SPI 而不是直接用 {@code LlmProvider}：
 * {@code LlmProvider} 是"典籍实体关系抽取 + 问答"，字段语义完全不同；
 * 而且医疗字段抽取必须能离线跑（模板实现），才能在测试里验证抽取规则本身。
 *
 * <p><b>实现不得凭空造值</b>：原文里没有的字段就留空，不许用常识补。
 * 缺失的字段由人工核查环节补，不是由模型猜。
 */
public interface PatientRecordExtractor {

    /** 实现名，会记进观测轨迹与录入记录，便于回溯"这份档案是哪套规则抄出来的"。 */
    String name();

    /**
     * 抽取一个分片的结构化字段。
     *
     * @param partKey 分片键（如 {@code chief_complaint}）
     * @param text    分片原文
     * @return JSON 对象字符串，至少包含 {@code fields} 与 {@code metrics} 两个数组
     * @throws PatientExtractionException 抽取失败时抛出，**不得返回空结果伪装成功**
     */
    String extract(String partKey, String text);

    /**
     * 带业务主线的抽取入口。
     *
     * <p>调用方传业务实例 ID，实现按 {@code PATIENT_RECORD} 主线取该实例冻结的那一版模型配置，
     * 而不是用全局 {@code healthos.ai.*}。默认转发，让离线规则实现保持不变。
     */
    default String extract(com.kangheng.healthos.spi.ai.LlmProvider.RouteContext context,
                           String partKey, String text) {
        return extract(partKey, text);
    }

    /** 抽取失败。刻意不继承 RuntimeException 之外的任何东西：它是可预期的失败。 */
    class PatientExtractionException extends RuntimeException {

        public PatientExtractionException(String message) {
            super(message);
        }

        public PatientExtractionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
