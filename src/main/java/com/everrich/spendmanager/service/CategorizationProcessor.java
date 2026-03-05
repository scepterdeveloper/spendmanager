package com.everrich.spendmanager.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.everrich.spendmanager.entities.Category;
import com.everrich.spendmanager.entities.Registration;
import com.everrich.spendmanager.entities.RegistrationStatus;
import com.everrich.spendmanager.entities.Statement;
import com.everrich.spendmanager.entities.StatementStatus;
import com.everrich.spendmanager.entities.Transaction;
import com.everrich.spendmanager.entities.TransactionCategorizationStatus;
import com.everrich.spendmanager.multitenancy.TenantContext;
import com.everrich.spendmanager.repository.RegistrationRepository;
import com.everrich.spendmanager.repository.TransactionRepository;

/**
 * Processes transaction categorization using LLM with RAG (similarity search).
 * 
 * This processor picks up transactions with TO_BE_LLM_CATEGORIZED status and
 * categorizes them in batches using the RagService. This design ensures:
 * - Crash resilience: Each transaction's categorization is tracked individually
 * - Progress visibility: Users can see categorization progress in real-time
 * - Consistency: Statement is marked COMPLETED only when all transactions are done
 * - Efficiency: Batching reduces the number of LLM calls
 * 
 * Batch Processing:
 * - Transactions are batched together (can be from different statements)
 * - Batches are processed sequentially (not in parallel)
 * - After each batch completes, statement completion is checked at transaction level
 * - Batch size is configurable via spendmanager.categorization.batch-size property
 * 
 * Error Handling:
 * - If categorization fails for any transaction, the entire statement is marked FAILED
 * 
 * Crash Recovery:
 * - On startup, transactions stuck in LLM_CATEGORIZING are reset to TO_BE_LLM_CATEGORIZED
 */
@Component
public class CategorizationProcessor {

    private static final Logger log = LoggerFactory.getLogger(CategorizationProcessor.class);

    private final TransactionRepository transactionRepository;
    private final RagService ragService;
    private final CategoryService categoryService;
    private final StatementService statementService;
    private final RegistrationRepository registrationRepository;

    public CategorizationProcessor(
            TransactionRepository transactionRepository,
            RagService ragService,
            CategoryService categoryService,
            StatementService statementService,
            RegistrationRepository registrationRepository) {
        this.transactionRepository = transactionRepository;
        this.ragService = ragService;
        this.categoryService = categoryService;
        this.statementService = statementService;
        this.registrationRepository = registrationRepository;
    }

    /**
     * On application startup, reset any transactions stuck in LLM_CATEGORIZING status.
     * This handles crash recovery - if the app crashed during categorization, those
     * transactions will be reset to TO_BE_LLM_CATEGORIZED so they can be reprocessed.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        log.info("Application started - checking for stuck LLM_CATEGORIZING transactions across all tenants");
        
        try {
            List<Registration> activeTenants = registrationRepository.findByStatus(RegistrationStatus.COMPLETE);
            
            for (Registration tenant : activeTenants) {
                String tenantId = tenant.getRegistrationId();
                try {
                    TenantContext.setTenantId(tenantId);
                    resetCategorizingTransactions();
                } catch (Exception e) {
                    log.error("Error resetting LLM_CATEGORIZING transactions for tenant {}: {}", 
                            tenantId, e.getMessage(), e);
                } finally {
                    TenantContext.clear();
                }
            }
            
            log.info("Completed startup check for stuck categorizing transactions");
        } catch (Exception e) {
            log.warn("Unable to perform startup check for stuck categorizing transactions: {}. " +
                    "This is expected in test environments.", e.getMessage());
        }
    }

    /**
     * Resets any transactions in LLM_CATEGORIZING status back to TO_BE_LLM_CATEGORIZED.
     * This is called on startup to recover from crashes.
     */
    @Transactional
    public void resetCategorizingTransactions() {
        int resetCount = transactionRepository.updateCategorizationStatusBulk(
                TransactionCategorizationStatus.LLM_CATEGORIZING,
                TransactionCategorizationStatus.TO_BE_LLM_CATEGORIZED);
        
        if (resetCount > 0) {
            log.warn("Reset {} transaction(s) from LLM_CATEGORIZING to TO_BE_LLM_CATEGORIZED for tenant {}", 
                    resetCount, TenantContext.getTenantId());
        }
    }

