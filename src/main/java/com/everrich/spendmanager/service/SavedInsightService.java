package com.everrich.spendmanager.service;

import com.everrich.spendmanager.dto.InsightExecutionResult;
import com.everrich.spendmanager.entities.SavedInsight;
import com.everrich.spendmanager.repository.SavedInsightRepository;

import org.springframework.stereotype.Service;

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

    public List<SavedInsight> findAll() {
        return savedInsightRepository.findAllByOrderByNameAsc();
    }

    public List<SavedInsight> findInsightsForDashboard() {
        return savedInsightRepository.findByShowOnDashboardTrue();
    }

    public Optional<SavedInsight> findById(Long id) {
        return savedInsightRepository.findById(id);
    }

    public SavedInsight save(SavedInsight savedInsight) {
        return savedInsightRepository.save(savedInsight);
    }

    public void deleteById(Long id) {
        savedInsightRepository.deleteById(id);
    }

    public SavedInsight findByName(String name) {
        return savedInsightRepository.findByNameIgnoreCase(name);
    }

    public List<InsightExecutionResult> getDashBoardKPIs() {
        List<InsightExecutionResult> results = new ArrayList<>();
        List<SavedInsight> dashBoardKPIInsights = savedInsightRepository.findByShowOnDashboardTrueAndAggregateResultsTrue();
        for(SavedInsight dashBoardKPIInsight: dashBoardKPIInsights) {
            results.add(this.execute(dashBoardKPIInsight.getId()));
        }
        return results;
    }

    public List<InsightExecutionResult> getDashBoardCharts() {
        List<InsightExecutionResult> results = new ArrayList<>();
        List<SavedInsight> dashBoardChartInsights = savedInsightRepository.findByShowOnDashboardTrueAndAggregateResultsFalse();
        for(SavedInsight dashboardChartInsight: dashBoardChartInsights) {
            results.add(this.execute(dashboardChartInsight.getId()));
        }
        return results;
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

        // Use the harmonized execution method from InsightsService
        InsightExecutionResult result = insightsService.executeAdHocInsight(
                insight.getTimeframe(),
                insight.getStartDate(),
                insight.getEndDate(),
                categoryIds,
                interval,
                intervalFunction,
                Boolean.TRUE.equals(insight.getAggregateResults())
        );

        // Override the auto-generated name and description with the saved insight's metadata
        result.setInsightId(insight.getId());
        result.setInsightName(insight.getName());
        result.setInsightDescription(insight.getDescription());

        return result;
    }
}