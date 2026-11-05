package com.kangheng.healthos.service.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.kangheng.healthos.common.BizException;
import com.kangheng.healthos.config.HealthOsProperties;
import com.kangheng.healthos.model.auth.Session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 登录与会话服务。
 *
 * <p>职责边界：本类只回答"你是谁"（认证）。"你能干什么"由 {@code common.AccessControl}
 * 按当前配置判定（授权）——两者分开的好处是改权限立即生效，不用等令牌过期。
 *
 * <p>原项目用 {@code .secrets/credentials.local.json} 存单账号；Java 切片改为配置项，
 * 支持多账号（患者域按归属人隔离，单账号验证不出越权）。
 *
 * <p>口令比较走 {@link PasswordHash}：支持 {@code {pbkdf2}} 哈希，也兼容明文
 * （明文仅限本地；非 local 环境启动自检会拒绝）。
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final HealthOsProperties properties;
    private final SessionRevocationRegistry revocations;
    private final LoginThrottle throttle;
    private final Clock clock;

    /**
     * 供 Spring 装配的构造器。
     *
     * <p>显式 {@code @Autowired} 是必需的：类里还有一个包内可见的构造器专门收 {@link Clock}
     * 供测试注入，两个候选并存时 Spring 会退回"找无参构造器"，启动直接失败。
     * 这类错误编译期看不出来，只在真正启动容器时才暴露。
     */
    @Autowired
    public AuthService(HealthOsProperties properties,
                       SessionRevocationRegistry revocations,
                       LoginThrottle throttle) {
        this(properties, revocations, throttle, Clock.systemUTC());
    }

    /**
     * 显式注入时钟的构造器。
     *
     * <p>公开而不是包内可见：它是个有意留出的接缝——测试要验"续期/过期"这类时间语义，
     * 不能靠 {@code sleep} 把用例拖慢；将来接统一时钟源时也从这里换。
     * Spring 侧由上面的 {@code @Autowired} 构造器负责，不受影响。
     */
    public AuthService(HealthOsProperties properties,
                       SessionRevocationRegistry revocations,
                       LoginThrottle throttle,
                       Clock clock) {
        this.properties = properties;
        this.revocations = revocations;
        this.throttle = throttle;
        this.clock = clock;
    }

    /**
     * 校验口令并签发会话。
     *
     * <p>失败原因对调用方只暴露一句话（"用户名或口令不正确"），日志里才区分
     * "账号不存在"与"口令不匹配"——否则接口本身就成了账号枚举器。
     */
    public IssuedSession login(String username, String rawPassword, String clientIp) {
        throttle.requireAllowed(username);
        Optional<Credential> credential = lookup(username);
        boolean passwordMatches = credential
                .map(item -> PasswordHash.matches(rawPassword, item.storedPassword()))
                .orElse(false);
        if (credential.isEmpty() || !passwordMatches) {
            throttle.recordFailure(username);
            log.warn("登录失败 user={} ip={} reason={}", username, clientIp,
                    credential.isEmpty() ? "账号不存在" : "口令不匹配");
            throw BizException.unauthorized("用户名或口令不正确");
        }
        throttle.recordSuccess(username);
        HealthOsProperties.Auth auth = properties.getAuth();
        SessionToken.Issued issued = SessionToken.issue(credential.get().username(), ttl(),
                auth.getSessionSecret(), clock.instant());
        log.info("登录成功 user={} ip={} jti={} expiresAt={}", issued.session().subject(), clientIp,
                issued.session().id(), issued.session().expiresAt());
        return new IssuedSession(issued.token(), issued.session());
    }

    /** 登出：把令牌记入吊销名单。令牌本身无效时静默返回，不报错（幂等）。 */
    public void logout(String token) {
        SessionToken.read(token, properties.getAuth().getSessionSecret(), clock.instant())
                .ifPresent(session -> {
                    revocations.revoke(session);
                    log.info("登出 user={} jti={}", session.subject(), session.id());
                });
    }

    /** 校验令牌：签名、有效期、是否已吊销。任一不满足都返回 empty。 */
    public Optional<Session> resolve(String token) {
        return SessionToken.read(token, properties.getAuth().getSessionSecret(), clock.instant())
                .filter(session -> !revocations.isRevoked(session.id(), clock.instant()));
    }

    /**
     * 续期：沿用原 {@code jti}，把到期时间往后推一个 TTL。
     *
     * <p>必须复用 {@code jti}：否则"续期 → 登出"之间形成的吊销记录对新令牌不生效，
     * 等于给登出开了后门。
     */
    public String renew(Session session) {
        return SessionToken.create(session.subject(), session.id(), ttl(),
                properties.getAuth().getSessionSecret(), clock.instant());
    }

    public Duration ttl() {
        Duration configured = properties.getAuth().getSessionTtl();
        return configured == null ? Duration.ofHours(8) : configured;
    }

    public String cookieName() {
        return properties.getAuth().getCookieName();
    }

    /** 生产环境应为 true；本地 http 下必须为 false，否则浏览器直接丢弃 Cookie。 */
    public boolean cookieSecure() {
        return properties.getAuth().isCookieSecure();
    }

    public Instant now() {
        return clock.instant();
    }

    /**
     * 找到用于比对的凭据。
     *
     * <p>配了 {@code users} 列表就以列表为准，否则退回单账号——两个分支的口令都可能为空，
     * 为空一律视为"此账号不可登录"，绝不允许空口令通过。
     */
    private Optional<Credential> lookup(String username) {
        HealthOsProperties.Auth auth = properties.getAuth();
        if (!StringUtils.hasText(username)) {
            return Optional.empty();
        }
        if (auth.getUsers() != null && !auth.getUsers().isEmpty()) {
            List<Credential> candidates = new ArrayList<>();
            for (HealthOsProperties.Auth.User user : auth.getUsers()) {
                if (user != null && StringUtils.hasText(user.getUsername())) {
                    candidates.add(new Credential(user.getUsername(), user.getPassword()));
                }
            }
            // 逐个比对而不是先 break：短路会让"账号在前几位"的响应快于"账号不存在"，
            // 差异足够被用来枚举账号
            Credential matched = null;
            for (Credential candidate : candidates) {
                if (candidate.username().equals(username)) {
                    matched = candidate;
                }
            }
            if (matched == null || !StringUtils.hasText(matched.storedPassword())) {
                return Optional.empty();
            }
            return Optional.of(matched);
        }
        if (!StringUtils.hasText(auth.getPassword()) || !StringUtils.hasText(auth.getUsername())) {
            throw BizException.unauthorized("服务端未配置登录口令，拒绝所有登录请求");
        }
        return Optional.of(new Credential(auth.getUsername(), auth.getPassword()));
    }

    /** @param storedPassword 明文或 {@code {pbkdf2}} 哈希串 */
    private record Credential(String username, String storedPassword) {
    }

    /**
     * 一次签发结果：令牌要写进 Cookie，会话用来生成响应体与审计日志。
     *
     * @param token   待写入 Cookie 的令牌
     * @param session 已解析的会话（含 jti 与到期时间）
     */
    public record IssuedSession(String token, Session session) {
    }
}
