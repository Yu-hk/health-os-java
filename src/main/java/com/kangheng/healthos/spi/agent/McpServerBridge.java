package com.kangheng.healthos.spi.agent;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Agent Lab 背后那台「MCP 服务」的抽象。
 *
 * <p>两个实现对应两种现实：
 * <ul>
 *   <li>{@code InProcessMcpBridge}：进程内直接枚举工具目录并查库。零依赖、可单测；</li>
 *   <li>{@code StdioMcpBridge}：用 {@code ProcessBuilder} 拉起一个真的 MCP 服务子进程，
 *       走 stdio 上的 JSON-RPC。它证明的是"外部客户端能按协议接进来"，
 *       而不仅是"授权逻辑写得对"。</li>
 * </ul>
 *
 * <p><b>为什么握手要一次返回工具清单</b>：{@code initialize} 与 {@code tools/list} 是两步，
 * 但调用方真正关心的是"握手完成后看到了哪些工具"。把两步的结果合成一个记录，
 * 上层就不用自己维护协议状态机；真正的 JSON-RPC 交互发生在实现内部。
 *
 * <p>接口不依赖 {@code contract}：工具名与范围用 {@code Set<String>} 表达，
 * 由调用方做它们的枚举转换。扩展点不该知道对外 API 长什么样。
 */
public interface McpServerBridge {

    /** {@code in-process} / {@code stdio}，写进回执 detail 与目录的服务描述里。 */
    String transport();

    boolean enabled();

    /**
     * 三步握手中的前两步：{@code initialize} + {@code tools/list}。
     *
     * @param scopeWires 本次授权的范围（wire 名，如 {@code memory}）。两种实现都必须
     *                   <b>自己</b>维护"范围 → 工具"的映射再裁剪声明：映射写在服务端，
     *                   调用方只递范围。若把工具清单直接传进去让服务端照抄，
     *                   "发现集等于授权集"就成了自己跟自己比，校验也就白做了
     * @throws McpBridgeException 子进程起不来、握手超时、协议错误
     */
    Handshake handshake(Set<String> scopeWires, String owner, String patientId);

    /**
     * {@code tools/call}。
     *
     * <p>范围同样要传：服务端按自己的映射判断这个工具在本次授权里是否可见，
     * 越权工具应当被它自己拒绝（协议错误），而不是靠调用方拦。
     *
     * @return 工具的结构化输出
     * @throws McpBridgeException 工具不存在、执行失败、输出为空
     */
    Map<String, Object> callTool(Set<String> scopeWires, String tool, String owner, String patientId,
                                 Map<String, Object> arguments);

    /**
     * @param tools        服务端<b>自己声明</b>的工具清单
     * @param handshakeMs  从发 initialize 到 tools/list 返回的总耗时
     * @param initializeMs 仅 initialize 那一步的耗时（进程内实现固定 0：没有往返）
     */
    record Handshake(String protocolVersion, String serverName, String serverVersion,
                     List<String> tools, long handshakeMs, long initializeMs) {
    }
}
