package com.everrich.spendmanager.controller;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.everrich.spendmanager.entities.Account;
import com.everrich.spendmanager.entities.Category;
import com.everrich.spendmanager.entities.CategorizationRule;
import com.everrich.spendmanager.entities.TransactionOperation;
import com.everrich.spendmanager.service.AccountService;
import com.everrich.spendmanager.service.CategoryService;
import com.everrich.spendmanager.service.CategorizationRuleService;

/**
 * Controller for managing categorization rules.
 * Provides UI for viewing, editing, and deleting rules used in table-based categorization.
 */
@Controller
@RequestMapping("/rules")
public class CategorizationRuleController {

    private static final Logger log = LoggerFactory.getLogger(CategorizationRuleController.class);
    private static final int DEFAULT_PAGE_SIZE = 20;

    private final CategorizationRuleService ruleService;
    private final AccountService accountService;
    private final CategoryService categoryService;

    public CategorizationRuleController(
            CategorizationRuleService ruleService,
            AccountService accountService,
            CategoryService categoryService) {
        this.ruleService = ruleService;
        this.accountService = accountService;
        this.categoryService = categoryService;
    }

    /**
     * Display the categorization rules management page.
     */
    @GetMapping
    public String listRules(
            @RequestParam(required = false) Long accountId,
            @RequestParam(required = false) String operation,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Model model) {
        
        // Parse operation filter
        TransactionOperation operationFilter = null;
        if (operation != null && !operation.isEmpty()) {
            try {
                operationFilter = TransactionOperation.valueOf(operation);
            } catch (IllegalArgumentException e) {
                log.warn("Invalid operation filter: {}", operation);
            }
        }
        
        // Create pageable with sorting
        Pageable pageable = PageRequest.of(page, size, Sort.by("account.name", "operation", "keywords"));
        
        // Fetch rules with filters
        Page<CategorizationRule> rulesPage = ruleService.findAllWithFilters(
                accountId, operationFilter, categoryId, search, pageable);
        
        // Load filter options
        List<Account> accounts = accountService.findAll();
        List<Category> categories = categoryService.findAll();
        
        // Add attributes to model
        model.addAttribute("appName", "EverRich");
        model.addAttribute("rulesPage", rulesPage);
        model.addAttribute("rules", rulesPage.getContent());
        model.addAttribute("accounts", accounts);
        model.addAttribute("categories", categories);
        model.addAttribute("operations", TransactionOperation.values());
        
        // Preserve filter values
        model.addAttribute("selectedAccountId", accountId);
        model.addAttribute("selectedOperation", operation);
        model.addAttribute("selectedCategoryId", categoryId);
        model.addAttribute("searchTerm", search);
        
        // Pagination info
        model.addAttribute("currentPage", page);
        model.addAttribute("totalPages", rulesPage.getTotalPages());
        model.addAttribute("totalElements", rulesPage.getTotalElements());
        
        // Feature flag status
        model.addAttribute("tableBasedEnabled", ruleService.isTableBasedEnabled());
        
        return "categorization-rules";
    }

    /**
     * Update a rule's keywords and/or category.
     */
    @PostMapping("/{id}/update")
    public String updateRule(
            @PathVariable Long id,
            @RequestParam(required = false) String keywords,
            @RequestParam(required = false) Long categoryId,
            RedirectAttributes redirectAttributes) {
        
        try {
            ruleService.updateRule(id, keywords, categoryId);
            redirectAttributes.addFlashAttribute("successMessage", "Rule updated successfully");
            log.info("Updated categorization rule: {}", id);
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Failed to update rule: " + e.getMessage());
            log.error("Failed to update rule {}: {}", id, e.getMessage());
        }
        
        return "redirect:/rules";
    }

    /**
     * Delete a rule.
     */
    @PostMapping("/{id}/delete")
    public String deleteRule(
            @PathVariable Long id,
            RedirectAttributes redirectAttributes) {
        
        try {
            ruleService.deleteRule(id);
            redirectAttributes.addFlashAttribute("successMessage", "Rule deleted successfully");
            log.info("Deleted categorization rule: {}", id);
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Failed to delete rule: " + e.getMessage());
            log.error("Failed to delete rule {}: {}", id, e.getMessage());
        }
        
        return "redirect:/rules";
    }

    /**
     * Create a new rule manually.
     */
    @PostMapping("/create")
    public String createRule(
            @RequestParam Long accountId,
            @RequestParam String operation,
            @RequestParam Long categoryId,
            @RequestParam String keywords,
            @RequestParam(required = false) String originalDescription,
            RedirectAttributes redirectAttributes) {
        
        try {
            TransactionOperation operationType = TransactionOperation.valueOf(operation);
            ruleService.createRuleManually(accountId, operationType, categoryId, keywords, originalDescription);
            redirectAttributes.addFlashAttribute("successMessage", "Rule created successfully");
            log.info("Created new categorization rule for account {}", accountId);
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Failed to create rule: " + e.getMessage());
            log.error("Failed to create rule: {}", e.getMessage());
        }
        
        return "redirect:/rules";
    }
}