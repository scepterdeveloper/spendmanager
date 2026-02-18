package com.everrich.spendmanager.service;

import com.everrich.spendmanager.dto.AggregatedInsight;
import com.everrich.spendmanager.dto.InsightExecutionResult;
import com.everrich.spendmanager.dto.InsightExecutionResult.DataPoint;
import com.everrich.spendmanager.dto.InsightExecutionResult.XAxisType;
import com.everrich.spendmanager.repository.TransactionRepository;
import com.everrich.spendmanager.entities.Transaction;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.Arrays;

@Service
public class InsightsService {

    private final TransactionRepository transactionRepository;

    public InsightsService(TransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    /**
     * Data Transfer Object (DTO) for internal use to hold calculated date range.
     */
    private static class DateRange {
        private final LocalDate start;
        private final LocalDate end;

        public DateRange(LocalDate start, LocalDate end) {
            this.start = start;
            this.end = end;
        }

        public LocalDate getStart() {
            return start;
        }

        public LocalDate getEnd() {
            return end;
        }
    }

    /**
     * NEW: Execute an ad-hoc insight and return a result suitable for display.
     * This harmonizes the execution logic for both saved insights and ad-hoc analysis.
     */
    public InsightExecutionResult executeAdHocInsight(
            String timeframe,
            LocalDate startDate,
            LocalDate endDate,
            List<Long> categoryIds,
            String interval,
            String intervalFunction,
            boolean aggregateResults) {

        // Execute the underlying analysis
        List<AggregatedInsight> analysisResults = getCategoryInsights(
                timeframe,
                startDate,
                endDate,
                categoryIds,
                interval,
                intervalFunction,
                aggregateResults
        );

        // Build a generic insight name based on parameters
        String insightName = buildInsightName(timeframe, interval, aggregateResults);
        String insightDescription = buildInsightDescription(timeframe, startDate, endDate, categoryIds);

        // Transform the results into InsightExecutionResult
        if (aggregateResults) {
            // KPI result (1D - single aggregated value)
            Double aggregatedValue = 0.0;
            if (!analysisResults.isEmpty() && analysisResults.get(0).getName().equals("Total Aggregated")) {
                aggregatedValue = analysisResults.get(0).getCumulatedAmount();
            }
            return InsightExecutionResult.createKpiResult(
                    null, // No ID for ad-hoc insights
                    insightName,
                    insightDescription,
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
                    null, // No ID for ad-hoc insights
                    insightName,
                    insightDescription,
                    dataPoints,
                    xAxisType
            );
        }
    }

    /**
     * Helper method to build a descriptive insight name based on parameters.
     */
    private String buildInsightName(String timeframe, String interval, boolean aggregateResults) {
        StringBuilder name = new StringBuilder();
        
        if (aggregateResults) {
            name.append("Total Spending");
        } else if (interval != null && !interval.isEmpty()) {
            name.append(interval).append(" Analysis");
        } else {
            name.append("Category Analysis");
        }
        
        name.append(" - ").append(formatTimeframeName(timeframe));
        
        return name.toString();
    }

    /**
     * Helper method to build insight description.
     */
    private String buildInsightDescription(String timeframe, LocalDate startDate, LocalDate endDate, List<Long> categoryIds) {
        StringBuilder desc = new StringBuilder("Analysis for ");
        desc.append(formatTimeframeName(timeframe));
        
        if ("DATE_RANGE".equals(timeframe) && startDate != null && endDate != null) {
            desc.append(" (").append(startDate).append(" to ").append(endDate).append(")");
        }
        
        if (categoryIds != null && !categoryIds.isEmpty()) {
            desc.append(" | ").append(categoryIds.size()).append(" categories selected");
        } else {
            desc.append(" | All categories");
        }
        
        return desc.toString();
    }

    /**
     * Helper method to format timeframe for display.
     */
    private String formatTimeframeName(String timeframe) {
        switch (timeframe) {
            case "THIS_MONTH": return "This Month";
            case "LAST_MONTH": return "Last Month";
            case "THIS_YEAR": return "This Year";
            case "LAST_YEAR": return "Last Year";
            case "ENTIRE_TIMEFRAME": return "Entire Timeframe";
            case "DATE_RANGE": return "Custom Date Range";
            default: return timeframe;
        }
    }

    /**
     * Main method to fetch and aggregate spending insights.
     * This is the core analysis engine used by both saved and ad-hoc insights.
     */
    public List<AggregatedInsight> getCategoryInsights(
            String timeframe,
            LocalDate startDate,
            LocalDate endDate,
            List<Long> categoryIds,
            String interval,
            String intervalFunction,
            boolean aggregateResults) {

        DateRange range = calculateDateRange(timeframe, startDate, endDate);
        LocalDate finalStart = range.getStart();
        LocalDate finalEnd = range.getEnd();

        if (finalStart == null || finalEnd == null) {
            return Collections.emptyList();
        }

        // Convert LocalDate to LocalDateTime for repository calls
        LocalDateTime startDateTime = finalStart.atStartOfDay();
        LocalDateTime endDateTime = finalEnd.atTime(LocalTime.MAX);
        
        List<Transaction> transactions;
        if (categoryIds == null || categoryIds.isEmpty()) {
            transactions = transactionRepository.findByDateRange(startDateTime, endDateTime);
        } else {
            // Call the repository method that attempts to filter by categories
            transactions = transactionRepository.findByDateRangeAndCategories(
                    startDateTime, endDateTime, categoryIds);

            // SAFEGUARD: Additional in-memory filtering to ensure category selection is respected
            transactions = transactions.stream()
                               .filter(t -> t.getCategoryEntity() != null && categoryIds.contains(t.getCategoryEntity().getId()))
                               .collect(Collectors.toList());
        }

        if (transactions.isEmpty()) {
            return Collections.emptyList();
        }

        List<AggregatedInsight> insights;

        if (interval != null && !"NOT_SPECIFIED".equals(interval)) {
            // Handle interval-based aggregation
            if ("MONTHLY".equals(interval) && "SUM".equals(intervalFunction)) {
                Map<String, Double> monthlyTotals = transactions.stream()
                        .collect(Collectors.groupingBy(
                                t -> t.getDate().format(DateTimeFormatter.ofPattern("MMM yyyy")),
                                Collectors.summingDouble(Transaction::getAmount)
                        ));

                insights = monthlyTotals.entrySet().stream()
                        .map(entry -> new AggregatedInsight(entry.getKey(), entry.getValue()))
                        .sorted((a, b) -> {
                            // Custom sort for monthly data (e.g., Jan 2023, Feb 2023)
                            try {
                                LocalDate dateA = LocalDate.parse("01 " + a.getName(), DateTimeFormatter.ofPattern("dd MMM yyyy"));
                                LocalDate dateB = LocalDate.parse("01 " + b.getName(), DateTimeFormatter.ofPattern("dd MMM yyyy"));
                                return dateA.compareTo(dateB);
                            } catch (Exception e) {
                                return a.getName().compareTo(b.getName()); // Fallback to string comparison if parsing fails
                            }
                        })
                        .collect(Collectors.toList());

            } else {
                // Fallback or unsupported interval/function
                return Collections.emptyList(); 
            }
        } else {
            // Existing category-based aggregation
            Map<String, Double> categoryTotals = transactions.stream()
                    .filter(t -> t.getCategoryEntity() != null && t.getCategoryEntity().getName() != null) 
                    .collect(Collectors.toMap(
                            t -> t.getCategoryEntity().getName(),
                            Transaction::getAmount,
                            Double::sum
                    ));

            insights = categoryTotals.entrySet().stream()
                    .map(entry -> new AggregatedInsight(entry.getKey(), entry.getValue()))
                    .sorted((a, b) -> Double.compare(b.getCumulatedAmount(), a.getCumulatedAmount())) 
                    .collect(Collectors.toList());
        }

        // Apply aggregation if requested
        if (aggregateResults && !insights.isEmpty()) {
            double totalAggregatedAmount = insights.stream()
                    .mapToDouble(AggregatedInsight::getCumulatedAmount)
                    .sum();
            return Arrays.asList(new AggregatedInsight("Total Aggregated", totalAggregatedAmount));
        } else {
            return insights;
        }
    }

    /**
     * Determines the absolute start and end dates based on the requested timeframe string.
     */
    private DateRange calculateDateRange(String timeframe, LocalDate customStart, LocalDate customEnd) {
        LocalDate now = LocalDate.now();
        LocalDate start = null;
        LocalDate end = null;

        switch (timeframe) {
            case "THIS_MONTH":
                start = now.with(TemporalAdjusters.firstDayOfMonth());
                end = now;
                break;
            case "LAST_MONTH":
                start = now.minusMonths(1).with(TemporalAdjusters.firstDayOfMonth());
                end = now.minusMonths(1).with(TemporalAdjusters.lastDayOfMonth());
                break;
            case "THIS_YEAR":
                start = now.with(TemporalAdjusters.firstDayOfYear());
                end = now;
                break;
            case "LAST_YEAR":
                start = now.minusYears(1).with(TemporalAdjusters.firstDayOfYear());
                end = now.minusYears(1).with(TemporalAdjusters.lastDayOfYear());
                break;
            case "ENTIRE_TIMEFRAME":
                // Convert LocalDateTime to LocalDate for the entire timeframe
                LocalDateTime minDate = transactionRepository.findMinDate().orElse(null);
                LocalDateTime maxDate = transactionRepository.findMaxDate().orElse(null);
                start = minDate != null ? minDate.toLocalDate() : null;
                end = maxDate != null ? maxDate.toLocalDate() : null;
                break;
            case "DATE_RANGE":
                // For DATE_RANGE, both customStart and customEnd must be provided
                if (customStart != null && customEnd != null) {
                    start = customStart;
                    end = customEnd;
                } else {
                    // If dates are not provided, return null range to indicate invalid state
                    start = null;
                    end = null;
                }
                break;
            default:
                start = now.with(TemporalAdjusters.firstDayOfMonth());
                end = now;
        }

        if (start != null && end != null && start.isAfter(end)) {
            return new DateRange(end, start);
        }

        return new DateRange(start, end);
    }
}