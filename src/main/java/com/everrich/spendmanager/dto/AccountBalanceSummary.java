package com.everrich.spendmanager.dto;

import java.math.BigDecimal;

/**
 * DTO representing the balance summary for an account.
 * Used in the Balances page to display account balances.
 */
public class AccountBalanceSummary {
    
    private Long accountId;
    private String accountName;
    private BigDecimal startingBalance;
    private BigDecimal closingBalance;
    private BigDecimal netChange;
    
    public AccountBalanceSummary() {
    }
    
    public AccountBalanceSummary(Long accountId, String accountName, BigDecimal startingBalance, BigDecimal closingBalance) {
        this.accountId = accountId;
        this.accountName = accountName;
        this.startingBalance = startingBalance;
        this.closingBalance = closingBalance;
        this.netChange = closingBalance.subtract(startingBalance);
    }
    
    // Getters and Setters
    
    public Long getAccountId() {
        return accountId;
    }
    
    public void setAccountId(Long accountId) {
        this.accountId = accountId;
    }
    
    public String getAccountName() {
        return accountName;
    }
    
    public void setAccountName(String accountName) {
        this.accountName = accountName;
    }
    
    public BigDecimal getStartingBalance() {
        return startingBalance;
    }
    
    public void setStartingBalance(BigDecimal startingBalance) {
        this.startingBalance = startingBalance;
        updateNetChange();
    }
    
    public BigDecimal getClosingBalance() {
        return closingBalance;
    }
    
    public void setClosingBalance(BigDecimal closingBalance) {
        this.closingBalance = closingBalance;
        updateNetChange();
    }
    
    public BigDecimal getNetChange() {
        return netChange;
    }
    
    public void setNetChange(BigDecimal netChange) {
        this.netChange = netChange;
    }
    
    private void updateNetChange() {
        if (startingBalance != null && closingBalance != null) {
            this.netChange = closingBalance.subtract(startingBalance);
        }
    }
}