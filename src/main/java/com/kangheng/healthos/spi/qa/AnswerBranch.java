package com.kangheng.healthos.spi.qa;

/**
 * 问答分支扩展点：一路视角 = 一个实现。
 *
 * <p>原工程在 {@code qa_prompts.BRANCHES} 里把三路写死成 {@code ('ragflow','modern','tcm')}，
 * 加第四路要同时改 worker、节点表、prompt 与测试。这里改成接口 + Spring 收集：
 * <b>新增一个 {@code @Component implements AnswerBranch} 即可自动参与并行问答</b>，
 * 编排器与接口层都不用动。
 *
 * <p>实现约束（编排器不会替你兜底）：
 * <ul>
 *   <li>必须幂等、无副作用、不写库——落库由 {@code QaService} 统一做；</li>
 *   <li>不得吞异常后返回空字符串冒充成功；没依据就返回
 *       {@link BranchResult#INSUFFICIENT}，出错就抛异常（由编排器转成 FAILED）；</li>
 *   <li>引用的 {@code quote} 必须逐字来自原文/证据，禁止自己改写后再挂出处。</li>
 * </ul>
 */
public interface AnswerBranch {

    /** 分支标识，写入响应与落库 JSON。建议短小写英文，如 {@code ragflow} / {@code tcm}。 */
    String key();

    /** 展示名，给前端与日志看。 */
    String label();

    /** 执行该路。允许耗时操作，但需自行控制在超时预算内（编排器另有硬超时兜底）。 */
    BranchResult answer(BranchContext context);

    /** 汇总时的排序，越小越靠前。默认按注册顺序。 */
    default int order() {
        return 100;
    }
}
