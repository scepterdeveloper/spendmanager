package com.everrich.spendmanager.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.everrich.spendmanager.entities.Account;
import com.everrich.spendmanager.entities.AccountBalance;
import com.everrich.spendmanager.entities.Transaction;
import com.everrich.spendmanager.entities.TransactionOperation;
import com.everrich.spendmanager.multitenancy.TenantContext;
import com.everrich.spendmanager.repository.AccountBalanceRepository;

/**
 * Service for managing account balances.
 * Balance updates are performed asynchronously with transactional integrity.
 * 
 * Uses per-account locking to prevent race conditions when multiple transactions
 * for the same account are processed concurrently.
 */
@Service
public class AccountBalanceService {

    private static final Logger log = LoggerFactory.getLogger(AccountBalanceService.class);

    private final AccountBalanceRepository accountBalanceRepository;
    private final TransactionTemplate transactionTemplate;
    
    /**
     * Per-account locks to serialize balance operations for the same account.
     * Key format: "tenantId:accountId" to handle multi-tenancy.
     */
    private final ConcurrentHashMap<String, ReentrantLock> accountLocks = new ConcurrentHashMap<>();

    public AccountBalanceService(AccountBalanceRepository accountBalanceRepository,
                                  TransactionTemplate transactionTemplate) {
        this.accountBalanceRepository = accountBalanceRepository;
        this.transactionTemplate = transactionTemplate;
        log.info("AccountBalanceService initialized with per-account locking");
    }
    
    /**
     * Gets or creates a lock for a specific account within a tenant.
     */
    private ReentrantLock getLockForAccount(String tenantId, Long accountId) {
        String lockKey = tenantId + ":" + accountId;
        return accountLocks.computeIfAbsent(lockKey, k -> new ReentrantLock(true)); // fair lock
    }

    /**
     * Synchronously creates a balance entry for a new transaction with application-level locking.
     * Use this when processing batches within the same transaction (e.g., statement processing).
     * 
     * @param transaction The newly created transaction
     */
    public void processTransactionCreateSync(Transaction transaction) {
        if (transaction.getAccount() == null) {
            log.warn("Cannot process balance: transaction account is null for ID: {}", transaction.getId());
            return;
        }
        
        String tenantId = TenantContext.getTenantId();
        Long accountId = transaction.getAccount().getId();
        ReentrantLock lock = getLockForAccount(tenantId, accountId);
        
        log.debug("Acquiring sync lock for account {} in tenant {} for transaction {}", accountId, tenantId, transaction.getId());
        lock.lock();
        try {
            log.info("Processing sync balance update for new transaction ID: {} (lock acquired)", transaction.getId());
            createBalanceForTransaction(transaction);
            log.info("Sync balance update completed for transaction ID: {}", transaction.getId());
        } finally {
            lock.unlock();
            log.debug("Released sync lock for account {} in tenant {} for transaction {}", accountId, tenantId, transaction.getId());
        }
    }
    
    /**
     * Synchronously updates balances when a transaction is modified with application-level locking.
     * Use this when processing batches within the same transaction.
     * 
     * @param oldTransaction The original transaction state
     * @param newTransaction The updated transaction state
     */
    public void processTransactionUpdateSync(Transaction oldTransaction, Transaction newTransaction) {
        if (newTransaction.getAccount() == null) {
            log.warn("Cannot process balance update: new transaction account is null for ID: {}", newTransaction.getId());
            return;
        }
        
        String tenantId = TenantContext.getTenantId();
        Long accountId = newTransaction.getAccount().getId();
        Long oldAccountId = oldTransaction.getAccount() != null ? oldTransaction.getAccount().getId() : accountId;
        
        // Lock accounts in consistent order to prevent deadlock
        Long firstLockAccountId = Math.min(accountId, oldAccountId);
        Long secondLockAccountId = Math.max(accountId, oldAccountId);
        
        ReentrantLock firstLock = getLockForAccount(tenantId, firstLockAccountId);
        ReentrantLock secondLock = accountId.equals(oldAccountId) ? null : getLockForAccount(tenantId, secondLockAccountId);
        
        log.debug("Acquiring sync lock(s) for account update in tenant {} for transaction {}", tenantId, newTransaction.getId());
        firstLock.lock();
        try {
            if (secondLock != null) {
                secondLock.lock();
            }
            try {
                log.info("Processing sync balance update for modified transaction ID: {} (lock acquired)", newTransaction.getId());
                updateBalanceForTransaction(oldTransaction, newTransaction);
                log.info("Sync balance update completed for modified transaction ID: {}", newTransaction.getId());
            } finally {
                if (secondLock != null) {
                    secondLock.unlock();
                }
            }
        } finally {
            firstLock.unlock();
            log.debug("Released sync lock(s) for account update in tenant {} for transaction {}", tenantId, newTransaction.getId());
        }
    }
    
