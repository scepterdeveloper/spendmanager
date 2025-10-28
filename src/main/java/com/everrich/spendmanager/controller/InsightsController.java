package com.everrich.spendmanager.controller;

import com.everrich.spendmanager.dto.CategoryInsight;
import com.everrich.spendmanager.entities.Category; // Assuming your Category entity package
import com.everrich.spendmanager.repository.CategoryRepository; 
import com.everrich.spendmanager.service.InsightsService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

@Controller
@RequestMapping({"/insights", "/api/insights"}) // Maps the root path to the page and /api to the data
public class InsightsController {

    private final InsightsService insightsService;
    private final CategoryRepository categoryRepository;
    
    // Inject the services and repositories
    public InsightsController(InsightsService insightsService, CategoryRepository categoryRepository) {
        this.insightsService = insightsService;
        this.categoryRepository = categoryRepository;
    }

    // 1. Controller for the Thymeleaf Page View
    // URL: /insights
    @GetMapping
    public String showInsightsPage(Model model) {
        model.addAttribute("appName", "EverRich");
        // Returns the HTML template
        return "insights"; 
    }

    // 2. REST Endpoint to get all categories for the filter dropdown
    // URL: /api/insights/categories
    @GetMapping("/categories")
    @ResponseBody // Tells Spring to return the data directly as JSON
    public List<Category> getAllCategories() {
        // Fetch all categories. Assuming the repository method handles sorting (e.g., findAllByOrderByNameAsc())
        return categoryRepository.findAll(); 
    }

    // 3. REST Endpoint to get the analysis data
    // URL: /api/insights/analyze
    @GetMapping("/analyze")
    @ResponseBody
    public ResponseEntity<List<CategoryInsight>> analyze(
            @RequestParam String timeframe,
            // Uses @DateTimeFormat to correctly parse dates from the query parameter
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            // Spring MVC handles parsing List<Long> from a comma-separated query string
            @RequestParam List<Long> categoryIds) {

        // Basic validation
        if (categoryIds == null || categoryIds.isEmpty()) {
            // Return an empty list with a successful 200 status
            return ResponseEntity.ok(Collections.emptyList());
        }

        try {
            // Delegate the heavy lifting to the Service layer
            List<CategoryInsight> insights = insightsService.getCategoryInsights(
                timeframe, startDate, endDate, categoryIds);
                
            return ResponseEntity.ok(insights);
        } catch (Exception e) {
            // Log the error and return a 500 status for the AJAX call
            System.err.println("Error generating insights: " + e.getMessage());
            return ResponseEntity.status(500).build();
        }
    }
}