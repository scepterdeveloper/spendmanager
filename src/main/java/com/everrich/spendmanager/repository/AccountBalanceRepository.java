package com.everrich.spendmanager.repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.everrich.spendmanager.entities.AccountBalance;
import com.everrich.spendmanager.entities.Account;
import com.everrich.spendmanager.entities.Transaction;

import jakarta.persistence.LockModeType;

@Repository
public interface AccountBalanceRepository extends JpaRepository<AccountBalance, Long> {

    /**
     * Find the balance entry immediately preceding a given timestamp for a specific account.
     * Used to calculate the new balance when a transaction is created.
     * 
     * @deprecated Use findPrecedingBalanceByTimestampAndTransactionId instead to handle same-timestamp transactions.
     */
    @Deprecated
    @Query("SELECT ab FROM AccountBalance ab WHERE ab.account.id = :accountId AND ab.timestamp < :timestamp ORDER BY ab.timestamp DESC LIMIT 1")
    Optional<AccountBalance> findPrecedingBalance(@Param("accountId") Long accountId, @Param("timestamp") LocalDateTime timestamp);

    /**
     * Find the balance entry immediately preceding a given transaction for a specific account.
     * Uses both timestamp and transaction ID to properly order same-timestamp transactions.
     * 
     * A transaction T2 is "after" T1 if:
     * - T2.timestamp > T1.timestamp, OR
     * - T2.timestamp = T1.timestamp AND T2.id > T1.id
     * 
     * This method finds the balance that is strictly "before" the given (timestamp, transactionId).
     */
    @Query("SELECT ab FROM AccountBalance ab WHERE ab.account.id = :accountId " +
           "AND (ab.timestamp < :timestamp OR (ab.timestamp = :timestamp AND ab.transaction.id < :transactionId)) " +
           "ORDER BY ab.timestamp DESC, ab.transaction.id DESC LIMIT 1")
    Optional<AccountBalance> findPrecedingBalanceByTimestampAndTransactionId(
            @Param("accountId") Long accountId, 
            @Param("timestamp") LocalDateTime timestamp,
            @Param("transactionId") Long transactionId);

    /**
     * Find the balance entry at or immediately before a given timestamp for a specific account.
     * Used to get the balance at a specific point in time (e.g., for starting/closing balance).
     */
    @Query("SELECT ab FROM AccountBalance ab WHERE ab.account.id = :accountId AND ab.timestamp <= :timestamp ORDER BY ab.timestamp DESC LIMIT 1")
    Optional<AccountBalance> findBalanceAtOrBefore(@Param("accountId") Long accountId, @Param("timestamp") LocalDateTime timestamp);

    /**
     * Find all balance entries after a given timestamp for a specific account.
     * Used for cascade updates when a new transaction is inserted in the middle of the timeline.
     */
    @Query("SELECT ab FROM AccountBalance ab WHERE ab.account.id = :accountId AND ab.timestamp > :timestamp ORDER BY ab.timestamp ASC")
    List<AccountBalance> findSubsequentBalances(@Param("accountId") Long accountId, @Param("timestamp") LocalDateTime timestamp);

    /**
     * Find the balance entry linked to a specific transaction.
     */
    Optional<AccountBalance> findByTransaction(Transaction transaction);

    /**
     * Find the balance entry by transaction ID.
     */
    @Query("SELECT ab FROM AccountBalance ab WHERE ab.transaction.id = :transactionId")
    Optional<AccountBalance> findByTransactionId(@Param("transactionId") Long transactionId);

    /**
     * Bulk update balances by adding an adjustment amount.
     * Used for cascade updates when a new transaction affects subsequent balances.
     * 
     * @deprecated Use adjustSubsequentBalancesByTimestampAndTransactionId instead to handle same-timestamp transactions.
     */
    @Deprecated
    @Modifying
    @Query("UPDATE AccountBalance ab SET ab.balance = ab.balance + :adjustment WHERE ab.account.id = :accountId AND ab.timestamp > :timestamp")
    int adjustSubsequentBalances(@Param("accountId") Long accountId, @Param("timestamp") LocalDateTime timestamp, @Param("adjustment") BigDecimal adjustment);

