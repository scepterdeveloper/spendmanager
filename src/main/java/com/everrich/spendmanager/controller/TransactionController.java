package com.everrich.spendmanager.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.everrich.spendmanager.entities.Account;
import com.everrich.spendmanager.entities.Category;
import com.everrich.spendmanager.entities.Transaction;
import com.everrich.spendmanager.entities.TransactionCategorizationStatus;
import com.everrich.spendmanager.service.AccountService;
import com.everrich.spendmanager.service.CategoryService;
import com.everrich.spendmanager.service.TransactionService;
import com.everrich.spendmanager.entities.TransactionOperation;

import java.beans.PropertyEditorSupport;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.everrich.spendmanager.multitenancy.TenantContext;

import jakarta.servlet.http.HttpServletRequest;

@Controller
@RequestMapping("/transactions")
public class TransactionController {

    private final TransactionService transactionService;
    private final CategoryService categoryService;
    private final AccountService accountService;

    private static final Logger log = LoggerFactory.getLogger(TransactionController.class);

    public TransactionController(TransactionService transactionService, CategoryService categoryService, AccountService accountService) {
        this.transactionService = transactionService;
        this.categoryService = categoryService;
        this.accountService = accountService;
    }
    
    @InitBinder
    public void initBinder(WebDataBinder binder) {
        // Custom editor for LocalDateTime to handle date-only format from HTML form
        binder.registerCustomEditor(LocalDateTime.class, new PropertyEditorSupport() {
            @Override
            public void setAsText(String text) {
                if (text != null && !text.isEmpty()) {
                    try {
                        // Try parsing as LocalDateTime first (yyyy-MM-ddTHH:mm)
                        setValue(LocalDateTime.parse(text));
                    } catch (DateTimeParseException e1) {
                        try {
                            // Fall back to date-only format (yyyy-MM-dd) and set time to start of day
                            LocalDate date = LocalDate.parse(text, DateTimeFormatter.ISO_LOCAL_DATE);
                            setValue(date.atStartOfDay());
                        } catch (DateTimeParseException e2) {
                            log.error("Failed to parse date: {}", text);
                            setValue(null);
                        }
                    }
                } else {
                    setValue(null);
                }
            }
        });

        binder.registerCustomEditor(Category.class, new PropertyEditorSupport() {
            @Override
            public void setAsText(String categoryId) {
                if (categoryId != null && !categoryId.isEmpty()) {
                    try {
                        Long id = Long.valueOf(categoryId);
                        Category category = categoryService.findById(id).orElse(null);
                        setValue(category); 
                        log.debug("Bound Category ID {} to Entity: {}", categoryId, category != null ? category.getName() : "NOT FOUND");
                    } catch (NumberFormatException e) {
                        log.error("Failed to parse Category ID: {}", categoryId);
                        setValue(null);
                    }
                } else {
                    setValue(null);
                }
            }
        });

        binder.registerCustomEditor(Account.class, new PropertyEditorSupport() {
            @Override
            public void setAsText(String accountId) {
                if (accountId != null && !accountId.isEmpty()) {
                    try {
                        Long id = Long.valueOf(accountId);
                        Account account = accountService.findById(id).orElse(null);
                        setValue(account);
                        log.debug("Bound Account ID {} to Entity: {}", accountId, account != null ? account.getName() : "NOT FOUND");
                    } catch (NumberFormatException e) {
                        log.error("Failed to parse Account ID: {}", accountId);
                        setValue(null);
                    }
                } else {
                    setValue(null);
                }
            }
        });
    }

