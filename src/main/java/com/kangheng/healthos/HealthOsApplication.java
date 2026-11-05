package com.kangheng.healthos;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.kangheng.healthos.config.HealthOsProperties;

/**
 * 康衡 HealthOS Java 版入口。
 *
 * <p>本工程是原 Python 版 {@code health-os-simple} 的核心垂直切片复刻：
 * 保留五段式分层（contract / model / mapper / service / api）、Outbox 事务性调度、
 * 分段检查点与二分重试、证据溯源等关键设计，用于横向对比两种实现。
 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(HealthOsProperties.class)
public class HealthOsApplication {

    public static void main(String[] args) {
        SpringApplication.run(HealthOsApplication.class, args);
    }
}
