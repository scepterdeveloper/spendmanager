package com.everrich.spendmanager.service;

import com.everrich.spendmanager.dto.InsightExecutionResult;
import com.everrich.spendmanager.entities.SavedInsight;
import com.everrich.spendmanager.repository.SavedInsightRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class SavedInsightService {

    private final SavedInsightRepository savedInsightRepository;
    private final InsightsService insightsService;

    public SavedInsightService(SavedInsightRepository savedInsightRepository, InsightsService insightsService) {
        this.savedInsightRepository = savedInsightRepository;
        this.insightsService = insightsService;
    }

    /**
     * Returns all insights ordered by display sequence (nulls treated as 0).
     */
    public List<SavedInsight> findAll() {
        return savedInsightRepository.findAllOrderByDisplaySequence();
    }

    public List<SavedInsight> findInsightsForDashboard() {
        return savedInsightRepository.findByShowOnDashboardTrue();
    }

    public Optional<SavedInsight> findById(Long id) {
        return savedInsightRepository.findById(id);
    }

    /**
     * Saves a new or updated insight. For new insights, assigns the next display sequence value.
     * For existing insights, preserves the original display sequence if not provided.
     * Ensures insightType is never null - defaults to CHART if not set.
     */
    public SavedInsight save(SavedInsight savedInsight) {
        // For new insights (no ID), assign the next display sequence
        if (savedInsight.getId() == null) {
            if (savedInsight.getDisplaySequence() == null) {
                Integer maxSeq = savedInsightRepository.findMaxDisplaySequence().orElse(0);
                savedInsight.setDisplaySequence(maxSeq + 1);
            }
        } else {
            // For existing insights, preserve the original displaySequence if not provided
            if (savedInsight.getDisplaySequence() == null) {
                savedInsightRepository.findById(savedInsight.getId())
                    .ifPresent(existing -> savedInsight.setDisplaySequence(existing.getDisplaySequence()));
            }
        }
        
        // Ensure insightType is never null - default to CHART
        if (savedInsight.getInsightType() == null || savedInsight.getInsightType().isBlank()) {
            savedInsight.setInsightType(SavedInsight.TYPE_CHART);
        }
        
        return savedInsightRepository.save(savedInsight);
    }

    public void deleteById(Long id) {
        savedInsightRepository.deleteById(id);
    }

    public SavedInsight findByName(String name) {
        return savedInsightRepository.findByNameIgnoreCase(name);
    }

    /**
     * Returns dashboard KPIs ordered by display sequence.
     */
    public List<InsightExecutionResult> getDashBoardKPIs() {
        List<InsightExecutionResult> results = new ArrayList<>();
        List<SavedInsight> dashBoardKPIInsights = savedInsightRepository.findDashboardKPIsOrderByDisplaySequence();
        for(SavedInsight dashBoardKPIInsight: dashBoardKPIInsights) {
            results.add(this.execute(dashBoardKPIInsight.getId()));
        }
        return results;
    }

    /**
     * Returns dashboard charts ordered by display sequence.
     */
    public List<InsightExecutionResult> getDashBoardCharts() {
        List<InsightExecutionResult> results = new ArrayList<>();
        List<SavedInsight> dashBoardChartInsights = savedInsightRepository.findDashboardChartsOrderByDisplaySequence();
        for(SavedInsight dashboardChartInsight: dashBoardChartInsights) {
            results.add(this.execute(dashboardChartInsight.getId()));
        }
        return results;
    }

    /**
     * Reorders insights based on the provided list of IDs.
     * Assigns sequential display sequence values (1, 2, 3, ...) based on the order of IDs.
     * 
     * @param orderedIds List of insight IDs in the desired order
     */
    @Transactional
    public void reorderInsights(List<Long> orderedIds) {
        for (int i = 0; i < orderedIds.size(); i++) {
            Long id = orderedIds.get(i);
            Optional<SavedInsight> insightOpt = savedInsightRepository.findById(id);
            if (insightOpt.isPresent()) {
                SavedInsight insight = insightOpt.get();
                insight.setDisplaySequence(i + 1);
                savedInsightRepository.save(insight);
            }
        }
    }

    /**
     * REFACTORED: Execute a saved insight using the harmonized execution method.
     * This now delegates to InsightsService.executeAdHocInsight() after extracting
     * the saved parameters, ensuring consistent behavior between saved and ad-hoc insights.
     */
    public InsightExecutionResult execute(Long insightId) {
        SavedInsight insight = savedInsightRepository.findById(insightId)
                .orElseThrow(() -> new IllegalArgumentException("Insight not found with ID: " + insightId));

        // Parse category IDs from comma-separated string
        List<Long> categoryIds = Collections.emptyList();
        if (insight.getCategoryIds() != null && !insight.getCategoryIds().trim().isEmpty()) {
            categoryIds = Arrays.stream(insight.getCategoryIds().split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(Long::parseLong)
                    .collect(Collectors.toList());
        }

        // Determine interval (map intervalType to interval parameter)
        String interval = "NOT_SPECIFIED".equals(insight.getIntervalType()) ? null : insight.getIntervalType();
        String intervalFunction = insight.getIntervalFunction();

        // Determine if this is an aggregated result (KPI) or not (Chart)
        boolean aggregateResults = insight.isKpi();
        
        // Get activateOperation flag (default to false if null for backwards compatibility)
        boolean activateOperation = Boolean.TRUE.equals(insight.getActivateOperation());

        // Use the harmonized execution method from InsightsService
        InsightExecutionResult result = insightsService.executeAdHocInsight(
                insight.getTimeframe(),
                insight.getStartDate(),
                insight.getEndDate(),
                categoryIds,
                interval,
                intervalFunction,
                aggregateResults,
                activateOperation
        );

        // Override the auto-generated name and description with the saved insight's metadata
        result.setInsightId(insight.getId());
        result.setInsightName(insight.getName());
        result.setInsightDescription(insight.getDescription());
        
        // Set KPI color from saved insight (with fallback to default)
        result.setKpiColor(insight.getKpiColor() != null && !insight.getKpiColor().trim().isEmpty() 
                ? insight.getKpiColor() 
                : InsightExecutionResult.DEFAULT_KPI_COLOR);

        return result;
    }
}