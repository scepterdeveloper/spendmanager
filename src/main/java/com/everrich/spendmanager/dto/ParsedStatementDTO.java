package com.everrich.spendmanager.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.everrich.spendmanager.entities.Transaction;

/**
 * DTO to hold the parsed statement response from the LLM.
 * Contains both statement-level metadata and the list of transactions.
 * All metadata fields are optional - the LLM will populate them when
 * the information is clearly available in the statement.
 */
public class ParsedStatementDTO {
    
    // Statement metadata - all optional
    private LocalDate periodStartDate;
    private LocalDate periodEndDate;
    private BigDecimal openingBalance;
    private BigDecimal closingBalance;
    private String description;
    
    // List of transactions extracted from the statement
    private List<Transaction> transactions;

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

    public List<Transaction> getTransactions() {
        return transactions;
    }

    public void setTransactions(List<Transaction> transactions) {
        this.transactions = transactions;
    }
}