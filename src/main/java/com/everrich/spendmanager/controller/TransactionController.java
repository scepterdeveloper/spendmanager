package com.everrich.spendmanager.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.everrich.spendmanager.entities.Transaction;
import com.everrich.spendmanager.service.CategoryService;
import com.everrich.spendmanager.service.TransactionService;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Controller
@RequestMapping("/transactions")
public class TransactionController {

    private final TransactionService transactionService;
    private final CategoryService categoryService;

    public TransactionController(TransactionService transactionService, CategoryService categoryService) {
        this.transactionService = transactionService;
        this.categoryService = categoryService;
    }

    // -------------------------------------------------------------------------
    // GET MAPPING (VIEW/FILTER)
    // -------------------------------------------------------------------------
    
    /**
     * Retrieves transactions with optional filtering and returns the management view.
     */
    @GetMapping
    public String viewTransactions(
        @RequestParam(required = false, defaultValue = "current_month") String timeframe,
        @RequestParam(value = "startDate", required = false) String startDateStr, 
        @RequestParam(value = "endDate", required = false) String endDateStr,    
        @RequestParam(required = false) List<Long> categoryIds,
        @RequestParam(value = "query", required = false) String query, // Capture the full-text search query
        Model model,
        @RequestParam Map<String, String> params) { 

        model.addAttribute("appName", "EverRich");
        LocalDate selectedStartDate = null;
        LocalDate selectedEndDate = null;
    
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    
        // Safely Parse Dates
        // NOTE: No change needed here. Null/Empty strings for dates are gracefully handled.
        if (startDateStr != null && !startDateStr.trim().isEmpty()) {
            try { selectedStartDate = LocalDate.parse(startDateStr.trim(), formatter); } 
            catch (DateTimeParseException ignored) {}
        }
        if (endDateStr != null && !endDateStr.trim().isEmpty()) {
            try { selectedEndDate = LocalDate.parse(endDateStr.trim(), formatter); } 
            catch (DateTimeParseException ignored) {}
        }
    
        // Fetch filtered transactions
        // UPDATED: Pass the new 'query' parameter to the service
        List<Transaction> filteredTransactions = transactionService.getFilteredTransactions(
            timeframe, 
            selectedStartDate,
            selectedEndDate,
            categoryIds,
            query 
        );
    
        // Add required model attributes
        model.addAttribute("transactions", filteredTransactions);
        model.addAttribute("categories", categoryService.findAll());
    
        // Re-populate the filter selection for the form (Crucial for HTML)
        model.addAttribute("selectedTimeframe", timeframe);
        model.addAttribute("selectedStartDate", startDateStr); // Keep as String to handle null/empty case
        model.addAttribute("selectedEndDate", endDateStr);     // Keep as String to handle null/empty case
        model.addAttribute("selectedCategoryIds", categoryIds); 
        model.addAttribute("selectedQuery", query); // Add selected query to model
    
        // Pass all raw parameters (for form action links and hidden fields)
        model.addAttribute("filterParams", params);
    
        return "transaction-management"; 
    }

    // -------------------------------------------------------------------------
    // ADD MAPPING
    // -------------------------------------------------------------------------
    
    @GetMapping("/add")
    public String addTransaction(
        @RequestParam Map<String, String> params, 
        Model model) {
        
        model.addAttribute("appName", "EverRich");
        Transaction newTransaction = new Transaction();
        newTransaction.setDate(LocalDate.now()); 
        
        model.addAttribute("transaction", newTransaction); 
        model.addAttribute("categories", categoryService.findAll());
        
        model.addAttribute("filterParams", params);
        
        return "transaction-form";
    }

    // -------------------------------------------------------------------------
    // EDIT MAPPING
    // -------------------------------------------------------------------------
    
    @GetMapping("/edit/{id}")
    public String editTransaction(
        @PathVariable("id") Long id, 
        @RequestParam Map<String, String> params, 
        Model model) {
        
        model.addAttribute("appName", "EverRich");
        Optional<Transaction> optionalTransaction = transactionService.findById(id);

        if (optionalTransaction.isEmpty()) {
            params.forEach(model::addAttribute);
            return "redirect:/transactions"; 
        }

        model.addAttribute("transaction", optionalTransaction.get());
        model.addAttribute("categories", categoryService.findAll());
        
        model.addAttribute("filterParams", params);
        
        return "transaction-form";
    }

    // -------------------------------------------------------------------------
    // POST MAPPING (SAVE/DELETE) - UPDATED REDIRECTION
    // -------------------------------------------------------------------------

    @PostMapping("/save")
    public String saveTransaction(
        @ModelAttribute Transaction transaction, 
        @RequestParam Map<String, Object> filterParams, 
        RedirectAttributes redirectAttributes) {
        
        boolean isNewTransaction = transaction.getId() == null; 

        try {
            transactionService.saveTransaction(transaction);
            
            String message = isNewTransaction ? "New transaction created successfully!" : "Transaction updated successfully!";
            redirectAttributes.addFlashAttribute("message", message);
            redirectAttributes.addFlashAttribute("messageType", "success");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("message", "Error saving transaction: " + e.getMessage());
            redirectAttributes.addFlashAttribute("messageType", "error");
        }
        
        if (isNewTransaction) {
            return "redirect:/"; 
        } else {
            filterParams.forEach(redirectAttributes::addAttribute);
            return "redirect:/transactions";
        }
    }
    
    @PostMapping("/{id}/delete")
    public String deleteTransaction(
        @PathVariable("id") Long id, 
        @RequestParam Map<String, Object> filterParams, 
        RedirectAttributes redirectAttributes) {
        
        try {
            transactionService.deleteTransaction(id);
            redirectAttributes.addFlashAttribute("message", "Transaction deleted successfully!");
            redirectAttributes.addFlashAttribute("messageType", "success");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("message", "Error deleting transaction.");
            redirectAttributes.addFlashAttribute("messageType", "error");
        }
        
        filterParams.forEach(redirectAttributes::addAttribute);

        return "redirect:/transactions";
    }
}