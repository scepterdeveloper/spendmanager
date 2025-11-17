package com.everrich.spendmanager.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.context.annotation.Bean;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@EnableAsync // Ensure this is present to enable @Async processing
public class AsyncConfig {

    @Bean(name = "transactionProcessingExecutor")
    public TaskExecutor transactionProcessingExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        
        // Core Pool Size: Determines how many threads are kept alive. 
        // Set higher than your vCPU count (4) to handle I/O waiting time efficiently.
        // A common rule for I/O bound is 2*CPU + 1. Let's start with 10.
        executor.setCorePoolSize(10); 
        
        // Max Pool Size: Maximum number of threads that can be created.
        executor.setMaxPoolSize(20); 
        
        // Queue Capacity: How many tasks can wait if all threads are busy.
        executor.setQueueCapacity(500); 
        
        executor.setThreadNamePrefix("Transaction-Task-");
        executor.initialize();
        return executor;
    }
}
