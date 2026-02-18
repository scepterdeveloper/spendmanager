package com.everrich.spendmanager.repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.everrich.spendmanager.entities.AccountBalance;
import com.everrich.spendmanager.entities.Account;
import com.everrich.spendmanager.entities.Transaction;

@Repository
public interface AccountBalanceRepository extends JpaRepository<AccountBalance, Long> {

    /**
     * Find the balance entry immediately preceding a given timestamp for a specific account.
     * Used to calculate the new balance when a transaction is created.
     */
    @Query("SELECT ab FROM AccountBalance ab WHERE ab.account.id = :accountId AND ab.timestamp < :timestamp ORDER BY ab.timestamp DESC LIMIT 1")
    Optional<AccountBalance> findPrecedingBalance(@Param("accountId") Long accountId, @Param("timestamp") LocalDateTime timestamp);

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
     */
    @Modifying
    @Query("UPDATE AccountBalance ab SET ab.balance = ab.balance + :adjustment WHERE ab.account.id = :accountId AND ab.timestamp > :timestamp")
    int adjustSubsequentBalances(@Param("accountId") Long accountId, @Param("timestamp") LocalDateTime timestamp, @Param("adjustment") BigDecimal adjustment);

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
}