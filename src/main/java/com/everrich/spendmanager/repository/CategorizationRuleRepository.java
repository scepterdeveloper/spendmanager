package com.everrich.spendmanager.repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.everrich.spendmanager.entities.CategorizationRule;
import com.everrich.spendmanager.entities.TransactionOperation;

/**
 * Repository for CategorizationRule entity.
 * Provides methods for querying categorization rules used in table-based categorization.
 */
@Repository
public interface CategorizationRuleRepository extends JpaRepository<CategorizationRule, Long> {

    /**
     * Find all rules for a specific account.
     * Used when categorizing transactions from a single account.
     * 
     * @param accountId The account ID
     * @return List of rules for the account
     */
    List<CategorizationRule> findByAccountId(Long accountId);

    /**
     * Find all rules for multiple accounts.
     * Used when categorizing a batch of transactions from different accounts.
     * 
     * @param accountIds Set of account IDs
     * @return List of rules for all specified accounts
     */
    List<CategorizationRule> findByAccountIdIn(Set<Long> accountIds);

    /**
     * Find a rule with exact match on account, operation, and keywords.
     * Used for deduplication when creating/updating rules.
     * 
     * @param accountId The account ID
     * @param operation The operation type (PLUS/MINUS)
     * @param keywords The extracted keywords
     * @return Optional containing the rule if found
     */
    Optional<CategorizationRule> findByAccountIdAndOperationAndKeywords(
            Long accountId, 
            TransactionOperation operation, 
            String keywords);

    /**
     * Find all rules with pagination for the management UI.
     * 
     * @param pageable Pagination parameters
     * @return Page of rules
     */
    Page<CategorizationRule> findAll(Pageable pageable);

    /**
     * Find rules by account with pagination.
     * 
     * @param accountId The account ID
     * @param pageable Pagination parameters
     * @return Page of rules for the account
     */
    Page<CategorizationRule> findByAccountId(Long accountId, Pageable pageable);

    /**
     * Find rules by operation type with pagination.
     * 
     * @param operation The operation type
     * @param pageable Pagination parameters
     * @return Page of rules for the operation type
     */
    Page<CategorizationRule> findByOperation(TransactionOperation operation, Pageable pageable);

    /**
     * Find rules by account and operation with pagination.
     * 
     * @param accountId The account ID
     * @param operation The operation type
     * @param pageable Pagination parameters
     * @return Page of rules matching both criteria
     */
    Page<CategorizationRule> findByAccountIdAndOperation(
            Long accountId, 
            TransactionOperation operation, 
            Pageable pageable);

    /**
     * Find rules by category with pagination.
     * 
     * @param categoryId The category ID
     * @param pageable Pagination parameters
     * @return Page of rules for the category
     */
    Page<CategorizationRule> findByCategoryId(Long categoryId, Pageable pageable);

    /**
     * Search rules by keywords (case-insensitive partial match).
     * Used for the search functionality in the management UI.
     * 
     * @param searchTerm The search term
     * @param pageable Pagination parameters
     * @return Page of rules matching the search term
     */
    @Query("SELECT r FROM CategorizationRule r WHERE LOWER(r.keywords) LIKE LOWER(CONCAT('%', :searchTerm, '%'))")
    Page<CategorizationRule> searchByKeywords(@Param("searchTerm") String searchTerm, Pageable pageable);

    /**
     * Search rules with filters for account, operation, category, and search term.
     * Used for advanced filtering in the management UI.
     * 
     * @param accountId Optional account ID filter (null for all accounts)
     * @param operation Optional operation filter (null for all operations)
     * @param categoryId Optional category ID filter (null for all categories)
     * @param searchTerm Optional search term for keywords (null or empty for no search)
     * @param pageable Pagination parameters
     * @return Page of rules matching all specified criteria
     */
    @Query("SELECT r FROM CategorizationRule r WHERE " +
           "(:accountId IS NULL OR r.account.id = :accountId) AND " +
           "(:operation IS NULL OR r.operation = :operation) AND " +
           "(:categoryId IS NULL OR r.category.id = :categoryId) AND " +
           "(:searchTerm IS NULL OR :searchTerm = '' OR LOWER(r.keywords) LIKE LOWER(CONCAT('%', :searchTerm, '%')))")
    Page<CategorizationRule> findWithFilters(
            @Param("accountId") Long accountId,
            @Param("operation") TransactionOperation operation,
            @Param("categoryId") Long categoryId,
            @Param("searchTerm") String searchTerm,
            Pageable pageable);

    /**
     * Count total rules for a tenant.
     * Used for statistics/dashboard.
     * 
     * @return Total number of rules
     */
    long count();

    /**
     * Count rules by account.
     * 
     * @param accountId The account ID
     * @return Number of rules for the account
     */
    long countByAccountId(Long accountId);

    /**
     * Delete all rules for a specific account.
     * Used when an account is deleted.
     * 
     * @param accountId The account ID
     */
    void deleteByAccountId(Long accountId);

    /**
     * Delete all rules for a specific category.
     * Used when a category is deleted.
     * 
     * @param categoryId The category ID
     */
    void deleteByCategoryId(Long categoryId);
}