package com.everrich.spendmanager.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.everrich.spendmanager.entities.Account;
import com.everrich.spendmanager.entities.AccountBalance;
import com.everrich.spendmanager.entities.Transaction;
import com.everrich.spendmanager.entities.TransactionOperation;
import com.everrich.spendmanager.multitenancy.TenantContext;
import com.everrich.spendmanager.repository.AccountBalanceRepository;

/**
 * Service for managing account balances.
 * Balance updates are performed asynchronously with transactional integrity.
 */
@Service
public class AccountBalanceService {

    private static final Logger log = LoggerFactory.getLogger(AccountBalanceService.class);

    private final AccountBalanceRepository accountBalanceRepository;

    public AccountBalanceService(AccountBalanceRepository accountBalanceRepository) {
        this.accountBalanceRepository = accountBalanceRepository;
        log.info("AccountBalanceService initialized");
    }

    /**
     * Asynchronously creates or updates the balance entry for a new transaction.
     * This method runs in a separate thread with its own transaction.
     * 
     * @param transaction The newly created transaction
     */
    @Async("transactionProcessingExecutor")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processTransactionCreateAsync(Transaction transaction, String tenantId) {
        // Set tenant context for the async thread
        TenantContext.setTenantId(tenantId);
        try {
            log.info("Processing balance update for new transaction ID: {} in tenant: {}", 
                    transaction.getId(), tenantId);
            createBalanceForTransaction(transaction);
            log.info("Balance update completed for transaction ID: {}", transaction.getId());
        } catch (Exception e) {
            log.error("Error processing balance for transaction ID: {}", transaction.getId(), e);
            throw e; // Re-throw to trigger transaction rollback
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * Asynchronously updates balances when a transaction is modified.
     * Handles changes to amount, operation, date, or account.
     * 
     * @param oldTransaction The original transaction state
     * @param newTransaction The updated transaction state
     */
    @Async("transactionProcessingExecutor")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processTransactionUpdateAsync(Transaction oldTransaction, Transaction newTransaction, String tenantId) {
        TenantContext.setTenantId(tenantId);
        try {
            log.info("Processing balance update for modified transaction ID: {} in tenant: {}", 
                    newTransaction.getId(), tenantId);
            updateBalanceForTransaction(oldTransaction, newTransaction);
            log.info("Balance update completed for modified transaction ID: {}", newTransaction.getId());
        } catch (Exception e) {
            log.error("Error updating balance for transaction ID: {}", newTransaction.getId(), e);
            throw e;
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * Asynchronously removes the balance entry and adjusts subsequent balances
     * when a transaction is deleted.
     * 
     * @param transaction The deleted transaction
     */
    @Async("transactionProcessingExecutor")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processTransactionDeleteAsync(Transaction transaction, String tenantId) {
        TenantContext.setTenantId(tenantId);
        try {
            log.info("Processing balance removal for deleted transaction ID: {} in tenant: {}", 
                    transaction.getId(), tenantId);
            deleteBalanceForTransaction(transaction);
            log.info("Balance removal completed for transaction ID: {}", transaction.getId());
        } catch (Exception e) {
            log.error("Error removing balance for transaction ID: {}", transaction.getId(), e);
            throw e;
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * Creates a balance entry for a new transaction and updates subsequent balances.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void createBalanceForTransaction(Transaction transaction) {
        if (transaction.getAccount() == null || transaction.getDate() == null) {
            log.warn("Cannot create balance: transaction account or date is null for ID: {}", transaction.getId());
            return;
        }

        Account account = transaction.getAccount();
        LocalDateTime transactionDate = transaction.getDate();
        BigDecimal amount = BigDecimal.valueOf(transaction.getAmount());
        TransactionOperation operation = transaction.getOperation();

        // Find the balance immediately before this transaction
        BigDecimal precedingBalance = accountBalanceRepository
                .findPrecedingBalance(account.getId(), transactionDate)
                .map(AccountBalance::getBalance)
                .orElse(BigDecimal.ZERO);

        // Calculate the new balance
        BigDecimal newBalance = calculateNewBalance(precedingBalance, amount, operation);

        // Create the balance entry
        AccountBalance accountBalance = new AccountBalance(account, transactionDate, newBalance, transaction);
        accountBalanceRepository.save(accountBalance);
        log.debug("Created balance entry for transaction ID: {}, balance: {}", transaction.getId(), newBalance);

        // Adjust all subsequent balances
        BigDecimal adjustment = getAdjustmentAmount(amount, operation);
        int updatedCount = accountBalanceRepository.adjustSubsequentBalances(
                account.getId(), transactionDate, adjustment);
        log.debug("Adjusted {} subsequent balance entries by {}", updatedCount, adjustment);
    }

    /**
     * Updates the balance when a transaction is modified.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void updateBalanceForTransaction(Transaction oldTransaction, Transaction newTransaction) {
        boolean accountChanged = !oldTransaction.getAccount().getId().equals(newTransaction.getAccount().getId());
        boolean dateChanged = !oldTransaction.getDate().equals(newTransaction.getDate());
        boolean amountOrOperationChanged = oldTransaction.getAmount() != newTransaction.getAmount() ||
                oldTransaction.getOperation() != newTransaction.getOperation();

        if (accountChanged) {
            // Account changed: remove from old account and add to new account
            log.debug("Account changed for transaction ID: {} from {} to {}", 
                    newTransaction.getId(), oldTransaction.getAccount().getId(), newTransaction.getAccount().getId());
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
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void deleteBalanceForTransaction(Transaction transaction) {
        if (transaction.getAccount() == null || transaction.getDate() == null) {
            log.warn("Cannot delete balance: transaction account or date is null for ID: {}", transaction.getId());
            return;
        }

        Account account = transaction.getAccount();
        LocalDateTime transactionDate = transaction.getDate();
        BigDecimal amount = BigDecimal.valueOf(transaction.getAmount());
        TransactionOperation operation = transaction.getOperation();

        // First, adjust subsequent balances by reversing the transaction's effect
        BigDecimal reverseAdjustment = getAdjustmentAmount(amount, operation).negate();
        int updatedCount = accountBalanceRepository.adjustSubsequentBalances(
                account.getId(), transactionDate, reverseAdjustment);
        log.debug("Reversed adjustment for {} subsequent balance entries by {}", updatedCount, reverseAdjustment);

        // Then delete the balance entry
        int deletedCount = accountBalanceRepository.deleteByTransactionId(transaction.getId());
        log.debug("Deleted {} balance entry for transaction ID: {}", deletedCount, transaction.getId());
    }

    /**
     * Adjusts balances when only the amount or operation changes (account and date remain the same).
     */
    private void adjustBalanceForAmountChange(Transaction oldTransaction, Transaction newTransaction) {
        Account account = newTransaction.getAccount();
        LocalDateTime transactionDate = newTransaction.getDate();

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

        // Adjust all subsequent balances
        int updatedCount = accountBalanceRepository.adjustSubsequentBalances(
                account.getId(), transactionDate, netAdjustment);
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