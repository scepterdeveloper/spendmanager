package com.everrich.spendmanager.repository;

import com.everrich.spendmanager.entities.VectorStoreTask;
import com.everrich.spendmanager.entities.VectorStoreTaskStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for VectorStoreTask entities.
 * This repository operates on the public schema table that stores
 * vector store initialization tasks for tenant registration.
 */
@Repository
public interface VectorStoreTaskRepository extends JpaRepository<VectorStoreTask, Long> {
    
    /**
     * Find all tasks with a specific status.
     * 
     * @param status The status to filter by
     * @return List of tasks with the given status
     */
    List<VectorStoreTask> findByStatus(VectorStoreTaskStatus status);
    
    /**
     * Find all pending tasks, ordered by creation time (oldest first).
     * This ensures FIFO processing of tasks.
     * 
     * @return List of pending tasks ordered by creation time
     */
    List<VectorStoreTask> findByStatusOrderByCreatedAtAsc(VectorStoreTaskStatus status);
    
    /**
     * Find a task by tenant ID.
     * 
     * @param tenantId The tenant/registration ID
     * @return Optional containing the task if found
     */
    Optional<VectorStoreTask> findByTenantId(String tenantId);
    
    /**
     * Check if a pending or processing task exists for the given tenant.
     * Used to prevent duplicate task creation.
     * 
     * @param tenantId The tenant/registration ID
     * @param statuses The statuses to check for
     * @return true if a task exists with one of the given statuses
     */
    boolean existsByTenantIdAndStatusIn(String tenantId, List<VectorStoreTaskStatus> statuses);
    
    /**
     * Find all tasks that are currently being processed.
     * Used on startup to detect and reset stuck tasks.
     * 
     * @return List of tasks in PROCESSING status
     */
    @Query("SELECT t FROM VectorStoreTask t WHERE t.status = 'PROCESSING'")
    List<VectorStoreTask> findProcessingTasks();
    
    /**
     * Find failed tasks that can be retried (retry count below threshold).
     * 
     * @param maxRetries Maximum number of retries allowed
     * @return List of failed tasks that can be retried
     */
    @Query("SELECT t FROM VectorStoreTask t WHERE t.status = 'FAILED' AND t.retryCount < :maxRetries ORDER BY t.createdAt ASC")
    List<VectorStoreTask> findFailedTasksForRetry(int maxRetries);
}