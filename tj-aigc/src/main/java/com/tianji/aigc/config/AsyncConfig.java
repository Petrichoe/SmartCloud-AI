package com.tianji.aigc.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步任务配置类
 * 配置异步方法执行的线程池
 *
 * @author kevin
 */
@Slf4j
@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {

    /**
     * 获取异步执行器
     * 配置线程池参数
     *
     * @return 线程池执行器
     */
    @Override
    public Executor getAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        // 核心线程数：正常情况下保持的线程数量
        executor.setCorePoolSize(5);

        // 最大线程数：并发量增加时，最多可以创建的线程数
        executor.setMaxPoolSize(10);

        // 队列容量：当核心线程都在工作时，新任务会先放入队列
        executor.setQueueCapacity(100);

        // 线程名称前缀：方便排查问题时识别线程
        executor.setThreadNamePrefix("aigc-async-");

        // 拒绝策略：当队列满且线程数达到最大时，由调用者线程执行任务
        // 这样可以降低任务提交速度，避免系统崩溃
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());

        // 关闭应用时等待任务完成
        executor.setWaitForTasksToCompleteOnShutdown(true);

        // 等待任务完成的最长时间（秒）
        executor.setAwaitTerminationSeconds(60);

        // 初始化线程池
        executor.initialize();

        log.info("异步任务线程池初始化完成 - 核心线程数: {}, 最大线程数: {}, 队列容量: {}",
                executor.getCorePoolSize(),
                executor.getMaxPoolSize(),
                executor.getQueueCapacity());

        return executor;
    }

    /**
     * 异步任务异常处理器
     * 当异步方法抛出异常时，会调用此处理器
     *
     * @return 异常处理器
     */
    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (ex, method, params) -> {
            log.error("异步任务执行失败 - 方法: {}, 参数: {}", method.getName(), params, ex);
        };
    }
}
