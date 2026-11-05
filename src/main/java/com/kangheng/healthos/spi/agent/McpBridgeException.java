package com.kangheng.healthos.spi.agent;

/**
 * MCP 桥接失败。
 *
 * <p>只带一个 {@code code} 与一句人话，不带堆栈、不带子进程原始输出——
 * 回执 detail 会落库，堆栈里可能夹带命令行、令牌、文件路径。
 * 原始输出留给日志（带长度上限）。
 *
 * <p>{@code stage} 是"挂在哪一步"：{@code initialize} / {@code tools/list} /
 * {@code tools/call}，或 null（不知道）。上层靠它决定把哪条 event 记成 error——
 * 没有它就只能把失败一律归到 initialize，回执里"哪一步挂了"这个最有用的信息就丢了。
 */
public class McpBridgeException extends RuntimeException {

    private final String code;
    private final String stage;

    public McpBridgeException(String code, String message) {
        this(code, message, null, null);
    }

    public McpBridgeException(String code, String message, Throwable cause) {
        this(code, message, null, cause);
    }

    public McpBridgeException(String code, String message, String stage, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.stage = stage;
    }

    public String code() {
        return code;
    }

    /** 失败所在协议步骤，可能为 null。 */
    public String stage() {
        return stage;
    }

    /** 同一个失败换个阶段标记：用于把"进程起不来"这类无阶段错误归到发起它的那一步。 */
    public McpBridgeException atStage(String stage) {
        return this.stage == null
                ? new McpBridgeException(code, getMessage(), stage, this)
                : this;
    }
}
