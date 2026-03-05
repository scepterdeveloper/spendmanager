package com.everrich.spendmanager.entities;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.JoinColumn;

@Entity
public class Statement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY) // 1. Use IDENTITY strategy for auto-increment in H2
    private Long id; // 2. Change type from String to Long
    private String originalFileName;
    private LocalDateTime uploadDateTime;
    private StatementStatus status; // Enum: UPLOADING, PROCESSING, COMPLETED, FAILED
    private byte[] content;
    
    // Optional statement metadata fields - derived from LLM parsing when available
    private LocalDate periodStartDate;
    private LocalDate periodEndDate;
    private BigDecimal openingBalance;
    private BigDecimal closingBalance;
    @Column(length = 500)
    private String description;
    
    // LLM Categorization timing fields
    private LocalDateTime llmCategorizationStart;
    private LocalDateTime llmCategorizationEnd;

    public byte[] getContent() {
        return content;
    }
    public void setContent(byte[] content) {
        this.content = content;
    }

    @ManyToOne
    @JoinColumn(name = "account_id")
    private Account account;
    
    public Long getId() {
        return id;
    }
    public String getOriginalFileName() {
        return originalFileName;
    }
    public void setId(Long id) {
        this.id = id;
    }
    public void setOriginalFileName(String originalFileName) {
        this.originalFileName = originalFileName;
    }
    public void setUploadDateTime(LocalDateTime uploadDateTime) {
        this.uploadDateTime = uploadDateTime;
    }
    public void setStatus(StatementStatus status) {
        this.status = status;
    }
    public LocalDateTime getUploadDateTime() {
        return uploadDateTime;
    }
    public StatementStatus getStatus() {
        return status;
    }

    public Account getAccount() {
        return account;
    }

    public void setAccount(Account account) {
        this.account = account;
    }

    public LocalDate getPeriodStartDate() {
        return periodStartDate;
    }

    public void setPeriodStartDate(LocalDate periodStartDate) {
        this.periodStartDate = periodStartDate;
    }

    public LocalDate getPeriodEndDate() {
        return periodEndDate;
    }

    public void setPeriodEndDate(LocalDate periodEndDate) {
        this.periodEndDate = periodEndDate;
    }

    public BigDecimal getOpeningBalance() {
        return openingBalance;
    }

    public void setOpeningBalance(BigDecimal openingBalance) {
        this.openingBalance = openingBalance;
    }

    public BigDecimal getClosingBalance() {
        return closingBalance;
    }

    public void setClosingBalance(BigDecimal closingBalance) {
        this.closingBalance = closingBalance;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public LocalDateTime getLlmCategorizationStart() {
        return llmCategorizationStart;
    }

    public void setLlmCategorizationStart(LocalDateTime llmCategorizationStart) {
        this.llmCategorizationStart = llmCategorizationStart;
    }

    public LocalDateTime getLlmCategorizationEnd() {
        return llmCategorizationEnd;
    }

    public void setLlmCategorizationEnd(LocalDateTime llmCategorizationEnd) {
        this.llmCategorizationEnd = llmCategorizationEnd;
    }
}
