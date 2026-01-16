package com.everrich.spendmanager.dto;

public class AggregatedInsight {
    private String name;
    private double cumulatedAmount;
    // Note: Percentage calculation will be done on the frontend for simplicity and real-time total updates.

    // Constructors
    public AggregatedInsight() {}
    
    public AggregatedInsight(String name, double cumulatedAmount) {
        this.name = name;
        this.cumulatedAmount = cumulatedAmount;
    }

    // Getters and Setters
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public double getCumulatedAmount() {
        return cumulatedAmount;
    }

    public void setCumulatedAmount(double cumulatedAmount) {
        this.cumulatedAmount = cumulatedAmount;
    }
}
