package com.everrich.spendmanager.dto;

public class CategoryInsight {
    private String categoryName;
    private double cumulatedAmount;
    // Note: Percentage calculation will be done on the frontend for simplicity and real-time total updates.

    // Constructors
    public CategoryInsight() {}
    
    public CategoryInsight(String categoryName, double cumulatedAmount) {
        this.categoryName = categoryName;
        this.cumulatedAmount = cumulatedAmount;
    }

    // Getters and Setters
    public String getCategoryName() {
        return categoryName;
    }

    public void setCategoryName(String categoryName) {
        this.categoryName = categoryName;
    }

    public double getCumulatedAmount() {
        return cumulatedAmount;
    }

    public void setCumulatedAmount(double cumulatedAmount) {
        this.cumulatedAmount = cumulatedAmount;
    }
}