    @GetMapping
    public String viewTransactions(
        @RequestParam(required = false, defaultValue = "current_month") String timeframe,
        @RequestParam(value = "startDate", required = false) String startDateStr, 
        @RequestParam(value = "endDate", required = false) String endDateStr,    
        @RequestParam(required = false) List<Long> accountIds,
        @RequestParam(required = false) List<Long> categoryIds,
        @RequestParam(required = false) Boolean reviewedFilter,
        @RequestParam(value = "query", required = false) String query,
        Model model,
        @RequestParam Map<String, String> params) { 

        log.info("Listing transactions...");
        model.addAttribute("appName", "EverRich");
        model.addAttribute("operations", TransactionOperation.values());
        LocalDate selectedStartDate = null;
        LocalDate selectedEndDate = null;
    
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    
        if (startDateStr != null && !startDateStr.trim().isEmpty()) {
            try { selectedStartDate = LocalDate.parse(startDateStr.trim(), formatter); } 
            catch (DateTimeParseException ignored) {}
        }
        if (endDateStr != null && !endDateStr.trim().isEmpty()) {
            try { selectedEndDate = LocalDate.parse(endDateStr.trim(), formatter); } 
            catch (DateTimeParseException ignored) {}
        }
    
        List<Transaction> filteredTransactions = transactionService.getFilteredTransactions(
            timeframe, 
            selectedStartDate,
            selectedEndDate,
            accountIds,
            categoryIds,
            reviewedFilter,
            query 
        );
    
        model.addAttribute("transactions", filteredTransactions);
        model.addAttribute("categories", categoryService.findAll());
        model.addAttribute("accounts", accountService.findAll());
    
        // Calculate and add totals for credits and debits
        BigDecimal totalCredits = transactionService.calculateTotalCredits(filteredTransactions);
        BigDecimal totalDebits = transactionService.calculateTotalDebits(filteredTransactions);
        model.addAttribute("totalCredits", totalCredits);
        model.addAttribute("totalDebits", totalDebits);
    
        model.addAttribute("selectedTimeframe", timeframe);
        model.addAttribute("selectedStartDate", startDateStr); 
        model.addAttribute("selectedEndDate", endDateStr);
        model.addAttribute("selectedAccountIds", accountIds);
        model.addAttribute("selectedCategoryIds", categoryIds); 
        model.addAttribute("selectedReviewedFilter", reviewedFilter);
        model.addAttribute("selectedQuery", query); 
    
        model.addAttribute("filterParams", params);
    
        return "transaction-management"; 
    }

    @GetMapping("/add")
    public String addTransaction(
        @RequestParam Map<String, String> params, 
        Model model) {
        
        model.addAttribute("appName", "EverRich");
        Transaction newTransaction = new Transaction();
        newTransaction.setDate(LocalDateTime.now()); 
        
        model.addAttribute("transaction", newTransaction); 
        model.addAttribute("operations", TransactionOperation.values());
        model.addAttribute("categories", categoryService.findAll());
        model.addAttribute("accounts", accountService.findAll()); 
        
        model.addAttribute("filterParams", params);
        
        return "transaction-form";
    }

    @GetMapping("/edit/{id}")
    public String editTransaction(
        @PathVariable("id") Long id, 
        HttpServletRequest request,
        Model model) {
        
        model.addAttribute("appName", "EverRich");
        Optional<Transaction> optionalTransaction = transactionService.findById(id);
        optionalTransaction.ifPresent(transaction -> {
            log.info("Found transaction: " + transaction.getDescription() + " with category id " + (transaction.getCategoryEntity() != null ? transaction.getCategoryEntity().getId() : "null"));
            model.addAttribute("transaction", optionalTransaction.get());
            model.addAttribute("operations", TransactionOperation.values());
        });        

        // Build filter params map that preserves multiple values for accountIds and categoryIds
        Map<String, List<String>> filterParams = buildFilterParamsMap(request);

        if (optionalTransaction.isEmpty()) {
            return "redirect:/transactions"; 
        }

        model.addAttribute("transaction", optionalTransaction.get());
        model.addAttribute("categories", categoryService.findAll());
        model.addAttribute("accounts", accountService.findAll()); 
        
        model.addAttribute("filterParams", filterParams);
        
        return "transaction-form";
    }
    
    /**
     * Build a filter params map that preserves multiple values for multi-value parameters
     * like accountIds and categoryIds.
     */
    private Map<String, List<String>> buildFilterParamsMap(HttpServletRequest request) {
        Map<String, List<String>> filterParams = new HashMap<>();
        Map<String, String[]> parameterMap = request.getParameterMap();
        
        for (Map.Entry<String, String[]> entry : parameterMap.entrySet()) {
            String key = entry.getKey();
            String[] values = entry.getValue();
            
            if (values != null && values.length > 0) {
                List<String> valueList = new ArrayList<>();
                for (String value : values) {
                    if (value != null && !value.trim().isEmpty()) {
                        valueList.add(value);
                    }
                }
                if (!valueList.isEmpty()) {
                    filterParams.put(key, valueList);
                }
            }
        }
        
        return filterParams;
    }

