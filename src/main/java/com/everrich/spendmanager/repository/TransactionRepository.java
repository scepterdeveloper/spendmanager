package com.everrich.spendmanager.repository;

import com.everrich.spendmanager.entities.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    @Query(
        value = "SELECT t.* " + // SELECT ONLY FROM THE TRANSACTION TABLE
                "FROM transaction t " +
                "LEFT JOIN category c ON c.id = t.category_id " + // Keep the JOIN for filtering purposes
                "WHERE " +
                "(:startDate = '1900-01-01 00:00:00' OR t.date >= CAST(:startDate AS timestamp)) AND " +
                "(:endDate = '9999-12-31 23:59:59' OR t.date <= CAST(:endDate AS timestamp)) AND " +
                "(CAST(:accountIds AS text) IS NULL OR t.account_id IN (:accountIds)) AND " +
                "(CAST(:categoryIds AS text) IS NULL OR c.id IN (:categoryIds)) AND " +
                "(:query IS NULL OR " +
                "  LOWER(t.description::text) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
                "  LOWER(c.name) LIKE LOWER(CONCAT('%', :query, '%'))" +
                ") " +
                "ORDER BY t.date DESC",
        nativeQuery = true)
    List<Transaction> findFiltered(
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate,
            @Param("accountIds") List<Long> accountIds,
            @Param("categoryIds") List<Long> categoryIds,
            @Param("query") String query);

    //-------------------------------------------------------------------------
    
    /**
     * Custom query to fetch all transactions within a date range and specific categories.
     * This query remains in JPQL.
     */
    @Query("SELECT t FROM Transaction t WHERE t.date BETWEEN :startDate AND :endDate AND t.categoryEntity.id IN :categoryIds")
    List<Transaction> findByDateRangeAndCategories(
        @Param("startDate") LocalDateTime startDate,
        @Param("endDate") LocalDateTime endDate,
        @Param("categoryIds") List<Long> categoryIds);

    // Custom query to fetch all transactions within a date range (no category filtering)
    @Query("SELECT t FROM Transaction t WHERE t.date BETWEEN :startDate AND :endDate")
    List<Transaction> findByDateRange(
        @Param("startDate") LocalDateTime startDate,
        @Param("endDate") LocalDateTime endDate);

    // Helper methods for the "Entire Timeframe" bounds
    @Query("SELECT MIN(t.date) FROM Transaction t")
    Optional<LocalDateTime> findMinDate();

    @Query("SELECT MAX(t.date) FROM Transaction t")
    Optional<LocalDateTime> findMaxDate();
    
    List<Transaction> findByStatementId(Long statementId);

    // ⭐ NEW RAG DEPENDENCY METHOD: Fetches all transactions that have a category assigned (Category is NOT NULL)
    /**
     * Used by VectorStoreInitializer to find all historical examples for RAG training.
     * Assumes the Category entity reference field in Transaction is named 'category'. 
     * If the field is 'categoryEntity', the method name should be findByCategoryEntityIsNotNull().
     * Sticking to the shorter version based on standard conventions.
     */
    List<Transaction> findByCategoryEntityIsNotNull();
}