    /**
     * Synchronously removes the balance entry when a transaction is deleted with application-level locking.
     * Use this when processing batches within the same transaction.
     * 
     * @param transaction The deleted transaction
     */
    public void processTransactionDeleteSync(Transaction transaction) {
        if (transaction.getAccount() == null) {
            log.warn("Cannot process balance deletion: transaction account is null for ID: {}", transaction.getId());
            return;
        }
        
        String tenantId = TenantContext.getTenantId();
        Long accountId = transaction.getAccount().getId();
        ReentrantLock lock = getLockForAccount(tenantId, accountId);
        
        log.debug("Acquiring sync lock for account {} in tenant {} for deleting transaction {}", accountId, tenantId, transaction.getId());
        lock.lock();
        try {
            log.info("Processing sync balance removal for deleted transaction ID: {} (lock acquired)", transaction.getId());
            deleteBalanceForTransaction(transaction);
            log.info("Sync balance removal completed for transaction ID: {}", transaction.getId());
        } finally {
            lock.unlock();
            log.debug("Released sync lock for account {} in tenant {} after deleting transaction {}", accountId, tenantId, transaction.getId());
        }
    }

    /**
     * Asynchronously creates or updates the balance entry for a new transaction.
     * This method runs in a separate thread with its own transaction.
     * Uses per-account locking to prevent race conditions.
     * 
     * @param transaction The newly created transaction
     * @param tenantId The tenant ID for multi-tenancy context
     */
    @Async("transactionProcessingExecutor")
    public void processTransactionCreateAsync(Transaction transaction, String tenantId) {
        if (transaction.getAccount() == null) {
            log.warn("Cannot process balance: transaction account is null for ID: {}", transaction.getId());
            return;
        }
        
        Long accountId = transaction.getAccount().getId();
        ReentrantLock lock = getLockForAccount(tenantId, accountId);
        
        log.debug("Acquiring lock for account {} in tenant {} for transaction {}", accountId, tenantId, transaction.getId());
        lock.lock();
        try {
            // Set tenant context for the async thread
            TenantContext.setTenantId(tenantId);
            try {
                log.info("Processing balance update for new transaction ID: {} in tenant: {} (lock acquired)", 
                        transaction.getId(), tenantId);
                // Use TransactionTemplate for programmatic transaction management
                // (self-invocation doesn't go through Spring proxy, so @Transactional won't work)
                transactionTemplate.executeWithoutResult(status -> {
                    createBalanceForTransaction(transaction);
                });
                log.info("Balance update completed for transaction ID: {}", transaction.getId());
            } catch (Exception e) {
                log.error("Error processing balance for transaction ID: {}", transaction.getId(), e);
                throw e;
            } finally {
                TenantContext.clear();
            }
        } finally {
            lock.unlock();
            log.debug("Released lock for account {} in tenant {} for transaction {}", accountId, tenantId, transaction.getId());
        }
    }

