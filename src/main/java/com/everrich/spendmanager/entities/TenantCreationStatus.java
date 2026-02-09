package com.everrich.spendmanager.entities;

/**
 * Status of tenant schema creation during registration process.
 * Used to track the asynchronous schema creation workflow.
 */
public enum TenantCreationStatus {
    /**
     * Schema creation has been initiated but not yet completed.
     * User should not be able to login while in this state.
     */
    INITIATED,
    
    /**
     * Schema has been created and all data has been copied successfully.
     * User can now login and access their tenant-specific data.
     */
    COMPLETED,
    
    /**
     * Schema creation failed. Requires manual intervention or retry.
     */
    FAILED
}