package com.tianji.promotion.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

@Slf4j
@Configuration
public class PromotionConfig {

    @Bean
    public Executor generateExchangeCodeExecutor(){
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // 1.核心线程池大小
        executor.setCorePoolSize(2);
        // 2.最大线程池大小
        executor.setMaxPoolSize(5);
        // 3.队列大小
        executor.setQueueCapacity(200);
        // 4.线程名称
        executor.setThreadNamePrefix("exchange-code-handler-");
        // 5.拒绝策略
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    /**
     * 优惠方案是 CPU 密集型计算。
     *
     * 当前运行机器有 14 个物理核心、20 个逻辑处理器，
     * 因此按物理核心设置并发数，不直接使用 20 个逻辑线程，
     * 避免超线程争抢 CPU 资源。
     */
    @Bean
    public Executor discountSolutionExecutor(){
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // 1.核心线程池大小
        executor.setCorePoolSize(14);
        // 2.最大线程池大小
        executor.setMaxPoolSize(14);
        // 3.有界队列，避免组合任务过多时无限占用内存
        executor.setQueueCapacity(256);
        // 4.线程名称
        executor.setThreadNamePrefix("discount-solution-calculator-");
        // 5.队列满时由提交任务的请求线程执行，形成背压
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
