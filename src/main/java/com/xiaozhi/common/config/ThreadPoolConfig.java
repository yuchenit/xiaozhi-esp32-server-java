package com.xiaozhi.common.config;

import org.jetbrains.annotations.NotNull;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration
public class ThreadPoolConfig {

    // 基础通用线程池
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

    // 音频清理专用线程池（新增）
    @Bean(name = "audioCleanupExecutor")
    public ExecutorService audioCleanupExecutor() {
        return new ThreadPoolExecutor(
                1, // 单线程处理清理任务
                1,
                0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(50),
                new CustomThreadFactory("audio-cleanup", true), // 守护线程
                new ThreadPoolExecutor.DiscardPolicy() // 清理任务可丢弃
        );
    }

    // 增强版自定义线程工厂
    private static class CustomThreadFactory implements ThreadFactory {
        private final String namePrefix;
        private final boolean daemon;
        private final AtomicInteger threadNumber = new AtomicInteger(1);
        private static final SecurityManager SECURITY_MANAGER = System.getSecurityManager();

        public CustomThreadFactory(String namePrefix) {
            this(namePrefix, false);
        }

        public CustomThreadFactory(String namePrefix, boolean daemon) {
            this.namePrefix = namePrefix + "-";
            this.daemon = daemon;
        }

        @Override
        public Thread newThread(@NotNull Runnable r) {
            Thread t = new Thread(SECURITY_MANAGER == null ?
                    Thread.currentThread().getThreadGroup() :
                    SECURITY_MANAGER.getThreadGroup(),
                    r,
                    namePrefix + threadNumber.getAndIncrement(),
                    0);

            t.setDaemon(daemon);
            // 设置合理的默认优先级
            if (t.getPriority() != Thread.NORM_PRIORITY) {
                t.setPriority(Thread.NORM_PRIORITY);
            }
            return t;
        }
    }
}