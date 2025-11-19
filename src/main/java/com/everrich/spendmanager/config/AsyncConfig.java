package com.everrich.spendmanager.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@EnableAsync // Ensure this is present to enable @Async processing
public class AsyncConfig {

    private static final Logger log = LoggerFactory.getLogger(AsyncConfig.class);

    @Bean(name = "transactionProcessingExecutor")
    public TaskExecutor transactionProcessingExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        // 1. Set the Core Pool Size higher than 4 vCPUs for I/O bound tasks
        executor.setCorePoolSize(10);

        // 2. IMPORTANT: Do NOT let core threads die. They hold the initialized LLM
        // connection.
        executor.setAllowCoreThreadTimeOut(false); // Prevents core threads from shutting down
        executor.setKeepAliveSeconds(600); // 10 minutes (useful if threads exceed core size)

        executor.setMaxPoolSize(20);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("Transaction-Task-");
        executor.setTaskDecorator(new LoggingTaskDecorator());
        executor.setRejectedExecutionHandler((r, executor1) -> log.error("Task rejected, thread pool is full and queue is also full"));
        executor.setWaitForTasksToCompleteOnShutdown(true); // Allow tasks to complete on shutdown
        executor.setAwaitTerminationSeconds(60); // Wait up to 60 seconds for tasks to complete
        executor.setThreadPriority(Thread.MAX_PRIORITY);

        executor.initialize();
        log.info("AsyncConfig successfully wired with CorePoolSize: {}, MaxPoolSize: {}, QueueCapacity: {}",
                executor.getCorePoolSize(), executor.getMaxPoolSize(), executor.getQueueCapacity());
        return executor;
    }
}
