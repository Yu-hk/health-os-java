package com.kangheng.healthos.config;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;

import com.kangheng.healthos.provider.agent.InMemoryMcpToolGrants;
import com.kangheng.healthos.provider.agent.StdioMcpBridge;
import com.kangheng.healthos.service.agent.AgentLabToolService;
import com.kangheng.healthos.service.agent.InProcessMcpBridge;
import com.kangheng.healthos.spi.agent.McpServerBridge;
import com.kangheng.healthos.spi.agent.McpToolGrants;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * Agent Lab 传输层装配。
 *
 * <p>{@code in-process}（默认）与 {@code stdio} 是**启动期定型**的：上层
 * {@code AgentLabService} 只认 {@link McpServerBridge}，换形态不改业务代码。
 *
 * <p><b>为什么 stdio 不做启动期探测</b>：起一个子进程探活要几百毫秒，而且
 * "今天 node 不在 PATH 上"不该让整个服务起不来——目录页仍然可用，探测会以
 * {@code MCP_UNAVAILABLE} 的回执如实说明原因。这里只在<b>配置本身矛盾</b>时拒绝启动
 * （未知 transport、脚本路径为空），因为那种错无论怎么跑都不会好。
 *
 * <p><b>内部令牌</b>：{@code stdio} 形态下父子进程要靠它互认。没配就<b>现场生成一个
 * 随机值</b>——它只在本次进程生命周期内有效、只在本机回环上使用，写进配置文件反而是
 * 把秘密固化到仓库里。生成后写回配置对象，端点与桥接层读的是同一个值。
 */
@Configuration
public class AgentLabConfig {

    private static final Logger log = LoggerFactory.getLogger(AgentLabConfig.class);

    /** 凭证寿命：一次操作最多三步往返，给 3 倍超时的余量足够，且过期后无法重放。 */
    private static final int GRANT_TTL_FACTOR = 3;

    @Bean
    public McpToolGrants mcpToolGrants(HealthOsProperties properties) {
        Duration timeout = properties.getAgentLab().getTimeout();
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            timeout = Duration.ofSeconds(20);
        }
        return new InMemoryMcpToolGrants(timeout.multipliedBy(GRANT_TTL_FACTOR));
    }

    @Bean
    public McpServerBridge mcpServerBridge(HealthOsProperties properties,
                                           AgentLabToolService toolService,
                                           McpToolGrants grants) {
        HealthOsProperties.AgentLab config = properties.getAgentLab();
        String transport = config.getTransport() == null
                ? "in-process" : config.getTransport().trim().toLowerCase();
        if ("in-process".equals(transport)) {
            log.info("Agent Lab 传输层：in-process（进程内协议校验，不起子进程）");
            return new InProcessMcpBridge(toolService);
        }
        if ("stdio".equals(transport)) {
            requireText(config.getNodeCommand(), "healthos.agent-lab.node-command");
            requireText(config.getServerScript(), "healthos.agent-lab.server-script");
            requireText(config.getToolEndpoint(), "healthos.agent-lab.tool-endpoint");
            if (!StringUtils.hasText(config.getInternalToken())) {
                config.setInternalToken(randomToken());
                log.info("Agent Lab 内部令牌未配置，已生成本次进程专用随机令牌");
            }
            log.info("Agent Lab 传输层：stdio（node={} script={} endpoint={}）",
                    config.getNodeCommand(), config.getServerScript(), config.getToolEndpoint());
            return new StdioMcpBridge(config, grants);
        }
        throw new IllegalStateException(
                "不支持的 healthos.agent-lab.transport=" + transport + "（可选 in-process/stdio）");
    }

    private static void requireText(String value, String key) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException("healthos.agent-lab.transport=stdio 但 " + key + " 未配置，拒绝启动");
        }
    }

    /** 32 字节随机串。用 SecureRandom 而不是 UUID：令牌不该有可预测的结构。 */
    private static String randomToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