    /**
     * Bulk update balances by adding an adjustment amount for all balances after a given transaction.
     * Uses both timestamp and transaction ID to properly order same-timestamp transactions.
     * 
     * Adjusts all balances where:
     * - timestamp > given timestamp, OR
     * - timestamp = given timestamp AND transaction.id > given transactionId
     */
    @Modifying
    @Query("UPDATE AccountBalance ab SET ab.balance = ab.balance + :adjustment " +
           "WHERE ab.account.id = :accountId " +
           "AND (ab.timestamp > :timestamp OR (ab.timestamp = :timestamp AND ab.transaction.id > :transactionId))")
    int adjustSubsequentBalancesByTimestampAndTransactionId(
            @Param("accountId") Long accountId, 
            @Param("timestamp") LocalDateTime timestamp, 
            @Param("transactionId") Long transactionId,
            @Param("adjustment") BigDecimal adjustment);

    /**
     * Delete the balance entry associated with a transaction.
     */
    @Modifying
    @Query("DELETE FROM AccountBalance ab WHERE ab.transaction.id = :transactionId")
    int deleteByTransactionId(@Param("transactionId") Long transactionId);

    /**
     * Find all balances for a specific account, ordered by timestamp.
     */
    List<AccountBalance> findByAccountOrderByTimestampAsc(Account account);

    /**
     * Find all balances for a specific account ID, ordered by timestamp.
     */
    @Query("SELECT ab FROM AccountBalance ab WHERE ab.account.id = :accountId ORDER BY ab.timestamp ASC")
    List<AccountBalance> findByAccountIdOrderByTimestampAsc(@Param("accountId") Long accountId);

    /**
     * Get the latest balance for an account.
     */
    @Query("SELECT ab FROM AccountBalance ab WHERE ab.account.id = :accountId ORDER BY ab.timestamp DESC LIMIT 1")
    Optional<AccountBalance> findLatestBalance(@Param("accountId") Long accountId);

    /**
     * Delete all balance entries for a specific account.
     */
    @Modifying
    @Query("DELETE FROM AccountBalance ab WHERE ab.account.id = :accountId")
    int deleteByAccountId(@Param("accountId") Long accountId);

    /**
     * Acquire a pessimistic write lock on the latest balance entry for an account.
     * This serializes concurrent balance operations for the same account,
     * preventing race conditions during statement upload processing.
     * 
     * If no balance exists for the account, returns empty Optional (caller must handle this case).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT ab FROM AccountBalance ab WHERE ab.account.id = :accountId ORDER BY ab.timestamp DESC LIMIT 1")
    Optional<AccountBalance> findLatestBalanceWithLock(@Param("accountId") Long accountId);

    /**
     * Find the balance entry immediately preceding a given timestamp with pessimistic lock.
     * Used to calculate the new balance when a transaction is created with concurrent access protection.
     * 
     * @deprecated Use findPrecedingBalanceByTimestampAndTransactionIdWithLock instead to handle same-timestamp transactions.
     */
    @Deprecated
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT ab FROM AccountBalance ab WHERE ab.account.id = :accountId AND ab.timestamp < :timestamp ORDER BY ab.timestamp DESC LIMIT 1")
    Optional<AccountBalance> findPrecedingBalanceWithLock(@Param("accountId") Long accountId, @Param("timestamp") LocalDateTime timestamp);

    /**
     * Find the balance entry immediately preceding a given transaction with pessimistic lock.
     * Uses both timestamp and transaction ID to properly order same-timestamp transactions.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT ab FROM AccountBalance ab WHERE ab.account.id = :accountId " +
           "AND (ab.timestamp < :timestamp OR (ab.timestamp = :timestamp AND ab.transaction.id < :transactionId)) " +
           "ORDER BY ab.timestamp DESC, ab.transaction.id DESC LIMIT 1")
    Optional<AccountBalance> findPrecedingBalanceByTimestampAndTransactionIdWithLock(
            @Param("accountId") Long accountId, 
            @Param("timestamp") LocalDateTime timestamp,
            @Param("transactionId") Long transactionId);
}