    /**
     * Asynchronously updates balances when a transaction is modified.
     * Handles changes to amount, operation, date, or account.
     * Uses per-account locking to prevent race conditions.
     * 
     * @param oldTransaction The original transaction state
     * @param newTransaction The updated transaction state
     * @param tenantId The tenant ID for multi-tenancy context
     */
    @Async("transactionProcessingExecutor")
    public void processTransactionUpdateAsync(Transaction oldTransaction, Transaction newTransaction, String tenantId) {
        if (newTransaction.getAccount() == null) {
            log.warn("Cannot process balance update: new transaction account is null for ID: {}", newTransaction.getId());
            return;
        }
        
        Long accountId = newTransaction.getAccount().getId();
        // If account changed, we need to lock both accounts
        Long oldAccountId = oldTransaction.getAccount() != null ? oldTransaction.getAccount().getId() : accountId;
        
        // Lock accounts in consistent order to prevent deadlock
        Long firstLockAccountId = Math.min(accountId, oldAccountId);
        Long secondLockAccountId = Math.max(accountId, oldAccountId);
        
        ReentrantLock firstLock = getLockForAccount(tenantId, firstLockAccountId);
        ReentrantLock secondLock = accountId.equals(oldAccountId) ? null : getLockForAccount(tenantId, secondLockAccountId);
        
        log.debug("Acquiring lock(s) for account update in tenant {} for transaction {}", tenantId, newTransaction.getId());
        firstLock.lock();
        try {
            if (secondLock != null) {
                secondLock.lock();
            }
            try {
                TenantContext.setTenantId(tenantId);
                try {
                    log.info("Processing balance update for modified transaction ID: {} in tenant: {} (lock acquired)", 
                            newTransaction.getId(), tenantId);
                    // Use TransactionTemplate for programmatic transaction management
                    transactionTemplate.executeWithoutResult(status -> {
                        updateBalanceForTransaction(oldTransaction, newTransaction);
                    });
                    log.info("Balance update completed for modified transaction ID: {}", newTransaction.getId());
                } catch (Exception e) {
                    log.error("Error updating balance for transaction ID: {}", newTransaction.getId(), e);
                    throw e;
                } finally {
                    TenantContext.clear();
                }
            } finally {
                if (secondLock != null) {
                    secondLock.unlock();
                }
            }
        } finally {
            firstLock.unlock();
            log.debug("Released lock(s) for account update in tenant {} for transaction {}", tenantId, newTransaction.getId());
        }
    }

    /**
     * Asynchronously removes the balance entry and adjusts subsequent balances
     * when a transaction is deleted.
     * Uses per-account locking to prevent race conditions.
     * 
     * @param transaction The deleted transaction
     * @param tenantId The tenant ID for multi-tenancy context
     */
    @Async("transactionProcessingExecutor")
    public void processTransactionDeleteAsync(Transaction transaction, String tenantId) {
        if (transaction.getAccount() == null) {
            log.warn("Cannot process balance deletion: transaction account is null for ID: {}", transaction.getId());
            return;
        }
        
        Long accountId = transaction.getAccount().getId();
        ReentrantLock lock = getLockForAccount(tenantId, accountId);
        
        log.debug("Acquiring lock for account {} in tenant {} for deleting transaction {}", accountId, tenantId, transaction.getId());
        lock.lock();
        try {
            TenantContext.setTenantId(tenantId);
            try {
                log.info("Processing balance removal for deleted transaction ID: {} in tenant: {} (lock acquired)", 
                        transaction.getId(), tenantId);
                // Use TransactionTemplate for programmatic transaction management
                transactionTemplate.executeWithoutResult(status -> {
                    deleteBalanceForTransaction(transaction);
                });
                log.info("Balance removal completed for transaction ID: {}", transaction.getId());
            } catch (Exception e) {
                log.error("Error removing balance for transaction ID: {}", transaction.getId(), e);
                throw e;
            } finally {
                TenantContext.clear();
            }
        } finally {
            lock.unlock();
            log.debug("Released lock for account {} in tenant {} after deleting transaction {}", accountId, tenantId, transaction.getId());
        }
    }

