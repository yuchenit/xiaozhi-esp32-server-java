package com.xiaozhi.common.config;

import org.jetbrains.annotations.NotNull;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration
public class ThreadPoolConfig {

    @Bean(name = "baseThreadPool")
    public ExecutorService baseThreadPool() {
        return new ThreadPoolExecutor(
                10, // 核心线程数
                20, // 最大线程数
                60L, // 空闲线程存活时间
                TimeUnit.SECONDS, // 时间单位
                new LinkedBlockingQueue<>(100), // 任务队列
                new CustomThreadFactory("base-pool"), // 自定义线程工厂
                new ThreadPoolExecutor.CallerRunsPolicy() // 拒绝策略
        );
    }

    // 自定义线程工厂
    private static class CustomThreadFactory implements ThreadFactory {
        private final String namePrefix;
        private final AtomicInteger threadNumber = new AtomicInteger(1);

        public CustomThreadFactory(String namePrefix) {
            this.namePrefix = namePrefix;
        }

        @Override
        public Thread newThread(@NotNull Runnable r) {
            Thread t = new Thread(r, namePrefix + "-" + threadNumber.getAndIncrement());
            t.setDaemon(false); // 设置为非守护线程
            return t;
        }
    }
}