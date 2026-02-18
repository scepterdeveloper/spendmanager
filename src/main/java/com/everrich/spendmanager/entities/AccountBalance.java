package com.everrich.spendmanager.entities;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Represents the balance of an account at a specific point in time.
 * Each AccountBalance entry is linked to a transaction and represents
 * the account balance immediately after that transaction was applied.
 */
@Getter
@Setter
@Entity
@Table(name = "ACCOUNT_BALANCE", indexes = {
    @Index(name = "idx_account_balance_account_timestamp", columnList = "account_id, timestamp"),
    @Index(name = "idx_account_balance_transaction", columnList = "transaction_id")
})
@NoArgsConstructor
public class AccountBalance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    /**
     * The timestamp of the associated transaction.
     * Used for ordering balances chronologically.
     */
    @Column(nullable = false)
    private LocalDateTime timestamp;

    /**
     * The balance of the account after the associated transaction was applied.
     * Using BigDecimal for precise monetary calculations.
     */
    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal balance;

    /**
     * The transaction that caused this balance entry.
     * One-to-one relationship ensures each transaction has exactly one balance snapshot.
     */
    @OneToOne
    @JoinColumn(name = "transaction_id", nullable = false, unique = true)
    private Transaction transaction;

    public AccountBalance(Account account, LocalDateTime timestamp, BigDecimal balance, Transaction transaction) {
        this.account = account;
        this.timestamp = timestamp;
        this.balance = balance;
        this.transaction = transaction;
    }
}