    /**
     * Creates a balance entry for a new transaction and updates subsequent balances.
     * Note: This method should be called within a transaction (via TransactionTemplate).
     * 
     * Uses pessimistic locking to prevent race conditions when multiple transactions
     * are processed concurrently for the same account.
     * 
     * Uses transaction ID as a tiebreaker when multiple transactions have the same timestamp
     * to ensure proper ordering and balance calculation.
     */
    public void createBalanceForTransaction(Transaction transaction) {
        if (transaction.getAccount() == null || transaction.getDate() == null) {
            log.warn("Cannot create balance: transaction account or date is null for ID: {}", transaction.getId());
            return;
        }

        Account account = transaction.getAccount();
        LocalDateTime transactionDate = transaction.getDate();
        Long transactionId = transaction.getId();
        BigDecimal amount = BigDecimal.valueOf(transaction.getAmount());
        TransactionOperation operation = transaction.getOperation();

        // IMPORTANT: First acquire a pessimistic lock on the latest balance for this account.
        // This serializes concurrent balance operations for the same account, preventing race conditions.
        // If no balance exists yet, the lock query returns empty, but subsequent operations
        // are still safe because we use pessimistic locking on the preceding balance query.
        Optional<AccountBalance> latestBalanceOpt = accountBalanceRepository.findLatestBalanceWithLock(account.getId());
        if (latestBalanceOpt.isPresent()) {
            log.debug("Acquired pessimistic lock on latest balance for account {} (balance ID: {})", 
                    account.getId(), latestBalanceOpt.get().getId());
        } else {
            log.debug("No existing balance to lock for account {} - this is the first transaction", account.getId());
        }

        // Find the balance immediately before this transaction using both timestamp AND transaction ID.
        // This correctly handles same-timestamp transactions by using transaction ID as tiebreaker.
        BigDecimal precedingBalance = accountBalanceRepository
                .findPrecedingBalanceByTimestampAndTransactionIdWithLock(account.getId(), transactionDate, transactionId)
                .map(AccountBalance::getBalance)
                .orElse(BigDecimal.ZERO);

        log.debug("Preceding balance for account {} at {} (txn {}): {}", 
                account.getId(), transactionDate, transactionId, precedingBalance);

        // Calculate the new balance
        BigDecimal newBalance = calculateNewBalance(precedingBalance, amount, operation);

        // Create the balance entry
        AccountBalance accountBalance = new AccountBalance(account, transactionDate, newBalance, transaction);
        accountBalanceRepository.save(accountBalance);
        log.debug("Created balance entry for transaction ID: {}, balance: {}", transaction.getId(), newBalance);

        // Adjust all subsequent balances (those with higher timestamp OR same timestamp but higher transaction ID)
        BigDecimal adjustment = getAdjustmentAmount(amount, operation);
        int updatedCount = accountBalanceRepository.adjustSubsequentBalancesByTimestampAndTransactionId(
                account.getId(), transactionDate, transactionId, adjustment);
        log.debug("Adjusted {} subsequent balance entries by {}", updatedCount, adjustment);
    }

    /**
     * Updates the balance when a transaction is modified.
     * Note: This method should be called within a transaction (via TransactionTemplate).
     * 
     * Uses pessimistic locking via the delegated methods to prevent race conditions.
     */
    public void updateBalanceForTransaction(Transaction oldTransaction, Transaction newTransaction) {
        boolean accountChanged = !oldTransaction.getAccount().getId().equals(newTransaction.getAccount().getId());
        boolean dateChanged = !oldTransaction.getDate().equals(newTransaction.getDate());
        boolean amountOrOperationChanged = oldTransaction.getAmount() != newTransaction.getAmount() ||
                oldTransaction.getOperation() != newTransaction.getOperation();

        if (accountChanged) {
            // Account changed: remove from old account and add to new account
            log.debug("Account changed for transaction ID: {} from {} to {}", 
                    newTransaction.getId(), oldTransaction.getAccount().getId(), newTransaction.getAccount().getId());
            // Acquire lock on old account first
            accountBalanceRepository.findLatestBalanceWithLock(oldTransaction.getAccount().getId());
            deleteBalanceForTransaction(oldTransaction);
            createBalanceForTransaction(newTransaction);
        } else if (dateChanged) {
            // Date changed: remove old balance and create new one with cascade updates
            log.debug("Date changed for transaction ID: {}", newTransaction.getId());
            deleteBalanceForTransaction(oldTransaction);
            createBalanceForTransaction(newTransaction);
        } else if (amountOrOperationChanged) {
            // Only amount or operation changed: adjust current and subsequent balances
            log.debug("Amount/operation changed for transaction ID: {}", newTransaction.getId());
            adjustBalanceForAmountChange(oldTransaction, newTransaction);
        }
        // If nothing relevant changed, no update needed
    }

    /**
     * Deletes the balance entry for a transaction and adjusts subsequent balances.
     * Note: This method should be called within a transaction (via TransactionTemplate).
     * 
     * Uses pessimistic locking to prevent race conditions when multiple transactions
     * are processed concurrently for the same account.
     * 
     * Uses transaction ID as a tiebreaker to properly identify subsequent balances
     * when multiple transactions have the same timestamp.
     */
    public void deleteBalanceForTransaction(Transaction transaction) {
        if (transaction.getAccount() == null || transaction.getDate() == null) {
            log.warn("Cannot delete balance: transaction account or date is null for ID: {}", transaction.getId());
            return;
        }

        Account account = transaction.getAccount();
        LocalDateTime transactionDate = transaction.getDate();
        Long transactionId = transaction.getId();
        BigDecimal amount = BigDecimal.valueOf(transaction.getAmount());
        TransactionOperation operation = transaction.getOperation();

        // Acquire pessimistic lock on the latest balance to serialize operations
        accountBalanceRepository.findLatestBalanceWithLock(account.getId());
        log.debug("Acquired pessimistic lock for delete operation on account {}", account.getId());

        // First, adjust subsequent balances by reversing the transaction's effect
        // Uses transaction ID as tiebreaker for same-timestamp transactions
        BigDecimal reverseAdjustment = getAdjustmentAmount(amount, operation).negate();
        int updatedCount = accountBalanceRepository.adjustSubsequentBalancesByTimestampAndTransactionId(
                account.getId(), transactionDate, transactionId, reverseAdjustment);
        log.debug("Reversed adjustment for {} subsequent balance entries by {}", updatedCount, reverseAdjustment);

        // Then delete the balance entry
        int deletedCount = accountBalanceRepository.deleteByTransactionId(transaction.getId());
        log.debug("Deleted {} balance entry for transaction ID: {}", deletedCount, transaction.getId());
    }

