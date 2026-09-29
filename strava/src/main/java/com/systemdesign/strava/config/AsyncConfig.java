package com.systemdesign.strava.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Enables asynchronous execution for post-commit work.
 *
 * <p>When an activity is stopped, segment matching / leaderboard updates run off the request
 * thread via an {@code @Async @TransactionalEventListener(AFTER_COMMIT)} handler. Keeping this
 * work off the write path is part of what lets the ingestion path stay lightweight enough to
 * scale to millions of concurrent activities.
 */
@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {

    private static final Logger log = LoggerFactory.getLogger(AsyncConfig.class);

    @Bean(name = "stravaAsyncExecutor")
    public Executor stravaAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("strava-async-");

        // Use CallerRunsPolicy: if the queue is full, execute in the caller's thread
        // (synchronous fallback). This prevents RejectedExecutionException and provides
        // graceful degradation under load.
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());

        executor.initialize();
        return executor;
    }

    /**
     * Make the shared pool the default executor so plain {@code @Async} methods (e.g. the
     * leaderboard event listener) run on it without needing an explicit qualifier.
     */
    @Override
    public Executor getAsyncExecutor() {
        return stravaAsyncExecutor();
    }

    /**
     * Global exception handler for uncaught exceptions in @Async methods.
     * Logs exceptions with ERROR level and includes the method name for alerting/monitoring.
     */
    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (throwable, method, params) ->
            log.error("Uncaught exception in async method '{}': {}",
                method.getName(), throwable.getMessage(), throwable);
    }
}
