package com.fitmind.promotion.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 优惠方案计算的线程池。
 *
 * <p>为什么单独配一个池子而不是用默认池：叠加推荐会一次提交几十上百个计算任务，
 * 如果和日常请求共用线程池，一波领券高峰就能把整个服务的线程占满。</p>
 */
@Configuration
public class DiscountExecutorConfig {

    @Bean("discountSolutionExecutor")
    public Executor discountSolutionExecutor() {
        ThreadFactory factory = new ThreadFactory() {
            private final AtomicInteger idx = new AtomicInteger(1);

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r);
                t.setName("discount-solution-" + idx.getAndIncrement());
                return t;
            }
        };
        ExecutorService pool = new ThreadPoolExecutor(
                4, 8, 60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(500), factory,
                // 队列满了就丢弃最老的任务 —— 方案算不完是可接受的，不能堆积
                new ThreadPoolExecutor.DiscardOldestPolicy());
        return pool;
    }
}