    /**
     * Adjusts balances when only the amount or operation changes (account and date remain the same).
     * Uses pessimistic locking to prevent race conditions.
     * Uses transaction ID as a tiebreaker for same-timestamp transactions.
     */
    private void adjustBalanceForAmountChange(Transaction oldTransaction, Transaction newTransaction) {
        Account account = newTransaction.getAccount();
        LocalDateTime transactionDate = newTransaction.getDate();
        Long transactionId = newTransaction.getId();

        // Acquire pessimistic lock on the latest balance to serialize operations
        accountBalanceRepository.findLatestBalanceWithLock(account.getId());
        log.debug("Acquired pessimistic lock for amount change on account {}", account.getId());

        // Calculate the difference between old and new effect
        BigDecimal oldEffect = getAdjustmentAmount(BigDecimal.valueOf(oldTransaction.getAmount()), oldTransaction.getOperation());
        BigDecimal newEffect = getAdjustmentAmount(BigDecimal.valueOf(newTransaction.getAmount()), newTransaction.getOperation());
        BigDecimal netAdjustment = newEffect.subtract(oldEffect);

        // Update the current balance entry
        Optional<AccountBalance> currentBalanceOpt = accountBalanceRepository.findByTransactionId(newTransaction.getId());
        if (currentBalanceOpt.isPresent()) {
            AccountBalance currentBalance = currentBalanceOpt.get();
            currentBalance.setBalance(currentBalance.getBalance().add(netAdjustment));
            accountBalanceRepository.save(currentBalance);
            log.debug("Updated balance entry for transaction ID: {} by {}", newTransaction.getId(), netAdjustment);
        }

        // Adjust all subsequent balances using transaction ID as tiebreaker
        int updatedCount = accountBalanceRepository.adjustSubsequentBalancesByTimestampAndTransactionId(
                account.getId(), transactionDate, transactionId, netAdjustment);
        log.debug("Adjusted {} subsequent balance entries by {}", updatedCount, netAdjustment);
    }

    /**
     * Calculate new balance based on preceding balance, amount, and operation.
     */
    private BigDecimal calculateNewBalance(BigDecimal precedingBalance, BigDecimal amount, TransactionOperation operation) {
        if (operation == TransactionOperation.PLUS) {
            return precedingBalance.add(amount);
        } else {
            return precedingBalance.subtract(amount);
        }
    }

    /**
     * Get the adjustment amount based on operation (positive for PLUS, negative for MINUS).
     */
    private BigDecimal getAdjustmentAmount(BigDecimal amount, TransactionOperation operation) {
        if (operation == TransactionOperation.PLUS) {
            return amount;
        } else {
            return amount.negate();
        }
    }

    // ========== Query Methods for Balances Page ==========

    /**
     * Get the balance at or before a specific point in time for an account.
     * Returns 0.00 if no balance entry exists before the given time.
     */
    public BigDecimal getBalanceAtOrBefore(Long accountId, LocalDateTime dateTime) {
        return accountBalanceRepository.findBalanceAtOrBefore(accountId, dateTime)
                .map(AccountBalance::getBalance)
                .orElse(BigDecimal.ZERO);
    }

    /**
     * Get the latest balance for an account.
     */
    public BigDecimal getLatestBalance(Long accountId) {
        return accountBalanceRepository.findLatestBalance(accountId)
                .map(AccountBalance::getBalance)
                .orElse(BigDecimal.ZERO);
    }

    /**
     * Get all balance entries for an account, ordered by timestamp.
     */
    public List<AccountBalance> getBalanceHistory(Long accountId) {
        return accountBalanceRepository.findByAccountIdOrderByTimestampAsc(accountId);
    }
}