    /**
     * Scheduled task to categorize transactions across all tenants.
     * This method iterates over all active tenants (COMPLETE registrations),
     * sets the tenant context for each, and processes transactions awaiting categorization.
     * 
     * The scheduler rate is configurable via application.properties:
     * spendmanager.categorization.scheduler-rate (default: 30000ms = 30 seconds)
     */
    @Scheduled(fixedRateString = "${spendmanager.categorization.scheduler-rate:30000}")
    public void categorizeTransactions() {
        log.debug("Starting scheduled transaction categorization across all tenants");
        
        List<Registration> activeTenants = registrationRepository.findByStatus(RegistrationStatus.COMPLETE);
        log.debug("Found {} active tenants to process", activeTenants.size());
        
        for (Registration tenant : activeTenants) {
            String tenantId = tenant.getRegistrationId();
            try {
                TenantContext.setTenantId(tenantId);
                log.debug("Processing transaction categorization for tenant: {}", tenantId);
                
                categorizeTransactionsForCurrentTenant();
                
            } catch (Exception e) {
                log.error("Error categorizing transactions for tenant {}: {}", tenantId, e.getMessage(), e);
            } finally {
                TenantContext.clear();
            }
        }
        
        log.debug("Completed scheduled transaction categorization");
    }

    /**
     * Categorizes all pending transactions for the current tenant using batch processing.
     * Transactions are batched together (can be from different statements) and processed
     * sequentially (one batch after another, not in parallel).
     * 
     * After each batch's LLM call completes, each transaction in the batch is updated
     * and its statement completion is checked individually.
     */
    private void categorizeTransactionsForCurrentTenant() {
        // Find all transactions awaiting categorization
        List<Transaction> pendingTransactions = transactionRepository
                .findByCategorizationStatus(TransactionCategorizationStatus.TO_BE_LLM_CATEGORIZED);
        
        if (pendingTransactions.isEmpty()) {
            log.debug("No transactions pending categorization for tenant {}", TenantContext.getTenantId());
            return;
        }
        
        int totalPending = pendingTransactions.size();
        int batchSize = ragService.getBatchSize();
        int numBatches = (int) Math.ceil((double) totalPending / batchSize);
        
        log.debug("Found {} transactions pending categorization for tenant {}. Processing in {} batches (batch size: {})", 
                totalPending, TenantContext.getTenantId(), numBatches, batchSize);
        
        // Process batches sequentially
        for (int batchIndex = 0; batchIndex < numBatches; batchIndex++) {
            int startIdx = batchIndex * batchSize;
            int endIdx = Math.min(startIdx + batchSize, totalPending);
            List<Transaction> batch = pendingTransactions.subList(startIdx, endIdx);
            
            try {
                processBatch(batch, batchIndex + 1, numBatches);
            } catch (Exception e) {
                log.error("Error processing batch {}/{}: {}", batchIndex + 1, numBatches, e.getMessage(), e);
                // Mark all statements in this batch as FAILED
                markStatementsFailedForBatch(batch);
                // Continue with next batch (other batches may have different statements)
            }
        }
    }
    
