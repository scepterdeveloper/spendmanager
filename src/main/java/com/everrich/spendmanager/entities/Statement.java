package com.everrich.spendmanager.entities;

import java.time.LocalDateTime;

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
}
