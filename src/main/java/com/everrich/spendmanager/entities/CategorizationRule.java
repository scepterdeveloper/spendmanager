package com.everrich.spendmanager.entities;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Entity representing a categorization rule learned from user's manual categorizations.
 * These rules are used as guidance for the LLM when categorizing new transactions.
 * 
 * Rules are account-specific and operation-specific, allowing different categories
 * for the same merchant depending on whether it's a credit (PLUS) or debit (MINUS).
 */
@Entity
@Table(name = "categorization_rule")
public class CategorizationRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The account this rule applies to.
     * Rules are account-specific to handle cases where the same merchant
     * might be categorized differently across different accounts.
     */
    @ManyToOne
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    /**
     * The operation type (PLUS or MINUS) this rule applies to.
     * Same merchant can have different categories for credits vs debits.
     * Example: PayPal MINUS -> "PayPal Purchases", PayPal PLUS -> "PayPal Refunds"
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionOperation operation;

    /**
     * The category to apply when this rule matches.
     */
    @ManyToOne
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    /**
     * LLM-extracted keywords from the original transaction description.
     * These are the meaningful parts of the description used for matching.
     * Stored as comma-separated values.
     * Example: "PayPal Europe S.a.r.l. et Cie S.C.A, Ihr Einkauf bei, SEPA-BASISLASTSCHRIFT"
     */
    @Column(name = "keywords", columnDefinition = "TEXT", nullable = false)
    private String keywords;

    /**
     * The original full transaction description from which keywords were extracted.
     * Stored for reference and debugging purposes.
     */
    @Column(name = "original_description", columnDefinition = "TEXT")
    private String originalDescription;

    /**
     * Timestamp when this rule was created.
     */
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    /**
     * Timestamp when this rule was last updated.
     * Set when the category is changed for an existing rule.
     */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // Default constructor required by JPA
    public CategorizationRule() {
    }

    // Getters and Setters

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Account getAccount() {
        return account;
    }

    public void setAccount(Account account) {
        this.account = account;
    }

    public TransactionOperation getOperation() {
        return operation;
    }

    public void setOperation(TransactionOperation operation) {
        this.operation = operation;
    }

    public Category getCategory() {
        return category;
    }

    public void setCategory(Category category) {
        this.category = category;
    }

    public String getKeywords() {
        return keywords;
    }

    public void setKeywords(String keywords) {
        this.keywords = keywords;
    }

    public String getOriginalDescription() {
        return originalDescription;
    }

    public void setOriginalDescription(String originalDescription) {
        this.originalDescription = originalDescription;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    @Override
    public String toString() {
        return "CategorizationRule{" +
                "id=" + id +
                ", account=" + (account != null ? account.getName() : "null") +
                ", operation=" + operation +
                ", category=" + (category != null ? category.getName() : "null") +
                ", keywords='" + keywords + '\'' +
                '}';
    }
}