    /**
     * Process a single batch of transactions.
     * 
     * Steps:
     * 1. Mark all transactions in the batch as LLM_CATEGORIZING
     * 2. Call RagService to categorize the batch (single LLM call)
     * 3. Update each transaction with its category and mark as LLM_CATEGORIZED
     * 4. Check statement completion for each transaction (handles cross-statement batches)
     * 
     * @param batch List of transactions to process
     * @param batchNumber Current batch number (1-based, for logging)
     * @param totalBatches Total number of batches (for logging)
     */
    @Transactional
    public void processBatch(List<Transaction> batch, int batchNumber, int totalBatches) {
        if (batch == null || batch.isEmpty()) {
            return;
        }
        
        long batchStart = System.currentTimeMillis();
        log.info("LLM Categorization for Batch {} of {} started", batchNumber, totalBatches);
        
        // Step 1: Mark all transactions in the batch as LLM_CATEGORIZING
        for (Transaction transaction : batch) {
            transaction.setCategorizationStatus(TransactionCategorizationStatus.LLM_CATEGORIZING);
            transactionRepository.save(transaction);
        }
        log.debug("Batch {}: All {} transactions marked as LLM_CATEGORIZING", batchNumber, batch.size());
        
        // Step 2: Call RAG service to categorize the batch (returns Map<TransactionId, CategoryName>)
        Map<Long, String> categoryResults = ragService.categorizeBatchSequential(batch);
        log.debug("Batch {}: LLM returned {} category assignments", batchNumber, categoryResults.size());
        
        // Step 3 & 4: Update each transaction and check statement completion
        // Collect unique statement IDs to check completion later
        Set<Long> statementIdsToCheck = new HashSet<>();
        
        for (Transaction transaction : batch) {
            Long transactionId = transaction.getId();
            Long statementId = transaction.getStatementId();
            
            // Get category from LLM result (or use fallback)
            String categoryName = categoryResults.get(transactionId);
            if (categoryName == null) {
                log.warn("Batch {}: No category returned for transaction {}, using 'Other' as fallback", 
                        batchNumber, transactionId);
                categoryName = "Other";
            }
            
            // Resolve category entity
            Category categoryEntity = categoryService.findByName(categoryName);
            if (categoryEntity == null) {
                log.warn("Batch {}: Category '{}' not found, using 'Other' as fallback for transaction {}", 
                        batchNumber, categoryName, transactionId);
                categoryEntity = categoryService.findByName("Other");
                categoryName = "Other";
            }
            
            // Update transaction
            transaction.setCategory(categoryName);
            transaction.setCategoryEntity(categoryEntity);
            transaction.setCategorizationStatus(TransactionCategorizationStatus.LLM_CATEGORIZED);
            transactionRepository.save(transaction);
            
            log.debug("Batch {}: Transaction {} categorized as '{}'", batchNumber, transactionId, categoryName);
            
            // Collect statement ID for completion check
            if (statementId != null) {
                statementIdsToCheck.add(statementId);
            }
        }
        
        // Step 4: Check statement completion for all affected statements
        // This handles the case where a batch contains transactions from multiple statements
        for (Long statementId : statementIdsToCheck) {
            checkAndCompleteStatement(statementId);
        }
        
        double batchDurationSeconds = (System.currentTimeMillis() - batchStart) / 1000.0;
        log.info("LLM Categorization for Batch {} of {} completed. Time taken - {} seconds", 
                batchNumber, totalBatches, String.format("%.2f", batchDurationSeconds));
    }
    
    /**
     * Mark all statements associated with transactions in the batch as FAILED.
     * Used when batch processing fails.
     * 
     * @param batch List of transactions in the failed batch
     */
    private void markStatementsFailedForBatch(List<Transaction> batch) {
        Set<Long> failedStatementIds = new HashSet<>();
        for (Transaction transaction : batch) {
            if (transaction.getStatementId() != null) {
                failedStatementIds.add(transaction.getStatementId());
            }
        }
        
        for (Long statementId : failedStatementIds) {
            markStatementFailed(statementId);
        }
        
        log.warn("Marked {} statements as FAILED due to batch processing error", failedStatementIds.size());
    }

