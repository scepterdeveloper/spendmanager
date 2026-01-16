package com.everrich.spendmanager.repository;

import com.everrich.spendmanager.entities.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    @Query(
        value = "SELECT t.* " + // SELECT ONLY FROM THE TRANSACTION TABLE
                "FROM transaction t " +
                "LEFT JOIN category c ON c.id = t.category_id " + // Keep the JOIN for filtering purposes
                "WHERE " +
                "(:startDate = '1900-01-01' OR t.date >= CAST(:startDate AS date)) AND " +
                "(:endDate = '9999-12-31' OR t.date <= CAST(:endDate AS date)) AND " +
                "(CAST(:categoryIds AS text) IS NULL OR c.id IN (:categoryIds)) AND " +
                "(:query IS NULL OR " +
                "  LOWER(t.description::text) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
                "  LOWER(c.name) LIKE LOWER(CONCAT('%', :query, '%'))" +
                ") " +
                "ORDER BY t.date DESC",
        nativeQuery = true)
    List<Transaction> findFiltered(
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate,
            @Param("categoryIds") List<Long> categoryIds,
            @Param("query") String query);

    //-------------------------------------------------------------------------
    
    /**
     * Custom query to fetch all transactions within a date range and specific categories.
     * This query remains in JPQL.
     */
    @Query("SELECT t FROM Transaction t WHERE t.date BETWEEN :startDate AND :endDate AND t.categoryEntity.id IN :categoryIds")
    List<Transaction> findByDateRangeAndCategories(
        @Param("startDate") LocalDate startDate,
        @Param("endDate") LocalDate endDate,
        @Param("categoryIds") List<Long> categoryIds);

    // Custom query to fetch all transactions within a date range (no category filtering)
    @Query("SELECT t FROM Transaction t WHERE t.date BETWEEN :startDate AND :endDate")
    List<Transaction> findByDateRange(
        @Param("startDate") LocalDate startDate,
        @Param("endDate") LocalDate endDate);

    // Helper methods for the "Entire Timeframe" bounds
    @Query("SELECT MIN(t.date) FROM Transaction t")
    Optional<LocalDate> findMinDate();

    @Query("SELECT MAX(t.date) FROM Transaction t")
    Optional<LocalDate> findMaxDate();
    
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
