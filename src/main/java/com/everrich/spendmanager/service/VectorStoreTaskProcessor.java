package com.everrich.spendmanager.service;

import com.everrich.spendmanager.entities.Transaction;
import com.everrich.spendmanager.entities.VectorStoreTask;
import com.everrich.spendmanager.entities.VectorStoreTaskStatus;
import com.everrich.spendmanager.multitenancy.TenantContext;
import com.everrich.spendmanager.repository.TransactionRepository;
import com.everrich.spendmanager.repository.VectorStoreTaskRepository;
import com.everrich.spendmanager.service.RedisAdapter.DocumentBatchItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Processor for vector store initialization tasks.
 * This component runs as a scheduled task to process pending vector store
 * initialization tasks that were parked during tenant registration.
 * 
 * The processor follows a similar pattern to StatementProcessor:
 * - On startup, resets any stuck PROCESSING tasks to PENDING
 * - Runs on a fixed schedule to pick up and process pending tasks
 * - Each task is processed synchronously using existing vector store logic
 * 
 * This approach decouples the vector store initialization from the registration
 * process, allowing registration to complete quickly while the embeddings
 * are created in the background.
 */
@Component
public class VectorStoreTaskProcessor {

    private static final Logger logger = LoggerFactory.getLogger(VectorStoreTaskProcessor.class);
    
    /**
     * Maximum number of retry attempts for failed tasks.
     */
    private static final int MAX_RETRIES = 3;
    
    private final VectorStoreTaskRepository taskRepository;
    private final TransactionRepository transactionRepository;
    private final RedisAdapter redisAdapter;

    public VectorStoreTaskProcessor(VectorStoreTaskRepository taskRepository,
                                   TransactionRepository transactionRepository,
                                   RedisAdapter redisAdapter) {
        this.taskRepository = taskRepository;
        this.transactionRepository = transactionRepository;
        this.redisAdapter = redisAdapter;
    }

    /**
     * On application startup, reset any tasks that were stuck in PROCESSING status.
     * This handles crash recovery - if the app crashed during processing, those tasks
     * will be reset to PENDING so they can be reprocessed.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        logger.info("Application started - checking for stuck PROCESSING vector store tasks");
        
        try {
            resetProcessingTasks();
            logger.info("Completed startup check for stuck vector store tasks");
        } catch (Exception e) {
            // This may happen in test environments where the database is not fully configured
            logger.warn("Unable to perform startup check for stuck vector store tasks: {}. " +
                    "This is expected in test environments.", e.getMessage());
        }
    }
    
    /**
     * Resets any tasks in PROCESSING status back to PENDING.
     * This is called on startup to recover from crashes.
     */
    @Transactional
    public void resetProcessingTasks() {
        List<VectorStoreTask> processingTasks = taskRepository.findByStatus(VectorStoreTaskStatus.PROCESSING);
        if (!processingTasks.isEmpty()) {
            logger.warn("Found {} vector store task(s) stuck in PROCESSING status. Resetting to PENDING.", 
                    processingTasks.size());
            for (VectorStoreTask task : processingTasks) {
                task.resetForRetry();
                taskRepository.save(task);
                logger.info("Reset vector store task {} for tenant {} from PROCESSING to PENDING", 
                        task.getId(), task.getTenantId());
            }
        }
    }

    /**
     * Scheduled task to process pending vector store initialization tasks.
     * Runs every 60 seconds to pick up any pending tasks.
     * 
     * Processing is done one task at a time to avoid overwhelming the
     * embedding service with concurrent requests.
     */
    @Scheduled(fixedRate = 60000)
    public void processVectorStoreTasks() {
        logger.debug("Starting scheduled vector store task processing");
        
        // Process pending tasks (oldest first)
        List<VectorStoreTask> pendingTasks = taskRepository
                .findByStatusOrderByCreatedAtAsc(VectorStoreTaskStatus.PENDING);
        
        if (pendingTasks.isEmpty()) {
            logger.debug("No pending vector store tasks to process");
        } else {
            logger.info("Found {} pending vector store tasks to process", pendingTasks.size());
            
            // Process one task at a time to avoid concurrent load on embedding service
            for (VectorStoreTask task : pendingTasks) {
                try {
                    processTask(task);
                } catch (Exception e) {
                    logger.error("Error processing vector store task {} for tenant {}: {}", 
                            task.getId(), task.getTenantId(), e.getMessage(), e);
                    markTaskFailed(task, e.getMessage());
                }
            }
        }
        
        // Also check for failed tasks that can be retried
        List<VectorStoreTask> retryableTasks = taskRepository.findFailedTasksForRetry(MAX_RETRIES);
        if (!retryableTasks.isEmpty()) {
            logger.info("Found {} failed tasks eligible for retry", retryableTasks.size());
            for (VectorStoreTask task : retryableTasks) {
                task.resetForRetry();
                taskRepository.save(task);
                logger.info("Reset failed task {} for tenant {} for retry (attempt {})", 
                        task.getId(), task.getTenantId(), task.getRetryCount() + 1);
            }
        }
        
        logger.debug("Completed scheduled vector store task processing");
    }
    
