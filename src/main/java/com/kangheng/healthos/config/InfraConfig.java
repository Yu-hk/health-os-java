package com.kangheng.healthos.config;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;

import com.kangheng.healthos.common.AccessControl;
import com.kangheng.healthos.service.auth.PasswordHash;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * 基础设施装配：分页插件、虚拟线程执行器、启动期配置校验。
 */
@Configuration
public class InfraConfig {

    private static final Logger log = LoggerFactory.getLogger(InfraConfig.class);

    private static final int PIPELINE_THREADS = 8;
    private static final int QA_BRANCH_THREADS = 4;
    private static final int QA_STREAM_THREADS = 16;

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.POSTGRE_SQL));
        return interceptor;
    }

    /**
     * 访问控制装配。
     *
     * <p>白名单在装配层取值，{@code common.AccessControl} 只认"名单"这一个事实。
     * 之前它直接注入 {@link HealthOsProperties}，导致公共内核反向依赖配置层——
     * 内核代码换个配置方式就得跟着改，那是把耦合藏在了包结构里。
     */
    @Bean
    public AccessControl accessControl(HealthOsProperties properties) {
        return new AccessControl(
                properties.getSecurity().getAdminUsers(),
                properties.getSecurity().getTraceGlobalUsers(),
                properties.getSecurity().getModelAdminUsers());
    }

    /**
     * 流水线执行器：有界线程池。
     *
     * <p>不用无界线程池——认领到的事件数量本身由 dispatcher-batch-size 约束，
     * 任务队列再兜一层，避免突发上传把进程线程数打爆。
     * 若运行在 JDK 21 上，可在此换成 {@code Executors.newVirtualThreadPerTaskExecutor()}。
     */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService pipelineExecutor() {
        AtomicInteger counter = new AtomicInteger();
        return Executors.newFixedThreadPool(PIPELINE_THREADS, runnable -> {
            Thread thread = new Thread(runnable, "pipeline-worker-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * 问答分支执行器：与流水线隔离的独立池。
     *
     * <p>不共用 {@link #pipelineExecutor()}——一次上传可能占满 8 个流水线线程跑 OCR/抽取，
     * 如果问答也排队在同一个池里，用户点"提问"会被后台任务饿死。
     * 线程数取分支数即可（默认三路），再多也只是重复占用。
     */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService qaBranchExecutor() {
        AtomicInteger counter = new AtomicInteger();
        return Executors.newFixedThreadPool(QA_BRANCH_THREADS, runnable -> {
            Thread thread = new Thread(runnable, "qa-branch-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * SSE 连接的执行器。
     *
     * <p>一条 SSE 连接会<b>独占</b>一个线程直到连接关闭（推流是阻塞的）。
     * 所以它必须独立成池，且线程数有上限——否则一批客户端连上来就能把进程线程耗光，
     * 正常的业务请求反而没线程可用。
     */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService qaStreamExecutor() {
        AtomicInteger counter = new AtomicInteger();
        return Executors.newFixedThreadPool(QA_STREAM_THREADS, runnable -> {
            Thread thread = new Thread(runnable, "qa-stream-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * 启动期校验：非 local 环境禁止弱配置。
     *
     * <p>原项目在 {@code config.py} 里给 MinIO / Neo4j / 会话密钥留了
     * {@code change_me} 默认值，漏配时应用照常启动、静默使用弱密钥。
     * 这里改为 fail-fast。
     *
     * <p>三类检查各有原因：
     * <ol>
     *   <li><b>会话密钥</b>：HMAC-SHA256 的密钥短于 32 字节时，签名强度由密钥决定，
     *       换密钥就能伪造任意用户；</li>
     *   <li><b>账号口令</b>：<b>按配置形态分别检查</b>。配了 {@code users} 列表就该查列表，
     *       而不是死盯单账号的 {@code password}——否则多账号部署永远启动不了，
     *       或者反过来漏配一个空口令账号；</li>
     *   <li><b>口令存储</b>：非 local 拒绝明文口令。配置文件会进版本库、进镜像、进备份，
     *       明文等于口令公开。</li>
     * </ol>
     */
    @Bean
    public InitializingBean healthOsConfigGuard(HealthOsProperties properties) {
        return () -> {
            HealthOsProperties.Auth auth = properties.getAuth();
            boolean local = "local".equalsIgnoreCase(properties.getEnvironment());
            if (local) {
                log.warn("healthos.environment=local，跳过敏感配置强校验（仅限本地开发）");
                warnWeakAuth(auth, "local");
                return;
            }
            String secret = auth.getSessionSecret();
            if (!StringUtils.hasText(secret) || secret.contains("change-me")) {
                throw new IllegalStateException(
                        "healthos.auth.session-secret 未配置或仍为占位值，拒绝启动（environment="
                                + properties.getEnvironment() + "）");
            }
            if (secret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32) {
                throw new IllegalStateException(
                        "healthos.auth.session-secret 长度不足 32 字节，签名可被穷举，拒绝启动"
                                + "（建议 openssl rand -base64 48 生成）");
            }
            requireCredentials(auth);
            if (!auth.isCookieSecure()) {
                log.warn("healthos.auth.cookie-secure=false：会话 Cookie 会在明文信道上传输，"
                        + "HTTPS 部署请置为 true");
            }
            if (auth.getSessionTtl() != null && auth.getSessionTtl().compareTo(Duration.ofHours(24)) > 0) {
                log.warn("healthos.auth.session-ttl={} 超过 24 小时，令牌泄露后的可利用窗口过长",
                        auth.getSessionTtl());
            }
        };
    }

    /** 非 local 必须每个账号都有口令，且口令必须是哈希。 */
    private void requireCredentials(HealthOsProperties.Auth auth) {
        List<HealthOsProperties.Auth.User> users = auth.getUsers();
        if (users != null && !users.isEmpty()) {
            for (HealthOsProperties.Auth.User user : users) {
                String name = user == null ? null : user.getUsername();
                if (!StringUtils.hasText(name) || !StringUtils.hasText(user.getPassword())) {
                    throw new IllegalStateException(
                            "healthos.auth.users 中存在用户名为空或口令为空的账号，拒绝启动");
                }
                requireHashed(name, user.getPassword());
            }
            return;
        }
        if (!StringUtils.hasText(auth.getPassword())) {
            throw new IllegalStateException("healthos.auth.password 未配置，拒绝启动");
        }
        requireHashed(auth.getUsername(), auth.getPassword());
    }

    private void requireHashed(String username, String stored) {
        if (!PasswordHash.isEncoded(stored)) {
            throw new IllegalStateException(
                    "账号 " + username + " 的 healthos.auth 口令是明文，拒绝启动。"
                            + "用 `java -cp target/classes "
                            + "com.kangheng.healthos.service.auth.PasswordHash '<口令>'` 生成哈希后填入");
        }
    }

    /** local 环境不阻断启动，但要把"这套配置不能上线"说清楚。 */
    private void warnWeakAuth(HealthOsProperties.Auth auth, String environment) {
        if (!PasswordHash.isEncoded(auth.getPassword()) && StringUtils.hasText(auth.getPassword())) {
            log.warn("healthos.auth 口令为明文（environment={}），仅限本地使用", environment);
        }
        if (!auth.isCookieSecure()) {
            log.warn("healthos.auth.cookie-secure=false（environment={}），HTTP 明文下正常，上线需置 true",
                    environment);
        }
    }
}
