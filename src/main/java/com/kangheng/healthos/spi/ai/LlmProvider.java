package com.kangheng.healthos.spi.ai;

import java.util.List;

/**
 * 模型能力抽象。原项目对应四个 {@code *_provider.py}；Java 切片收敛为一个接口，
 * 由实现类决定协议（模板 / OpenAI 兼容）。
 */
public interface LlmProvider {

    /** 分段抽取实体关系。失败必须抛 {@link LlmException}，不得返回空结果伪装成功。 */
    ExtractionResult extract(String documentTitle, String segmentText) throws LlmException;

    /** 基于给定上下文回答，要求严格约束在上下文内。 */
    String answer(String question, String contextBlock) throws LlmException;

    /**
     * 自由对话（自定义 system + user 提示）。
     *
     * <p>给"结构化字段抽取"这类不属于典籍抽取也不属于问答的用途用。
     * 默认不支持——模板实现无法凭空生成结构化结果，硬撑着返回一段编造的数字更糟。
     */
    default String chat(String systemPrompt, String userPrompt) throws LlmException {
        throw LlmException.nonRetryable("当前模型实现不支持自由对话", null);
    }

    /**
     * 带业务主线的调用入口。
     *
     * <p>实现类应当按 {@link RouteContext} 去解析该主线此刻生效的模型配置（含密钥与超时），
     * 而不是用全局 {@code healthos.ai.*}。默认实现转发到不带主线的方法，
     * 让离线模板实现与既有调用点保持不变。
     *
     * <p>{@code bizType + bizId} 用来锁定"这个业务实例用哪一版配置"：任务执行途中
     * 管理员改了路由，在途任务仍按冻结的那一版跑完。
     */
    default String chat(RouteContext context, String systemPrompt, String userPrompt) throws LlmException {
        return chat(systemPrompt, userPrompt);
    }

    default String answer(RouteContext context, String question, String contextBlock) throws LlmException {
        return answer(question, contextBlock);
    }

    default ExtractionResult extract(RouteContext context, String documentTitle,
                                     String segmentText) throws LlmException {
        return extract(documentTitle, segmentText);
    }

    /** 一次模型调用的业务归属：哪条主线、哪个业务实例。 */
    record RouteContext(String pipelineKey, String bizType, String bizId) {

        public static RouteContext of(String pipelineKey, String bizType, String bizId) {
            return new RouteContext(pipelineKey, bizType, bizId);
        }
    }

    String name();

    record ExtractedEntity(String name, String type, List<String> aliases) {
    }

    record ExtractedRelation(String source, String target, String type,
                             double confidence, String evidence) {
    }

    record ExtractionResult(List<ExtractedEntity> entities, List<ExtractedRelation> relations) {

        public static ExtractionResult empty() {
            return new ExtractionResult(List.of(), List.of());
        }

        public boolean isEmpty() {
            return entities.isEmpty() && relations.isEmpty();
        }
    }
}
