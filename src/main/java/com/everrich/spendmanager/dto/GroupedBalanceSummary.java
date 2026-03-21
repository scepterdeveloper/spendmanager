package com.everrich.spendmanager.dto;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * DTO representing a group of account balance summaries.
 * Used in the Balances page to display accounts grouped by their AccountGroup.
 */
public class GroupedBalanceSummary {
    
    private Long groupId;
    private String groupName;
    private List<AccountBalanceSummary> accountSummaries;
    private BigDecimal totalStartingBalance;
    private BigDecimal totalClosingBalance;
    private BigDecimal totalNetChange;
    
    public GroupedBalanceSummary() {
        this.accountSummaries = new ArrayList<>();
        this.totalStartingBalance = BigDecimal.ZERO;
        this.totalClosingBalance = BigDecimal.ZERO;
        this.totalNetChange = BigDecimal.ZERO;
    }
    
    public GroupedBalanceSummary(Long groupId, String groupName) {
        this();
        this.groupId = groupId;
        this.groupName = groupName;
    }
    
    /**
     * Add an account summary to this group and update totals.
     */
    public void addAccountSummary(AccountBalanceSummary summary) {
        this.accountSummaries.add(summary);
        this.totalStartingBalance = this.totalStartingBalance.add(summary.getStartingBalance());
        this.totalClosingBalance = this.totalClosingBalance.add(summary.getClosingBalance());
        this.totalNetChange = this.totalClosingBalance.subtract(this.totalStartingBalance);
    }
    
    // Getters and Setters
    
    public Long getGroupId() {
        return groupId;
    }
    
    public void setGroupId(Long groupId) {
        this.groupId = groupId;
    }
    
    public String getGroupName() {
        return groupName;
    }
    
    public void setGroupName(String groupName) {
        this.groupName = groupName;
    }
    
    public List<AccountBalanceSummary> getAccountSummaries() {
        return accountSummaries;
    }
    
    public void setAccountSummaries(List<AccountBalanceSummary> accountSummaries) {
        this.accountSummaries = accountSummaries;
        recalculateTotals();
    }
    
    public BigDecimal getTotalStartingBalance() {
        return totalStartingBalance;
    }
    
    public void setTotalStartingBalance(BigDecimal totalStartingBalance) {
        this.totalStartingBalance = totalStartingBalance;
    }
    
    public BigDecimal getTotalClosingBalance() {
        return totalClosingBalance;
    }
    
    public void setTotalClosingBalance(BigDecimal totalClosingBalance) {
        this.totalClosingBalance = totalClosingBalance;
    }
    
    public BigDecimal getTotalNetChange() {
        return totalNetChange;
    }
    
    public void setTotalNetChange(BigDecimal totalNetChange) {
        this.totalNetChange = totalNetChange;
    }
    
    private void recalculateTotals() {
        this.totalStartingBalance = BigDecimal.ZERO;
        this.totalClosingBalance = BigDecimal.ZERO;
        for (AccountBalanceSummary summary : accountSummaries) {
            this.totalStartingBalance = this.totalStartingBalance.add(summary.getStartingBalance());
            this.totalClosingBalance = this.totalClosingBalance.add(summary.getClosingBalance());
        }
        this.totalNetChange = this.totalClosingBalance.subtract(this.totalStartingBalance);
    }
}