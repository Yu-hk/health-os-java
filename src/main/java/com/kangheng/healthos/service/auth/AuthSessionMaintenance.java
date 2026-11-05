package com.kangheng.healthos.service.auth;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 认证状态表的过期清理。
 *
 * <p>两张表都是"只增不删会一直涨"的形状：吊销记录的生命周期等于令牌剩余寿命，
 * 失败计数的生命周期等于滑动窗口。写路径上虽然也有顺手清理（超过阈值时），
 * 但那只在"还有活动"时才会触发——一个安静的系统恰恰最容易把垃圾攒下来，
 * 所以需要一个与业务无关的定时兜底。
 *
 * <p>多实例同时跑这段没有副作用：两个 DELETE 撞在一起只是后一个少删几行，
 * 不需要选主或分布式锁。这也是"能幂等就别引协调"的一个例子。
 */
@Service
public class AuthSessionMaintenance {

    private static final Logger log = LoggerFactory.getLogger(AuthSessionMaintenance.class);

    private final SessionRevocationRegistry revocations;
    private final LoginThrottle throttle;

    public AuthSessionMaintenance(SessionRevocationRegistry revocations, LoginThrottle throttle) {
        this.revocations = revocations;
        this.throttle = throttle;
    }

    /** 每小时清一次，避开整点（整点是各类定时任务的拥堵时刻）。 */
    @Scheduled(cron = "${healthos.auth.sweep-cron:0 17 * * * *}")
    public void scheduled() {
        Instant now = Instant.now();
        try {
            revocations.sweep(now);
        } catch (Exception ex) {
            log.warn("吊销名单清理失败（不影响判定，下次再清）: {}", ex.getMessage());
        }
        try {
            throttle.sweep(now);
        } catch (Exception ex) {
            log.warn("登录失败记录清理失败（不影响判定，下次再清）: {}", ex.getMessage());
        }
    }
}