    /**
     * Processes a single vector store initialization task.
     * 
     * @param task The task to process
     */
    @Transactional
    public void processTask(VectorStoreTask task) {
        String tenantId = task.getTenantId();
        logger.info("=== VECTOR STORE TASK PROCESSING START === Task ID: {}, Tenant: {}", 
                task.getId(), tenantId);
        
        // Mark task as processing
        task.markAsProcessing();
        taskRepository.save(task);
        logger.info("Task {} marked as PROCESSING", task.getId());
        
        String previousTenantId = null;
        try {
            // Ensure the shared Redis index exists
            redisAdapter.createTransactionIndex();
            
            // Set tenant context to query their transactions
            previousTenantId = TenantContext.getTenantId();
            TenantContext.setTenantId(tenantId);
            
            // Query all transactions from the tenant's schema
            List<Transaction> transactions = transactionRepository.findAll();
            logger.info("Found {} transactions to process for RAG training in tenant '{}'", 
                    transactions.size(), tenantId);
            
            // Build batch items for all valid transactions
            List<DocumentBatchItem> batchItems = new ArrayList<>();
            int skippedCount = 0;
            
            for (Transaction transaction : transactions) {
                // Only process transactions that have a category and account assigned
                if (transaction.getCategoryEntity() != null && transaction.getAccount() != null) {
                    String categoryName = transaction.getCategoryEntity().getName();
                    String description = transaction.getDescription();
                    String operationName = transaction.getOperation().name(); // "PLUS" or "MINUS"
                    String accountName = transaction.getAccount().getName();
                    
                    batchItems.add(new DocumentBatchItem(categoryName, description, operationName, accountName));
                } else {
                    skippedCount++;
                }
            }
            
            // Create all documents in batch for significantly improved performance
            int processedCount = 0;
            if (!batchItems.isEmpty()) {
                logger.info("Creating {} RAG documents in batch for tenant '{}'...", batchItems.size(), tenantId);
                processedCount = redisAdapter.createDocumentsBatch(batchItems, tenantId);
                logger.info("RAG training data created for tenant '{}'. Processed: {}, Skipped: {}", 
                        tenantId, processedCount, skippedCount);
            } else {
                logger.info("No valid transactions found for RAG training in tenant '{}'. Skipped: {}", 
                        tenantId, skippedCount);
            }
            
            // Mark task as completed
            task.markAsCompleted(processedCount, skippedCount);
            taskRepository.save(task);
            
            logger.info("=== VECTOR STORE TASK PROCESSING COMPLETE === Task ID: {}, Tenant: {}, Processed: {}, Skipped: {}", 
                    task.getId(), tenantId, processedCount, skippedCount);
            
        } catch (Exception e) {
            logger.error("=== VECTOR STORE TASK PROCESSING FAILED === Task ID: {}, Tenant: {}, Error: {}", 
                    task.getId(), tenantId, e.getMessage(), e);
            task.markAsFailed(e.getMessage());
            taskRepository.save(task);
            throw e; // Re-throw to trigger transaction rollback if needed
        } finally {
            // Restore the previous tenant context
            if (previousTenantId != null) {
                TenantContext.setTenantId(previousTenantId);
            } else {
                TenantContext.clear();
            }
        }
    }
    
    /**
     * Marks a task as failed. Called when processing fails outside the transaction.
     * 
     * @param task The task to mark as failed
     * @param errorMessage The error message
     */
    @Transactional
    public void markTaskFailed(VectorStoreTask task, String errorMessage) {
        try {
            VectorStoreTask currentTask = taskRepository.findById(task.getId()).orElse(null);
            if (currentTask != null) {
                currentTask.markAsFailed(errorMessage);
                taskRepository.save(currentTask);
                logger.warn("Marked vector store task {} as FAILED: {}", task.getId(), errorMessage);
            }
        } catch (Exception e) {
            logger.error("Failed to mark vector store task {} as FAILED: {}", task.getId(), e.getMessage(), e);
        }
    }
    
    /**
     * Creates a new vector store initialization task for a tenant.
     * This method is called during tenant registration to park the task
     * for later processing by the scheduler.
     * 
     * @param tenantId The tenant/registration ID
     * @return The created task, or null if a task already exists
     */
    @Transactional
    public VectorStoreTask createTask(String tenantId) {
        // Check if a pending or processing task already exists for this tenant
        List<VectorStoreTaskStatus> activeStatuses = List.of(
                VectorStoreTaskStatus.PENDING, 
                VectorStoreTaskStatus.PROCESSING);
        
        if (taskRepository.existsByTenantIdAndStatusIn(tenantId, activeStatuses)) {
            logger.warn("Vector store task already exists for tenant '{}'. Skipping creation.", tenantId);
            return null;
        }
        
        VectorStoreTask task = VectorStoreTask.createForTenant(tenantId);
        task = taskRepository.save(task);
        
        logger.info("Created vector store initialization task {} for tenant '{}'", task.getId(), tenantId);
        return task;
    }
}