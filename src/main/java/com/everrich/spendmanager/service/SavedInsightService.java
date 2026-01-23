package com.everrich.spendmanager.service;

import com.everrich.spendmanager.dto.AggregatedInsight;
import com.everrich.spendmanager.dto.InsightExecutionResult;
import com.everrich.spendmanager.dto.InsightExecutionResult.DataPoint;
import com.everrich.spendmanager.dto.InsightExecutionResult.XAxisType;
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

    public List<InsightExecutionResult> getDashBoardKPIs()    {

        List<InsightExecutionResult> results = new ArrayList<>();
        List<SavedInsight> dashBoardKPIInsights = savedInsightRepository.findByShowOnDashboardTrueAndAggregateResultsTrue();
        for(SavedInsight dashBoardKPIInsight: dashBoardKPIInsights) {

            results.add(this.execute(dashBoardKPIInsight.getId()));

        }

        return results;
    }

    /**
     * Execute a saved insight and return a generic result that can be mapped to various UI chart types.
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

        // Execute the analysis using the existing InsightsService
        List<AggregatedInsight> analysisResults = insightsService.getCategoryInsights(
                insight.getTimeframe(),
                insight.getStartDate(),
                insight.getEndDate(),
                categoryIds,
                interval,
                intervalFunction,
                Boolean.TRUE.equals(insight.getAggregateResults())
        );

        // Transform the results into InsightExecutionResult
        if (Boolean.TRUE.equals(insight.getAggregateResults())) {
            // KPI result (1D - single aggregated value)
            Double aggregatedValue = 0.0;
            if (!analysisResults.isEmpty() && analysisResults.get(0).getName().equals("Total Aggregated")) {
                aggregatedValue = analysisResults.get(0).getCumulatedAmount();
            }
            return InsightExecutionResult.createKpiResult(
                    insight.getId(),
                    insight.getName(),
                    insight.getDescription(),
                    aggregatedValue
            );
        } else {
            // Chart result (2D - multiple data points)
            List<DataPoint> dataPoints = analysisResults.stream()
                    .map(ai -> new DataPoint(ai.getName(), ai.getCumulatedAmount()))
                    .collect(Collectors.toList());

            // Determine X-axis type based on whether interval is specified
            XAxisType xAxisType = (interval != null && !interval.isEmpty()) 
                    ? XAxisType.INTERVAL 
                    : XAxisType.CATEGORY;

            return InsightExecutionResult.createChartResult(
                    insight.getId(),
                    insight.getName(),
                    insight.getDescription(),
                    dataPoints,
                    xAxisType
            );
        }
    }
}
