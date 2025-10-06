package com.everrich.spendmanager.repository;

import com.everrich.spendmanager.entities.Statement;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StatementRepository extends JpaRepository<Statement, Long> {
    // Spring automatically provides save(), findAll(), findById(), etc.
}