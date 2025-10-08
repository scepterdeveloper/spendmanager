package com.everrich.spendmanager.service;

import com.everrich.spendmanager.dto.CategoryInsight;
import com.everrich.spendmanager.repository.TransactionRepository;
import com.everrich.spendmanager.entities.Transaction; // Assuming your Transaction entity package
import org.springframework.stereotype.Service;
import java.time.LocalDate;
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
    public List<CategoryInsight> getCategoryInsights(
            String timeframe, LocalDate startDate, LocalDate endDate, List<Long> categoryIds) {

        DateRange range = calculateDateRange(timeframe, startDate, endDate);
        LocalDate finalStart = range.getStart();
        LocalDate finalEnd = range.getEnd();

        if (finalStart == null || finalEnd == null) {
            return Collections.emptyList();
        }

        List<Transaction> transactions = transactionRepository
                .findByDateRangeAndCategories(
                        finalStart, finalEnd, categoryIds);

        if (transactions.isEmpty()) {
            return Collections.emptyList();
        }

        // 3. CORRECT FIX: Use getCategoryEntity() and access its getName() method.
        // This resolves all prior casting and type mismatch issues.
        Map<String, Double> categoryTotals = transactions.stream()
                // 1. Ensure the Category entity object exists and has a name
                .filter(t -> t.getCategoryEntity() != null && t.getCategoryEntity().getName() != null) 
                
                // 2. Aggregate using Collectors.toMap
                .collect(Collectors.toMap(
                        // Key Mapper: Use CategoryEntity to get the name (String)
                        t -> t.getCategoryEntity().getName(),
                        // Value Mapper: Transaction Amount (Double)
                        Transaction::getAmount,
                        // Merge Function: Sum the amounts for duplicate keys
                        Double::sum
                ));

        // 4. Convert the Map into the final DTO list
        return categoryTotals.entrySet().stream()
                .map(entry -> new CategoryInsight(entry.getKey(), entry.getValue()))
                .sorted((a, b) -> Double.compare(b.getCumulatedAmount(), a.getCumulatedAmount())) 
                .collect(Collectors.toList());
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