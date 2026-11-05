package com.kangheng.healthos.spi.tracing;

/**
 * 轨迹写入器。业务线程只负责"提交一次落库动作"，同步还是异步由实现决定。
 *
 * <p>为什么不让 TracingService 直接写库：审计与诊断数据必须<b>比业务事务活得更久</b>。
 * 业务失败回滚时，最需要看到的恰恰是失败轨迹；如果和业务共用一个事务，
 * 排查现场会随回滚一起消失。异步单写线程天然避开这个问题，也顺带把
 * 每条 span 一次 INSERT 的开销从业务链路上挪走。
 */
public interface TraceWriter extends AutoCloseable {

    /** 提交一次写库动作。实现绝不能抛异常给调用方。 */
    void submit(String kind, Runnable action);

    /** 等待队列排空，用于"写完立刻查"的场景（接口查询、测试断言）。 */
    void flush();

    Stats stats();

    @Override
    default void close() {
    }

    record Stats(String mode, long submitted, long written, long dropped, int queueDepth) {
    }
}
