package com.planet.evolution.ai;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

/**
 * AI 叙事任务执行器：单线程 + 有界队列，让 tick 不被 1~3 秒的 AI 调用阻塞。
 * 队列满时静默丢弃任务（叙事丢失总好过让 tick 抛异常）。
 */
@Component
public class NarrativeExecutor {

    /** 等待队列容量。 */
    private static final int QUEUE_CAPACITY = 1000;

    private final ExecutorService pool = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(QUEUE_CAPACITY),
            (task, executor) -> System.out.println("[叙事丢弃] 队列已满，丢弃一条待写叙事"));

    /** 提交一个叙事任务，立即返回，不等待执行结果。 */
    public void submit(Runnable task) {
        pool.execute(task);
    }

    /** 应用关闭时停止线程池。 */
    @PreDestroy
    public void shutdown() {
        pool.shutdown();
    }
}
