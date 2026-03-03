package com.everrich.spendmanager.entities;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Table;
import jakarta.persistence.Column;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Entity representing a vector store initialization task.
 * This table is stored in the public schema and used to queue vector store
 * update tasks for new tenants. Tasks are processed asynchronously by a 
 * scheduler to avoid blocking the registration process.
 * 
 * The task stores the tenant ID and tracks the status of the vector store
 * initialization process which includes creating embeddings for all default
 * transactions copied to the tenant's schema.
 */
@Getter
@Setter
@Entity
@Table(name = "VECTOR_STORE_TASK", schema = "public")
@NoArgsConstructor
public class VectorStoreTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    /**
     * The tenant/registration ID for which to initialize the vector store.
     * This maps to Registration.registrationId.
     */
    @Column(name = "tenant_id", nullable = false)
    private String tenantId;
    
    /**
     * Current status of the task.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private VectorStoreTaskStatus status;
    
    /**
     * Timestamp when the task was created (parked).
     */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
    
    /**
     * Timestamp when processing started.
     */
    @Column(name = "started_at")
    private LocalDateTime startedAt;
    
    /**
     * Timestamp when the task completed (success or failure).
     */
    @Column(name = "completed_at")
    private LocalDateTime completedAt;
    
    /**
     * Number of documents processed successfully.
     */
    @Column(name = "documents_processed")
    private Integer documentsProcessed;
    
    /**
     * Number of documents skipped (e.g., missing category).
     */
    @Column(name = "documents_skipped")
    private Integer documentsSkipped;
    
    /**
     * Error message if the task failed.
     */
    @Column(name = "error_message", length = 2000)
    private String errorMessage;
    
    /**
     * Number of retry attempts.
     */
    @Column(name = "retry_count")
    private Integer retryCount = 0;
    
    /**
     * Creates a new pending task for the given tenant.
     * 
     * @param tenantId The tenant ID
     * @return A new VectorStoreTask in PENDING status
     */
    public static VectorStoreTask createForTenant(String tenantId) {
        VectorStoreTask task = new VectorStoreTask();
        task.setTenantId(tenantId);
        task.setStatus(VectorStoreTaskStatus.PENDING);
        task.setCreatedAt(LocalDateTime.now());
        task.setRetryCount(0);
        return task;
    }
    
    /**
     * Marks the task as processing.
     */
    public void markAsProcessing() {
        this.status = VectorStoreTaskStatus.PROCESSING;
        this.startedAt = LocalDateTime.now();
    }
    
    /**
     * Marks the task as completed successfully.
     * 
     * @param processed Number of documents processed
     * @param skipped Number of documents skipped
     */
    public void markAsCompleted(int processed, int skipped) {
        this.status = VectorStoreTaskStatus.COMPLETED;
        this.completedAt = LocalDateTime.now();
        this.documentsProcessed = processed;
        this.documentsSkipped = skipped;
    }
    
    /**
     * Marks the task as failed.
     * 
     * @param errorMessage The error message
     */
    public void markAsFailed(String errorMessage) {
        this.status = VectorStoreTaskStatus.FAILED;
        this.completedAt = LocalDateTime.now();
        this.errorMessage = errorMessage;
        this.retryCount = (this.retryCount == null ? 0 : this.retryCount) + 1;
    }
    
    /**
     * Resets the task to pending status for retry.
     */
    public void resetForRetry() {
        this.status = VectorStoreTaskStatus.PENDING;
        this.startedAt = null;
        this.completedAt = null;
        this.errorMessage = null;
    }
}