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
    private final AccountService accountService;

    private static final Logger log = LoggerFactory.getLogger(TransactionController.class);

    public TransactionController(TransactionService transactionService, CategoryService categoryService, AccountService accountService) {
        this.transactionService = transactionService;
        this.categoryService = categoryService;
        this.accountService = accountService;
    }
    
    @InitBinder
    public void initBinder(WebDataBinder binder) {
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
        @RequestParam(required = false) List<Long> categoryIds,
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
            categoryIds,
            query 
        );
    
        model.addAttribute("transactions", filteredTransactions);
        model.addAttribute("categories", categoryService.findAll());
    
        model.addAttribute("selectedTimeframe", timeframe);
        model.addAttribute("selectedStartDate", startDateStr); 
        model.addAttribute("selectedEndDate", endDateStr);     
        model.addAttribute("selectedCategoryIds", categoryIds); 
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
        newTransaction.setDate(LocalDate.now()); 
        
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
        @RequestParam Map<String, String> params, 
        Model model) {
        
        model.addAttribute("appName", "EverRich");
        Optional<Transaction> optionalTransaction = transactionService.findById(id);
        optionalTransaction.ifPresent(transaction -> {
            log.info("Found transaction: " + transaction.getDescription() + " with category id " + (transaction.getCategoryEntity() != null ? transaction.getCategoryEntity().getId() : "null"));
            model.addAttribute("transaction", optionalTransaction.get());
            model.addAttribute("operations", TransactionOperation.values());
        });        

        if (optionalTransaction.isEmpty()) {
            params.forEach(model::addAttribute);
            return "redirect:/transactions"; 
        }

        model.addAttribute("transaction", optionalTransaction.get());
        model.addAttribute("categories", categoryService.findAll());
        model.addAttribute("accounts", accountService.findAll()); 
        
        model.addAttribute("filterParams", params);
        
        return "transaction-form";
    }

    @PostMapping("/save")
    public String saveTransaction(
        @ModelAttribute Transaction transaction,
        @RequestParam(required = false) Long originalCategoryId, 
        @RequestParam Map<String, Object> filterParams, 
        RedirectAttributes redirectAttributes) {

        boolean isNewTransaction = transaction.getId() == null; 

        try {
            if (transaction.getCategoryEntity() != null) {
                transaction.setCategory(transaction.getCategoryEntity().getName());
            } else {
                transaction.setCategory(null);
            }

            transaction.setCategorizationStatus(TransactionCategorizationStatus.USER_CATEGORIZED);
            
            log.info("Going to save transaction...");
            log.info("T-ID {} | RAG Category (String): {} | Entity Set: {} | Account Set: {}", 
                     transaction.getId(), 
                     transaction.getCategory(), 
                     transaction.getCategoryEntity() != null ? transaction.getCategoryEntity().getName() : "NULL",
                     transaction.getAccount() != null ? transaction.getAccount().getName() : "NULL");

            transactionService.saveTransaction(transaction);
            if(transaction.getCategoryEntity() != null && (originalCategoryId == null || !transaction.getCategoryEntity().getId().equals(originalCategoryId))) {
                transactionService.updateVectorStore(transaction.getId(), transaction.getCategory());
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
