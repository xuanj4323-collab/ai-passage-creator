package com.mypj.aipassagecreator.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@EnableAsync
public class AsyncConfig {

    /**
     * 文章生成异步线程池
     */
    @Bean(name = "articleExecutor")
    public Executor articleExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        // 核心线程数
        executor.setCorePoolSize(5);

        // 最大线程数
        executor.setMaxPoolSize(10);

        // 队列容量
        executor.setQueueCapacity(100);

        // 线程名称前缀
        executor.setThreadNamePrefix("article-async-");

        // 拒绝策略：由调用线程处理
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());

        // 等待所有任务完成后再关闭线程池
        executor.setWaitForTasksToCompleteOnShutdown(true);

        // 等待时间
        executor.setAwaitTerminationSeconds(60);

        executor.initialize();

        return executor;
    }

    /**
     * 智能体日志写入专用线程池
     * <p>
     * 与文章生成池隔离的原因：
     * 1）日志是旁路数据，优先级低，不应和 AI 生成抢线程；
     * 2）一次生成会产生 6 条日志，量比生成任务大一个数量级；
     * 3）拒绝策略用 DiscardPolicy —— 队列满时**丢弃日志**而不是阻塞主流程，
     *    绝不能因为「记日志」把用户的创作卡住。
     */
    @Bean(name = "logExecutor")
    public Executor logExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("agent-log-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }

    /**
     * 并行配图专用线程池
     * <p>
     * 图片生成是 IO 密集（网络请求，单张 10-30 秒），必须与 CPU 任务隔离。
     * 不能使用 {@code CompletableFuture.runAsync} 的默认线程池
     * （{@code ForkJoinPool.commonPool()}）：它被全应用的并行流共享，
     * 一旦被网络等待占满，其他地方（包括框架内部）的并行流都会被拖住。
     * <p>
     * 容量按「配图方式类型数」设计：当前最多 6-7 种类型并行，
     * 每种类型内部串行，因此 4-8 个线程足够。
     */
    @Bean(name = "imageExecutor")
    public Executor imageExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("image-gen-");
        // 队列满时退化为调用线程执行：宁可慢，也不要丢配图任务
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}

