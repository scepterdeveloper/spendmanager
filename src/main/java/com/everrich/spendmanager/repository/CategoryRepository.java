package com.everrich.spendmanager.repository;

import com.everrich.spendmanager.entities.Category;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryRepository extends JpaRepository<Category, Long> {
    // Custom finder for convenience:
    Category findByNameIgnoreCase(String name);
}