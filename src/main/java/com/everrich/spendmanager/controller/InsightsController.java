package com.everrich.spendmanager.controller;

import com.everrich.spendmanager.dto.AggregatedInsight;
import com.everrich.spendmanager.dto.InsightExecutionResult;
import com.everrich.spendmanager.entities.Category;
import com.everrich.spendmanager.entities.Transaction;
import com.everrich.spendmanager.repository.CategoryRepository;
import com.everrich.spendmanager.repository.TransactionRepository;
import com.everrich.spendmanager.service.InsightsService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpSession;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Controller
@RequestMapping({"/insights", "/api/insights"})
public class InsightsController {

    private final InsightsService insightsService;
    private final CategoryRepository categoryRepository;
    private final TransactionRepository transactionRepository;
    
    public InsightsController(InsightsService insightsService, CategoryRepository categoryRepository,
                              TransactionRepository transactionRepository) {
        this.insightsService = insightsService;
        this.categoryRepository = categoryRepository;
        this.transactionRepository = transactionRepository;
    }

    // 1. Controller for the Thymeleaf Page View (Parameter Collection)
    // URL: /insights
    @GetMapping
    public String showInsightsPage(Model model) {
        model.addAttribute("appName", "EverRich");
        return "insights"; 
    }

    // 2. Execute ad-hoc insight and redirect to result page via GET (for proper back navigation)
    // URL: POST /insights/execute
    @PostMapping("/execute")
    public String executeAdHocInsight(
            @RequestParam String timeframe,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) String categoryIds,
            @RequestParam(required = false) String interval,
            @RequestParam(required = false) String intervalFunction,
            @RequestParam(defaultValue = "CHART") String insightType,
            HttpSession session) {
        
        try {
            // Parse category IDs from comma-separated string
            List<Long> categoryIdList = Collections.emptyList();
            if (categoryIds != null && !categoryIds.trim().isEmpty()) {
                categoryIdList = Arrays.stream(categoryIds.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .map(Long::parseLong)
                        .collect(Collectors.toList());
            }

            // Normalize interval parameter
            String normalizedInterval = "NOT_SPECIFIED".equals(interval) ? null : interval;

            // Convert insightType to boolean for service (KPI = aggregate results, CHART = detailed results)
            boolean aggregateResults = "KPI".equalsIgnoreCase(insightType);

            // Execute the analysis
            InsightExecutionResult result = insightsService.executeAdHocInsight(
                    timeframe,
                    startDate,
                    endDate,
                    categoryIdList,
                    normalizedInterval,
                    intervalFunction,
                    aggregateResults
            );

            // Store result in session with unique ID for GET retrieval
            String resultId = UUID.randomUUID().toString();
            session.setAttribute("insight_result_" + resultId, result);
            
            // Redirect to GET endpoint for proper browser back navigation support
            return "redirect:/insights/result/" + resultId;
            
        } catch (Exception e) {
            return "redirect:/insights?error=" + e.getMessage();
        }
    }

    // 2b. GET endpoint to view cached ad-hoc insight result (supports browser back navigation)
    // URL: GET /insights/result/{resultId}
    @GetMapping("/result/{resultId}")
    public String viewInsightResult(@PathVariable String resultId, HttpSession session, Model model) {
        model.addAttribute("appName", "EverRich");
        
        InsightExecutionResult result = (InsightExecutionResult) session.getAttribute("insight_result_" + resultId);
        
        if (result == null) {
            // Result expired or invalid - redirect back to insights page
            return "redirect:/insights?error=Result+expired.+Please+run+the+analysis+again.";
        }
        
        model.addAttribute("result", result);
        // Set back URL to return to insights page, and resultId for drill-down back navigation
        model.addAttribute("backUrl", "/insights");
        model.addAttribute("resultPageUrl", "/insights/result/" + resultId);
        
        return "insight-result";
    }

    // 3. REST Endpoint to get all categories for the filter dropdown
    // URL: /api/insights/categories
    @GetMapping("/categories")
    @ResponseBody
    public List<Category> getAllCategories() {
        return categoryRepository.findAll(); 
    }

    // 4. Legacy REST Endpoint to get the analysis data (kept for backward compatibility)
    // URL: /api/insights/analyze
    @GetMapping("/analyze")
    @ResponseBody
    public ResponseEntity<List<AggregatedInsight>> analyze(
            @RequestParam String timeframe,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam List<Long> categoryIds,
            @RequestParam(required = false) String interval,
            @RequestParam(required = false) String intervalFunction,
            @RequestParam(defaultValue = "CHART") String insightType) {

        try {
            // Convert insightType to boolean for service (KPI = aggregate results, CHART = detailed results)
            boolean aggregateResults = "KPI".equalsIgnoreCase(insightType);
            
            List<AggregatedInsight> insights = insightsService.getCategoryInsights(
                timeframe, startDate, endDate, categoryIds, interval, intervalFunction, aggregateResults);
                
            return ResponseEntity.ok(insights);
        } catch (Exception e) {
            System.err.println("Error generating insights: " + e.getMessage());
            return ResponseEntity.status(500).build();
        }
    }
    
    // 5. Drill-down endpoint for viewing transactions behind an insight data point
    // URL: GET /insights/drill/transactions
    @GetMapping("/drill/transactions")
    public String drillDownTransactions(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam Long categoryId,
            @RequestParam(required = false) String backUrl,
            Model model) {
        
        model.addAttribute("appName", "EverRich");
        
        try {
            // Fetch the category details
            Category category = categoryRepository.findById(categoryId)
                    .orElseThrow(() -> new IllegalArgumentException("Category not found: " + categoryId));
            
            // Convert LocalDate to LocalDateTime for repository calls
            LocalDateTime startDateTime = startDate.atStartOfDay();
            LocalDateTime endDateTime = endDate.atTime(LocalTime.MAX);
            
            // Fetch transactions for the category within the date range
            List<Transaction> transactions = transactionRepository.findByDateRangeAndCategories(
                    startDateTime, endDateTime, Collections.singletonList(categoryId));
            
            // Additional in-memory filtering to ensure category match
            transactions = transactions.stream()
                    .filter(t -> t.getCategoryEntity() != null && t.getCategoryEntity().getId().equals(categoryId))
                    .sorted((a, b) -> b.getDate().compareTo(a.getDate())) // Sort by date descending
                    .collect(Collectors.toList());
            
            // Calculate total amount for display
            double totalAmount = transactions.stream()
                    .mapToDouble(Transaction::getAmount)
                    .sum();
            
            model.addAttribute("transactions", transactions);
            model.addAttribute("category", category);
            model.addAttribute("startDate", startDate);
            model.addAttribute("endDate", endDate);
            model.addAttribute("totalAmount", totalAmount);
            model.addAttribute("transactionCount", transactions.size());
            model.addAttribute("backUrl", backUrl != null && !backUrl.isEmpty() ? backUrl : "/insights");
            
            return "insight-drill-transactions";
            
        } catch (Exception e) {
            model.addAttribute("error", "Failed to load drill-down: " + e.getMessage());
            return "redirect:/insights";
        }
    }
}
