package com.everrich.spendmanager.repository;

import com.everrich.spendmanager.entities.SavedInsight;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SavedInsightRepository extends JpaRepository<SavedInsight, Long> {
    
    SavedInsight findByNameIgnoreCase(String name);
    
    // Order by displaySequence (nulls treated as 0, so they come first), then by id for stability
    @Query("SELECT s FROM SavedInsight s ORDER BY COALESCE(s.displaySequence, 0) ASC, s.id ASC")
    List<SavedInsight> findAllOrderByDisplaySequence();
    
    // Legacy method - kept for backwards compatibility
    List<SavedInsight> findAllByOrderByNameAsc();
    
    List<SavedInsight> findByShowOnDashboardTrue();
    
    // Dashboard KPIs ordered by display sequence (insightType = 'KPI')
    @Query("SELECT s FROM SavedInsight s WHERE s.showOnDashboard = true AND s.insightType = 'KPI' ORDER BY COALESCE(s.displaySequence, 0) ASC, s.id ASC")
    List<SavedInsight> findDashboardKPIsOrderByDisplaySequence();
    
    // Dashboard Charts ordered by display sequence (insightType = 'CHART')
    @Query("SELECT s FROM SavedInsight s WHERE s.showOnDashboard = true AND s.insightType = 'CHART' ORDER BY COALESCE(s.displaySequence, 0) ASC, s.id ASC")
    List<SavedInsight> findDashboardChartsOrderByDisplaySequence();
    
    // Find insights by type
    List<SavedInsight> findByShowOnDashboardTrueAndInsightType(String insightType);
    
    // Find the maximum display sequence value
    @Query("SELECT MAX(s.displaySequence) FROM SavedInsight s")
    Optional<Integer> findMaxDisplaySequence();
}
