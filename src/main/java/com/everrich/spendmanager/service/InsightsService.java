package com.everrich.spendmanager.service;

import com.everrich.spendmanager.dto.AggregatedInsight;
import com.everrich.spendmanager.repository.TransactionRepository;
import com.everrich.spendmanager.entities.Transaction;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
     * Main method to fetch and aggregate spending insights.
     */
    public List<AggregatedInsight> getCategoryInsights(
            String timeframe,
            LocalDate startDate,
            LocalDate endDate,
            List<Long> categoryIds,
            String interval,
            String intervalFunction) {

        DateRange range = calculateDateRange(timeframe, startDate, endDate);
        LocalDate finalStart = range.getStart();
        LocalDate finalEnd = range.getEnd();

        if (finalStart == null || finalEnd == null) {
            return Collections.emptyList();
        }

        List<Transaction> transactions;
        if (categoryIds == null || categoryIds.isEmpty()) {
            transactions = transactionRepository.findByDateRange(finalStart, finalEnd);
        } else {
            // Call the repository method that attempts to filter by categories
            transactions = transactionRepository.findByDateRangeAndCategories(
                    finalStart, finalEnd, categoryIds);

            // SAFEGURAD: Additional in-memory filtering to ensure category selection is respected
            transactions = transactions.stream()
                               .filter(t -> t.getCategoryEntity() != null && categoryIds.contains(t.getCategoryEntity().getId()))
                               .collect(Collectors.toList());
        }

        if (transactions.isEmpty()) {
            return Collections.emptyList();
        }

        if (interval != null && !"NOT_SPECIFIED".equals(interval)) {
            // Handle interval-based aggregation
            if ("MONTHLY".equals(interval) && "SUM".equals(intervalFunction)) {
                Map<String, Double> monthlyTotals = transactions.stream()
                        .collect(Collectors.groupingBy(
                                t -> t.getDate().format(DateTimeFormatter.ofPattern("MMM yyyy")),
                                Collectors.summingDouble(Transaction::getAmount)
                        ));

                return monthlyTotals.entrySet().stream()
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

            return categoryTotals.entrySet().stream()
                    .map(entry -> new AggregatedInsight(entry.getKey(), entry.getValue()))
                    .sorted((a, b) -> Double.compare(b.getCumulatedAmount(), a.getCumulatedAmount())) 
                    .collect(Collectors.toList());
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
                start = transactionRepository.findMinDate().orElse(null);
                end = transactionRepository.findMaxDate().orElse(null);
                break;
            case "DATE_RANGE":
                start = customStart != null ? customStart : transactionRepository.findMinDate().orElse(LocalDate.MIN);
                end = customEnd != null ? customEnd : transactionRepository.findMaxDate().orElse(LocalDate.MAX);
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
