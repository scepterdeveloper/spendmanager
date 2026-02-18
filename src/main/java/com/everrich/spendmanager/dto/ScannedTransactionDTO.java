package com.everrich.spendmanager.dto;

import java.time.LocalDate;

/**
 * DTO representing a transaction extracted from a receipt image by the AI.
 * Contains only the fields that can be reliably extracted from a photo.
 */
public class ScannedTransactionDTO {

    private LocalDate date;
    private String description;
    private double amount;
    private String operation; // "PLUS" or "MINUS"

    public ScannedTransactionDTO() {
    }

    public ScannedTransactionDTO(LocalDate date, String description, double amount, String operation) {
        this.date = date;
        this.description = description;
        this.amount = amount;
        this.operation = operation;
    }

    public LocalDate getDate() {
        return date;
    }

    public void setDate(LocalDate date) {
        this.date = date;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public double getAmount() {
        return amount;
    }

    public void setAmount(double amount) {
        this.amount = amount;
    }

    public String getOperation() {
        return operation;
    }

    public void setOperation(String operation) {
        this.operation = operation;
    }

    @Override
    public String toString() {
        return "ScannedTransactionDTO{" +
                "date=" + date +
                ", description='" + description + '\'' +
                ", amount=" + amount +
                ", operation='" + operation + '\'' +
                '}';
    }
}