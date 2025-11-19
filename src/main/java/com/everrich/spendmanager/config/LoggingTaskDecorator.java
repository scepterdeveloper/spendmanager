package com.everrich.spendmanager.config; // Assuming your config is here

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskDecorator;

import java.time.Instant;
import java.time.Duration;

public class LoggingTaskDecorator implements TaskDecorator {

    private static final Logger log = LoggerFactory.getLogger(LoggingTaskDecorator.class);

    @Override
    public Runnable decorate(Runnable runnable) {
        // Capture the name of the thread that SUBMITS the task (e.g., nio-8080-exec-7)
        String submissionThreadName = Thread.currentThread().getName();
        Instant submissionTime = Instant.now();
        
        log.info("Task submitted. Submitting Thread: {} | Timestamp: {}", 
            submissionThreadName, submissionTime);

        // Return a new Runnable that wraps the original task with timing logic
        return () -> {
            String executionThreadName = Thread.currentThread().getName();
            Instant startTime = Instant.now();
            
            log.info(">>> ASYNC TASK START | Execution Thread: {} | Submission Thread: {}", 
                executionThreadName, submissionThreadName);
            
            try {
                // Execute the original business logic
                runnable.run();
            } finally {
                Instant endTime = Instant.now();
                Duration duration = Duration.between(startTime, endTime);
                
                log.info("<<< ASYNC TASK FINISH | Execution Thread: {} | Duration: {} milliseconds", 
                    executionThreadName, duration.toMillis());
            }
        };
    }
}