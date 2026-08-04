package com.everrich.spendmanager.service;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.RetryContext;
import org.springframework.retry.RetryListener;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * Rate limiter for LLM calls to prevent resource exhaustion.
 * 
 * Uses a token bucket algorithm with configurable:
 * - Maximum concurrent requests (semaphore-based)
 * - Requests per second limit (token bucket)
 * - Timeout for acquiring permits
 * - Retry with exponential backoff for transient failures
 * 
 * This implementation is thread-safe and suitable for high-concurrency scenarios.
 */
@Component
public class LlmRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(LlmRateLimiter.class);

    /**
     * Maximum number of concurrent LLM requests allowed.
     */
    @Value("${spendmanager.llm.rate-limit.max-concurrent:5}")
    private int maxConcurrentRequests;

    /**
     * Maximum requests per second (rate limit).
     */
    @Value("${spendmanager.llm.rate-limit.requests-per-second:10.0}")
    private double requestsPerSecond;

    /**
     * Timeout in milliseconds when waiting for a permit.
     */
    @Value("${spendmanager.llm.rate-limit.timeout-ms:30000}")
    private long timeoutMs;

    /**
     * Whether rate limiting is enabled.
     */
    @Value("${spendmanager.llm.rate-limit.enabled:true}")
    private boolean enabled;

    /**
     * Maximum retry attempts for transient failures.
     */
    @Value("${spendmanager.llm.retry.max-attempts:3}")
    private int maxRetryAttempts;

    /**
     * Initial backoff interval in milliseconds.
     */
    @Value("${spendmanager.llm.retry.initial-interval-ms:1000}")
    private long initialIntervalMs;

    /**
     * Backoff multiplier for exponential backoff.
     */
    @Value("${spendmanager.llm.retry.multiplier:2.0}")
    private double backoffMultiplier;

    /**
     * Maximum backoff interval in milliseconds.
     */
    @Value("${spendmanager.llm.retry.max-interval-ms:10000}")
    private long maxIntervalMs;

    /**
     * Whether retry is enabled.
     */
    @Value("${spendmanager.llm.retry.enabled:true}")
    private boolean retryEnabled;

    private Semaphore concurrencySemaphore;
    private RetryTemplate retryTemplate;
    
    // Token bucket state for rate limiting
    private final AtomicLong lastRefillTime = new AtomicLong(System.nanoTime());
    private volatile double availableTokens;
    private final Object tokenLock = new Object();

    // Metrics
    private final AtomicLong totalRequests = new AtomicLong(0);
    private final AtomicLong throttledRequests = new AtomicLong(0);
    private final AtomicLong timedOutRequests = new AtomicLong(0);
    private final AtomicLong retriedRequests = new AtomicLong(0);
    private final AtomicLong failedAfterRetries = new AtomicLong(0);

    @PostConstruct
    public void init() {
        this.concurrencySemaphore = new Semaphore(maxConcurrentRequests, true); // fair ordering
        this.availableTokens = requestsPerSecond; // Start with a full bucket
        this.retryTemplate = createRetryTemplate();
        
        log.info("LLM Rate Limiter initialized: enabled={}, maxConcurrent={}, requestsPerSecond={}, timeoutMs={}",
                enabled, maxConcurrentRequests, requestsPerSecond, timeoutMs);
        log.info("LLM Retry configured: enabled={}, maxAttempts={}, initialInterval={}ms, multiplier={}, maxInterval={}ms",
                retryEnabled, maxRetryAttempts, initialIntervalMs, backoffMultiplier, maxIntervalMs);
    }

    /**
     * Creates a RetryTemplate with exponential backoff policy.
     */
    private RetryTemplate createRetryTemplate() {
        RetryTemplate template = new RetryTemplate();
        
        // Retry policy - retry on any exception up to maxRetryAttempts
        SimpleRetryPolicy retryPolicy = new SimpleRetryPolicy();
        retryPolicy.setMaxAttempts(maxRetryAttempts);
        template.setRetryPolicy(retryPolicy);
        
        // Exponential backoff policy
        ExponentialBackOffPolicy backOffPolicy = new ExponentialBackOffPolicy();
        backOffPolicy.setInitialInterval(initialIntervalMs);
        backOffPolicy.setMultiplier(backoffMultiplier);
        backOffPolicy.setMaxInterval(maxIntervalMs);
        template.setBackOffPolicy(backOffPolicy);
        
        // Add listener for logging retries
        template.registerListener(new RetryListener() {
            @Override
            public <T, E extends Throwable> void onError(RetryContext context, 
                    RetryCallback<T, E> callback, Throwable throwable) {
                retriedRequests.incrementAndGet();
                log.warn("LLM call failed (attempt {}/{}): {}. Retrying...", 
                        context.getRetryCount(), maxRetryAttempts, 
                        throwable.getMessage());
            }
            
            @Override
            public <T, E extends Throwable> void close(RetryContext context, 
                    RetryCallback<T, E> callback, Throwable throwable) {
                if (throwable != null) {
                    failedAfterRetries.incrementAndGet();
                    log.error("LLM call failed after {} attempts: {}", 
                            context.getRetryCount(), throwable.getMessage());
                }
            }
        });
        
        return template;
    }

    /**
     * Acquires a permit for making an LLM call.
     * This method blocks until a permit is available or timeout occurs.
     * 
     * @return true if permit was acquired, false if rate limited or timed out
     */
    public boolean acquire() {
        if (!enabled) {
            return true;
        }

        totalRequests.incrementAndGet();
        long startTime = System.currentTimeMillis();

        try {
            // Step 1: Acquire concurrency semaphore
            boolean semaphoreAcquired = concurrencySemaphore.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS);
            if (!semaphoreAcquired) {
                timedOutRequests.incrementAndGet();
                log.warn("LLM rate limit: Timed out waiting for concurrent slot (waited {}ms)", timeoutMs);
                return false;
            }

            // Step 2: Wait for rate limit token (token bucket)
            long remainingTimeout = timeoutMs - (System.currentTimeMillis() - startTime);
            if (remainingTimeout <= 0) {
                concurrencySemaphore.release();
                timedOutRequests.incrementAndGet();
                log.warn("LLM rate limit: Timed out after acquiring semaphore");
                return false;
            }

            boolean tokenAcquired = acquireToken(remainingTimeout);
            if (!tokenAcquired) {
                concurrencySemaphore.release();
                throttledRequests.incrementAndGet();
                log.warn("LLM rate limit: Throttled - rate limit exceeded");
                return false;
            }

            long waitTime = System.currentTimeMillis() - startTime;
            if (waitTime > 100) { // Log if wait time is significant
                log.debug("LLM rate limit: Acquired permit after {}ms wait", waitTime);
            }

            return true;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("LLM rate limit: Interrupted while waiting for permit");
            return false;
        }
    }

    /**
     * Releases a permit after completing an LLM call.
     * Must be called after acquire() returns true.
     */
    public void release() {
        if (!enabled) {
            return;
        }
        concurrencySemaphore.release();
    }

    /**
     * Executes an LLM call with rate limiting and retry support.
     * This is the recommended way to make LLM calls as it handles both
     * rate limiting and retries automatically.
     * 
     * @param <T> The return type of the LLM call
     * @param llmCall The LLM call to execute
     * @param fallback The fallback value to return if rate limited or all retries fail
     * @return The result of the LLM call, or the fallback value
     */
    public <T> T executeWithRetry(Supplier<T> llmCall, T fallback) {
        // First, try to acquire rate limit permit
        if (!acquire()) {
            log.warn("Rate limit exceeded, returning fallback value");
            return fallback;
        }
        
        try {
            if (retryEnabled) {
                return retryTemplate.execute(context -> llmCall.get());
            } else {
                return llmCall.get();
            }
        } catch (Exception e) {
            log.error("LLM call failed after all retries: {}", e.getMessage());
            return fallback;
        } finally {
            release();
        }
    }

    /**
     * Executes an LLM call with rate limiting and retry support.
     * Throws exception if the call fails after all retries.
     * 
     * @param <T> The return type of the LLM call
     * @param llmCall The LLM call to execute
     * @return The result of the LLM call
     * @throws LlmRateLimitException if rate limited
     * @throws RuntimeException if the call fails after all retries
     */
    public <T> T executeWithRetryOrThrow(Supplier<T> llmCall) throws LlmRateLimitException {
        // First, try to acquire rate limit permit
        if (!acquire()) {
            throw new LlmRateLimitException("Rate limit exceeded for LLM calls");
        }
        
        try {
            if (retryEnabled) {
                return retryTemplate.execute(context -> llmCall.get());
            } else {
                return llmCall.get();
            }
        } catch (Exception e) {
            throw new RuntimeException("LLM call failed after all retries", e);
        } finally {
            release();
        }
    }

    /**
     * Acquires a token from the token bucket, waiting if necessary.
     * 
     * @param timeoutMs maximum time to wait for a token
     * @return true if token acquired, false if timed out
     */
    private boolean acquireToken(long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        
        synchronized (tokenLock) {
            while (true) {
                refillTokens();
                
                if (availableTokens >= 1.0) {
                    availableTokens -= 1.0;
                    return true;
                }

                // Calculate wait time until next token is available
                double tokensNeeded = 1.0 - availableTokens;
                long waitTimeMs = (long) Math.ceil((tokensNeeded / requestsPerSecond) * 1000);
                
                long remainingTime = deadline - System.currentTimeMillis();
                if (remainingTime <= 0) {
                    return false; // Timed out
                }

                long actualWait = Math.min(waitTimeMs, remainingTime);
                if (actualWait > 0) {
                    tokenLock.wait(actualWait);
                }
            }
        }
    }

    /**
     * Refills tokens based on elapsed time since last refill.
     * Must be called while holding tokenLock.
     */
    private void refillTokens() {
        long now = System.nanoTime();
        long lastRefill = lastRefillTime.get();
        double elapsedSeconds = (now - lastRefill) / 1_000_000_000.0;
        
        if (elapsedSeconds > 0) {
            double newTokens = elapsedSeconds * requestsPerSecond;
            availableTokens = Math.min(requestsPerSecond, availableTokens + newTokens); // Cap at bucket size
            lastRefillTime.set(now);
        }
    }

    /**
     * Returns current rate limiter statistics.
     */
    public RateLimiterStats getStats() {
        return new RateLimiterStats(
                totalRequests.get(),
                throttledRequests.get(),
                timedOutRequests.get(),
                retriedRequests.get(),
                failedAfterRetries.get(),
                maxConcurrentRequests - concurrencySemaphore.availablePermits(),
                maxConcurrentRequests
        );
    }

    /**
     * Statistics about rate limiter usage.
     */
    public record RateLimiterStats(
            long totalRequests,
            long throttledRequests,
            long timedOutRequests,
            long retriedRequests,
            long failedAfterRetries,
            int currentConcurrentRequests,
            int maxConcurrentRequests
    ) {}

    /**
     * Exception thrown when rate limit is exceeded.
     */
    public static class LlmRateLimitException extends Exception {
        public LlmRateLimitException(String message) {
            super(message);
        }
    }
}