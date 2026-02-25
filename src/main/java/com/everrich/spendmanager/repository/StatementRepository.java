package com.everrich.spendmanager.repository;

import com.everrich.spendmanager.entities.Account;
import com.everrich.spendmanager.entities.Statement;
import com.everrich.spendmanager.entities.StatementStatus;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StatementRepository extends JpaRepository<Statement, Long> {
    // Spring automatically provides save(), findAll(), findById(), etc.

    List<Statement> findByStatus(StatementStatus status);
    
    List<Statement> findByAccount(Account account);
    
    /**
     * Finds statements that overlap with the given date range.
     * A statement overlaps if:
     * - Its period (start to end) intersects with the filter range
     * - Uses COALESCE to handle null dates:
     *   - If periodStartDate is null, treat as beginning of time (1970-01-01)
     *   - If periodEndDate is null, treat as end of time (9999-12-31)
     */
    @Query("SELECT s FROM Statement s WHERE " +
           "COALESCE(s.periodStartDate, '1970-01-01') <= :filterEndDate AND " +
           "COALESCE(s.periodEndDate, '9999-12-31') >= :filterStartDate " +
           "ORDER BY s.uploadDateTime DESC")
    List<Statement> findByPeriodOverlapping(
            @Param("filterStartDate") LocalDate filterStartDate,
            @Param("filterEndDate") LocalDate filterEndDate);
    
    /**
     * Finds statements for a specific account that overlap with the given date range.
     */
    @Query("SELECT s FROM Statement s WHERE " +
           "s.account = :account AND " +
           "COALESCE(s.periodStartDate, '1970-01-01') <= :filterEndDate AND " +
           "COALESCE(s.periodEndDate, '9999-12-31') >= :filterStartDate " +
           "ORDER BY s.uploadDateTime DESC")
    List<Statement> findByAccountAndPeriodOverlapping(
            @Param("account") Account account,
            @Param("filterStartDate") LocalDate filterStartDate,
            @Param("filterEndDate") LocalDate filterEndDate);
    
    /**
     * Finds all statements ordered by upload date descending.
     */
    List<Statement> findAllByOrderByUploadDateTimeDesc();
    
    /**
     * Finds statements by account ordered by upload date descending.
     */
    List<Statement> findByAccountOrderByUploadDateTimeDesc(Account account);
}
