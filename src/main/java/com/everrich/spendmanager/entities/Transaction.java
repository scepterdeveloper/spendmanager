package com.everrich.spendmanager.entities;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Transient;

@Entity
public class Transaction {

    @Id
    @GeneratedValue (strategy = GenerationType.IDENTITY)    
    private Long id; 
    
    // This field holds the ID of the linked statement (if it exists).
    // For a manual transaction, this field will be null in the database.
    private Long statementId; 
    
    @DateTimeFormat(pattern = "yyyy-MM-dd")
    private LocalDate date;
    private String description;
    private double amount;
    
    @ManyToOne 
    @JoinColumn(name = "category_id")
    private Category categoryEntity;
    
    @Transient
    private String category;     
    
    // Default (No-Argument) Constructor is required by JPA/Hibernate
    public Transaction() {
    }

    // 🟢 HELPER METHOD: Check if the transaction was created manually
    public boolean isManual() {
        return this.statementId == null;
    }

    // --- Getters and Setters ---

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getStatementId() {
        return statementId;
    }

    // Note: If you have a separate Statement entity, you might need to load it here
    // or use a @ManyToOne relationship with @JoinColumn(name = "statement_id", nullable = true)
    // but based on this code, we assume statementId is the direct ID.
    public void setStatementId(Long statementId) {
        this.statementId = statementId;
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

    public Category getCategoryEntity() {
        return categoryEntity;
    }

    public void setCategoryEntity(Category categoryEntity) {
        this.categoryEntity = categoryEntity;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }
}