    /**
     * Categorizes a single transaction using RAG (similarity search) + LLM.
     * 
     * Process:
     * 1. Set transaction status to LLM_CATEGORIZING
     * 2. Call RagService.findBestCategory (includes similarity search)
     * 3. Set category and status to LLM_CATEGORIZED
     * 4. Check if all transactions for the statement are categorized
     * 5. If yes, mark statement as COMPLETED
     * 
     * @param transaction The transaction to categorize
     */
    @Transactional
    public void categorizeTransactionWithRag(Transaction transaction) {
        Long transactionId = transaction.getId();
        Long statementId = transaction.getStatementId();
        
        log.info("Starting categorization for transaction {} (statement {})", transactionId, statementId);
        
        // Step 1: Set status to LLM_CATEGORIZING immediately
        transaction.setCategorizationStatus(TransactionCategorizationStatus.LLM_CATEGORIZING);
        transactionRepository.save(transaction);
        log.debug("Transaction {} status set to LLM_CATEGORIZING", transactionId);
        
        // Step 2: Call RAG service to find best category (includes similarity search)
        String categoryName = ragService.findBestCategory(transaction);
        log.info("RAG categorization result for transaction {}: '{}'", transactionId, categoryName);
        
        // Step 3: Resolve category entity and update transaction
        Category categoryEntity = categoryService.findByName(categoryName);
        if (categoryEntity == null) {
            log.warn("Category '{}' not found, using 'Other' as fallback for transaction {}", 
                    categoryName, transactionId);
            categoryEntity = categoryService.findByName("Other");
        }
        
        transaction.setCategory(categoryName);
        transaction.setCategoryEntity(categoryEntity);
        transaction.setCategorizationStatus(TransactionCategorizationStatus.LLM_CATEGORIZED);
        transactionRepository.save(transaction);
        
        log.info("Transaction {} categorized as '{}' and marked as LLM_CATEGORIZED", 
                transactionId, categoryName);
        
        // Step 4: Check if all transactions for this statement are now categorized
        if (statementId != null) {
            checkAndCompleteStatement(statementId);
        }
    }

    /**
     * Checks if all transactions for a statement have been categorized.
     * If no transactions remain with TO_BE_LLM_CATEGORIZED status, marks the statement as COMPLETED
     * and records the LLM categorization end time.
     * 
     * @param statementId The statement ID to check
     */
    @Transactional
    public void checkAndCompleteStatement(Long statementId) {
        long pendingCount = transactionRepository.countByStatementIdAndCategorizationStatus(
                statementId, TransactionCategorizationStatus.TO_BE_LLM_CATEGORIZED);
        
        long categorizingCount = transactionRepository.countByStatementIdAndCategorizationStatus(
                statementId, TransactionCategorizationStatus.LLM_CATEGORIZING);
        
        log.debug("Statement {} - pending: {}, categorizing: {}", statementId, pendingCount, categorizingCount);
        
        // Only mark as COMPLETED if no transactions are pending or in-progress
        if (pendingCount == 0 && categorizingCount == 0) {
            Statement statement = statementService.getStatementById(statementId);
            if (statement != null && statement.getStatus() == StatementStatus.CATEGORIZING) {
                // Record LLM categorization end time
                LocalDateTime endTime = LocalDateTime.now();
                statement.setLlmCategorizationEnd(endTime);
                statement.setStatus(StatementStatus.COMPLETED);
                statementService.saveStatement(statement);
                
                // Calculate and log processing duration
                LocalDateTime startTime = statement.getLlmCategorizationStart();
                if (startTime != null) {
                    long durationSeconds = Duration.between(startTime, endTime).getSeconds();
                    log.info("Processing of statement {} completed. Duration - {} second(s)", 
                            statementId, durationSeconds);
                } else {
                    log.info("Processing of statement {} completed", statementId);
                }
            }
        } else {
            log.debug("Statement {} still has {} pending and {} categorizing transactions", 
                    statementId, pendingCount, categorizingCount);
        }
    }

    /**
     * Marks a statement as FAILED when categorization fails.
     * Also deletes all transactions associated with the failed statement to clean up.
     * 
     * @param statementId The statement ID to mark as failed
     */
    @Transactional
    public void markStatementFailed(Long statementId) {
        if (statementId == null) {
            log.warn("Cannot mark statement as FAILED: statementId is null");
            return;
        }
        
        try {
            // First, delete all transactions associated with this statement
            transactionRepository.deleteByStatementId(statementId);
            log.info("Deleted all transactions for failed statement {}", statementId);
            
            // Then mark the statement as FAILED
            Statement statement = statementService.getStatementById(statementId);
            if (statement != null) {
                statement.setStatus(StatementStatus.FAILED);
                statementService.saveStatement(statement);
                log.warn("Marked statement {} as FAILED due to categorization error", statementId);
            }
        } catch (Exception e) {
            log.error("Failed to mark statement {} as FAILED: {}", statementId, e.getMessage(), e);
        }
    }
}