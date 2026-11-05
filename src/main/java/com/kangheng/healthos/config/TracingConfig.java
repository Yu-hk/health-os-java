package com.kangheng.healthos.config;

import com.kangheng.healthos.provider.tracing.AsyncTraceWriter;
import com.kangheng.healthos.provider.tracing.DirectTraceWriter;
import com.kangheng.healthos.spi.tracing.TraceWriter;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 观测写入器装配。
 *
 * <p>用单个 {@code @Bean} 分支而不是两个 {@code @ConditionalOnProperty}：
 * 后者在普通 {@code @Configuration} 里的注册顺序不受保证，容易出现
 * "两个实现同时生效"或"一个都没注册"，装配失败要到运行时才暴露。
 */
@Configuration
public class TracingConfig {

    @Bean(destroyMethod = "close")
    public TraceWriter traceWriter(HealthOsProperties properties) {
        if ("direct".equalsIgnoreCase(properties.getTracing().getWriter())) {
            return new DirectTraceWriter();
        }
        return new AsyncTraceWriter(properties.getTracing().getQueueCapacity());
    }
}
