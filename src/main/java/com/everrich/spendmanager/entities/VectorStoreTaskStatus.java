package com.everrich.spendmanager.entities;

/**
 * Status enum for vector store initialization tasks.
 * These tasks are created during tenant registration and processed asynchronously
 * by a scheduler to avoid blocking the registration process.
 */
public enum VectorStoreTaskStatus {
    /**
     * Task is pending and waiting to be picked up by the scheduler.
     */
    PENDING,
    
    /**
     * Task is currently being processed by the scheduler.
     */
    PROCESSING,
    
    /**
     * Task completed successfully - all vector embeddings were created.
     */
    COMPLETED,
    
    /**
     * Task failed and needs investigation or retry.
     */
    FAILED
}