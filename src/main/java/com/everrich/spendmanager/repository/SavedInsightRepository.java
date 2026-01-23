package com.everrich.spendmanager.repository;

import com.everrich.spendmanager.entities.SavedInsight;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SavedInsightRepository extends JpaRepository<SavedInsight, Long> {
    
    SavedInsight findByNameIgnoreCase(String name);
    
    List<SavedInsight> findAllByOrderByNameAsc();
}