    @PostMapping("/save")
    public String saveTransaction(
        @ModelAttribute Transaction transaction,
        @RequestParam(required = false) Long originalCategoryId, 
        HttpServletRequest request,
        RedirectAttributes redirectAttributes) {

        boolean isNewTransaction = transaction.getId() == null; 

        try {
            if (transaction.getCategoryEntity() != null) {
                transaction.setCategory(transaction.getCategoryEntity().getName());
            } else {
                transaction.setCategory(null);
            }

            transaction.setCategorizationStatus(TransactionCategorizationStatus.USER_CATEGORIZED);
            
            // Mark transaction as reviewed when explicitly saved/edited by user
            transaction.setReviewed(true);
            
            log.info("Going to save transaction...");
            log.info("T-ID {} | RAG Category (String): {} | Entity Set: {} | Account Set: {}", 
                     transaction.getId(), 
                     transaction.getCategory(), 
                     transaction.getCategoryEntity() != null ? transaction.getCategoryEntity().getName() : "NULL",
                     transaction.getAccount() != null ? transaction.getAccount().getName() : "NULL");

            // Capture tenant ID BEFORE async call (ThreadLocal won't propagate to async thread)
            String tenantId = TenantContext.getTenantId();
            
            transactionService.saveTransaction(transaction);
            if(transaction.getCategoryEntity() != null && (originalCategoryId == null || !transaction.getCategoryEntity().getId().equals(originalCategoryId))) {
                // Pass transaction data and tenantId directly to avoid race conditions with async execution
                String accountName = transaction.getAccount() != null ? transaction.getAccount().getName() : null;
                transactionService.updateVectorStore(
                    transaction.getDescription(), 
                    transaction.getCategory(),
                    transaction.getAmount(),
                    transaction.getOperation(),
                    accountName,
                    tenantId
                );
            }
            
            String message = isNewTransaction ? "New transaction created successfully!" : "Transaction updated successfully!";
            redirectAttributes.addFlashAttribute("message", message);
            redirectAttributes.addFlashAttribute("messageType", "success");
        } catch (Exception e) {
            log.error("Error saving transaction: ", e);
            redirectAttributes.addFlashAttribute("message", "Error saving transaction: " + e.getMessage());
            redirectAttributes.addFlashAttribute("messageType", "error");
        }
        
        if (isNewTransaction) {
            return "redirect:/dashboard"; 
        } else {
            // Add filter parameters to redirect, preserving multi-value params
            addFilterParamsToRedirect(request, redirectAttributes);
            return "redirect:/transactions";
        }
    }
    
    /**
     * Add filter parameters to redirect attributes, properly handling multi-value parameters.
     */
    private void addFilterParamsToRedirect(HttpServletRequest request, RedirectAttributes redirectAttributes) {
        // List of filter parameter names to include in redirect
        List<String> filterParamNames = List.of("timeframe", "startDate", "endDate", "accountIds", "categoryIds", "reviewedFilter", "query");
        
        Map<String, String[]> parameterMap = request.getParameterMap();
        
        for (String paramName : filterParamNames) {
            String[] values = parameterMap.get(paramName);
            if (values != null) {
                for (String value : values) {
                    if (value != null && !value.trim().isEmpty()) {
                        redirectAttributes.addAttribute(paramName, value);
                    }
                }
            }
        }
    }
    
    @PostMapping("/{id}/delete")
    public String deleteTransaction(
        @PathVariable("id") Long id, 
        HttpServletRequest request,
        RedirectAttributes redirectAttributes) {
        
        try {
            transactionService.deleteTransaction(id);
            redirectAttributes.addFlashAttribute("message", "Transaction deleted successfully!");
            redirectAttributes.addFlashAttribute("messageType", "success");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("message", "Error deleting transaction.");
            redirectAttributes.addFlashAttribute("messageType", "error");
        }
        
        // Add filter parameters to redirect, preserving multi-value params
        addFilterParamsToRedirect(request, redirectAttributes);

        return "redirect:/transactions";
    }
}
