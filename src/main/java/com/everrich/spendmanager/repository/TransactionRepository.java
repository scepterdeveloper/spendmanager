package com.everrich.spendmanager.repository;

import com.everrich.spendmanager.entities.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    @Query("SELECT t FROM Transaction t " +
           "LEFT JOIN FETCH t.categoryEntity c " +
           "WHERE " +
           // 🟢 FIX 1: Ignore StartDate if it is LocalDate.MIN (used for Entire Timeframe / Missing Start Date)
           "(:startDate = com.everrich.spendmanager.service.DateUtils.MIN_DATE OR t.date >= :startDate) AND " + 
           // 🟢 FIX 2: Ignore EndDate if it is LocalDate.MAX (used for Entire Timeframe / Missing End Date)
           "(:endDate = com.everrich.spendmanager.service.DateUtils.MAX_DATE OR t.date <= :endDate) AND " + 
           
           "(:categoryIds IS NULL OR t.categoryEntity.id IN :categoryIds) AND " +
           
           // Full-Text Search Logic (retained and confirmed correct)
           "(:query IS NULL OR " +
           "  LOWER(t.description) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "  LOWER(c.name) LIKE LOWER(CONCAT('%', :query, '%'))" +
           ") " +
           "ORDER BY t.date DESC")
    List<Transaction> findFiltered(
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate,
            @Param("categoryIds") List<Long> categoryIds,
            @Param("query") String query); 
    
    List<Transaction> findByStatementId(Long statementId);
}