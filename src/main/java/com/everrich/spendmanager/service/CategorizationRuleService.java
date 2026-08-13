package com.everrich.spendmanager.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.everrich.spendmanager.entities.Account;
import com.everrich.spendmanager.entities.Category;
import com.everrich.spendmanager.entities.CategorizationRule;
import com.everrich.spendmanager.entities.Transaction;
import com.everrich.spendmanager.entities.TransactionOperation;
import com.everrich.spendmanager.multitenancy.TenantContext;
import com.everrich.spendmanager.repository.AccountRepository;
import com.everrich.spendmanager.repository.CategorizationRuleRepository;
import com.everrich.spendmanager.repository.CategoryRepository;

/**
 * Service for managing categorization rules in the table-based categorization approach.
 * 
 * This service handles:
 * 1. Async rule learning (extracting keywords and creating/updating rules)
 * 2. Building guidance tables for LLM categorization
 * 3. CRUD operations for rules (for the management UI)
 */
@Service
public class CategorizationRuleService {

    private static final Logger log = LoggerFactory.getLogger(CategorizationRuleService.class);

    private final CategorizationRuleRepository ruleRepository;
    private final AccountRepository accountRepository;
    private final CategoryRepository categoryRepository;
    private final ChatClient chatClient;
    private final LlmRateLimiter llmRateLimiter;
    private final TransactionTemplate transactionTemplate;

    @Value("classpath:/prompts/extract-keywords-prompt.st")
    private Resource extractKeywordsPromptResource;

    @Value("${spendmanager.categorization.use-table-based:false}")
    private boolean useTableBased;

    public CategorizationRuleService(
            CategorizationRuleRepository ruleRepository,
            AccountRepository accountRepository,
            CategoryRepository categoryRepository,
            ChatClient.Builder chatClientBuilder,
            LlmRateLimiter llmRateLimiter,
            TransactionTemplate transactionTemplate) {
        this.ruleRepository = ruleRepository;
        this.accountRepository = accountRepository;
        this.categoryRepository = categoryRepository;
        this.chatClient = chatClientBuilder.build();
        this.llmRateLimiter = llmRateLimiter;
        this.transactionTemplate = transactionTemplate;
        log.info("CategorizationRuleService initialized (table-based enabled: {})", useTableBased);
    }

    /**
     * Check if table-based categorization is enabled.
     */
    public boolean isTableBasedEnabled() {
        return useTableBased;
    }

    // ============================================================
    // ASYNC RULE LEARNING
    // ============================================================

    /**
     * Asynchronously extracts keywords and creates/updates a categorization rule.
     * Called when a user manually categorizes a transaction.
     * 
     * This method runs in a background thread, so the user is not blocked.
     * Tenant context must be explicitly passed and set.
     * 
     * @param accountId The account ID
     * @param operation The transaction operation (PLUS/MINUS)
     * @param categoryId The category ID
     * @param description The transaction description
     * @param tenantId The tenant ID (captured before async call)
     */
    @Async("transactionProcessingExecutor")
    public void extractAndSaveRuleAsync(
            Long accountId,
            TransactionOperation operation,
            Long categoryId,
            String description,
            String tenantId) {
        
        log.info("Starting async rule extraction for tenant {} - account: {}, operation: {}", 
                tenantId, accountId, operation);
        
        try {
            // Set tenant context for the async thread
            TenantContext.setTenantId(tenantId);
            
            // Use TransactionTemplate for programmatic transaction management
            transactionTemplate.executeWithoutResult(status -> {
                extractAndSaveRuleInternal(accountId, operation, categoryId, description);
            });
            
            log.info("Async rule extraction completed for tenant {}", tenantId);
            
        } catch (Exception e) {
            log.error("Error in async rule extraction for tenant {}: {}", tenantId, e.getMessage(), e);
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * Internal method that does the actual keyword extraction and rule saving.
     * Must be called within a transaction context.
     */
    private void extractAndSaveRuleInternal(
            Long accountId,
            TransactionOperation operation,
            Long categoryId,
            String description) {
        
        // Step 1: Extract keywords using LLM
        String keywords = extractKeywordsFromDescription(description);
        
        if (keywords == null || keywords.isBlank()) {
            log.warn("Failed to extract keywords from description, skipping rule creation");
            return;
        }
        
        // Step 2: Check for existing rule with same account + operation + keywords
        Optional<CategorizationRule> existingRule = ruleRepository
                .findByAccountIdAndOperationAndKeywords(accountId, operation, keywords);
        
        if (existingRule.isPresent()) {
            CategorizationRule rule = existingRule.get();
            
            if (!rule.getCategory().getId().equals(categoryId)) {
                // Category changed - update the rule
                Category newCategory = categoryRepository.findById(categoryId)
                        .orElseThrow(() -> new RuntimeException("Category not found: " + categoryId));
                
                rule.setCategory(newCategory);
                rule.setUpdatedAt(LocalDateTime.now());
                ruleRepository.save(rule);
                
                log.info("Updated rule {} with new category: {} -> {}", 
                        rule.getId(), rule.getCategory().getName(), newCategory.getName());
            } else {
                // Same category - no action needed
                log.debug("Rule already exists with same category, skipping: {}", keywords);
            }
        } else {
            // Create new rule
            Account account = accountRepository.findById(accountId)
                    .orElseThrow(() -> new RuntimeException("Account not found: " + accountId));
            Category category = categoryRepository.findById(categoryId)
                    .orElseThrow(() -> new RuntimeException("Category not found: " + categoryId));
            
            CategorizationRule newRule = new CategorizationRule();
            newRule.setAccount(account);
            newRule.setOperation(operation);
            newRule.setCategory(category);
            newRule.setKeywords(keywords);
            newRule.setOriginalDescription(description);
            newRule.setCreatedAt(LocalDateTime.now());
            
            ruleRepository.save(newRule);
            log.info("Created new rule for account {} with keywords: {}", account.getName(), keywords);
        }
    }

    /**
     * Extracts meaningful keywords from a transaction description using LLM.
     * 
     * @param description The raw transaction description
     * @return Extracted keywords as comma-separated string, or null on failure
     */
    private String extractKeywordsFromDescription(String description) {
        if (description == null || description.isBlank()) {
            return null;
        }
        
        try {
            String result = llmRateLimiter.executeWithRetry(
                () -> {
                    PromptTemplate promptTemplate = new PromptTemplate(extractKeywordsPromptResource);
                    Map<String, Object> model = Map.of("description", description);
                    
                    return chatClient.prompt(promptTemplate.create(model))
                            .call()
                            .content()
                            .trim();
                },
                null // Return null on failure
            );
            
            if (result != null) {
                log.debug("Extracted keywords: {} -> {}", 
                        description.substring(0, Math.min(50, description.length())), result);
            }
            
            return result;
            
        } catch (Exception e) {
            log.error("Error extracting keywords from description: {}", e.getMessage());
            return null;
        }
    }

    // ============================================================
    // GUIDANCE TABLE GENERATION
    // ============================================================

    /**
     * Finds all rules for the given accounts and formats them as a guidance table.
     * Used when categorizing a batch of transactions.
     * 
     * @param transactions List of transactions to categorize
     * @return Formatted guidance table string for the LLM prompt
     */
    public String buildGuidanceTableForBatch(List<Transaction> transactions) {
        if (transactions == null || transactions.isEmpty()) {
            return "No historical categorization guidance available.";
        }
        
        // Collect unique account IDs
        Set<Long> accountIds = transactions.stream()
                .filter(t -> t.getAccount() != null)
                .map(t -> t.getAccount().getId())
                .collect(Collectors.toSet());
        
        if (accountIds.isEmpty()) {
            return "No historical categorization guidance available.";
        }
        
        // Fetch all rules for these accounts
        List<CategorizationRule> rules = ruleRepository.findByAccountIdIn(accountIds);
        
        return formatRulesAsGuidanceTable(rules);
    }

    /**
     * Finds all rules for a single transaction's account.
     * 
     * @param transaction The transaction to categorize
     * @return Formatted guidance table string for the LLM prompt
     */
    public String buildGuidanceTableForTransaction(Transaction transaction) {
        if (transaction == null || transaction.getAccount() == null) {
            return "No historical categorization guidance available.";
        }
        
        List<CategorizationRule> rules = ruleRepository.findByAccountId(transaction.getAccount().getId());
        return formatRulesAsGuidanceTable(rules);
    }

    /**
     * Formats a list of rules as a markdown guidance table.
     * 
     * @param rules List of categorization rules
     * @return Formatted table string
     */
    private String formatRulesAsGuidanceTable(List<CategorizationRule> rules) {
        if (rules == null || rules.isEmpty()) {
            return "No historical categorization guidance available.";
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append("| Account | Operation | Keywords | Category |\n");
        sb.append("|---------|-----------|----------|----------|\n");
        
        for (CategorizationRule rule : rules) {
            String accountName = rule.getAccount() != null ? rule.getAccount().getName() : "Unknown";
            String operationName = rule.getOperation() != null ? rule.getOperation().name() : "Unknown";
            String keywords = rule.getKeywords() != null ? rule.getKeywords() : "";
            String categoryName = rule.getCategory() != null ? rule.getCategory().getName() : "Unknown";
            
            // Truncate long keywords to keep table readable
            if (keywords.length() > 80) {
                keywords = keywords.substring(0, 77) + "...";
            }
            
            sb.append(String.format("| %s | %s | %s | %s |\n",
                    accountName, operationName, keywords, categoryName));
        }
        
        log.debug("Built guidance table with {} rules", rules.size());
        return sb.toString();
    }

    // ============================================================
    // CRUD OPERATIONS (for Management UI)
    // ============================================================

    /**
     * Get all rules with pagination and optional filters.
     */
    public Page<CategorizationRule> findAllWithFilters(
            Long accountId,
            TransactionOperation operation,
            Long categoryId,
            String searchTerm,
            Pageable pageable) {
        return ruleRepository.findWithFilters(accountId, operation, categoryId, searchTerm, pageable);
    }

    /**
     * Get all rules with pagination.
     */
    public Page<CategorizationRule> findAll(Pageable pageable) {
        return ruleRepository.findAll(pageable);
    }

    /**
     * Get a rule by ID.
     */
    public Optional<CategorizationRule> findById(Long id) {
        return ruleRepository.findById(id);
    }

    /**
     * Update a rule's keywords and/or category.
     */
    @Transactional
    public CategorizationRule updateRule(Long id, String keywords, Long categoryId) {
        CategorizationRule rule = ruleRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Rule not found: " + id));
        
        if (keywords != null && !keywords.isBlank()) {
            rule.setKeywords(keywords.trim());
        }
        
        if (categoryId != null) {
            Category category = categoryRepository.findById(categoryId)
                    .orElseThrow(() -> new RuntimeException("Category not found: " + categoryId));
            rule.setCategory(category);
        }
        
        rule.setUpdatedAt(LocalDateTime.now());
        return ruleRepository.save(rule);
    }

    /**
     * Delete a rule by ID.
     */
    @Transactional
    public void deleteRule(Long id) {
        if (!ruleRepository.existsById(id)) {
            throw new RuntimeException("Rule not found: " + id);
        }
        ruleRepository.deleteById(id);
        log.info("Deleted categorization rule: {}", id);
    }

    /**
     * Delete all rules for an account.
     */
    @Transactional
    public void deleteRulesByAccountId(Long accountId) {
        ruleRepository.deleteByAccountId(accountId);
        log.info("Deleted all categorization rules for account: {}", accountId);
    }

    /**
     * Delete all rules for a category.
     */
    @Transactional
    public void deleteRulesByCategoryId(Long categoryId) {
        ruleRepository.deleteByCategoryId(categoryId);
        log.info("Deleted all categorization rules for category: {}", categoryId);
    }

    /**
     * Get total rule count.
     */
    public long getRuleCount() {
        return ruleRepository.count();
    }

    /**
     * Get rule count by account.
     */
    public long getRuleCountByAccount(Long accountId) {
        return ruleRepository.countByAccountId(accountId);
    }

    /**
     * Create a rule manually (from UI).
     */
    @Transactional
    public CategorizationRule createRuleManually(
            Long accountId,
            TransactionOperation operation,
            Long categoryId,
            String keywords,
            String originalDescription) {
        
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new RuntimeException("Account not found: " + accountId));
        Category category = categoryRepository.findById(categoryId)
                .orElseThrow(() -> new RuntimeException("Category not found: " + categoryId));
        
        // Check for duplicate
        Optional<CategorizationRule> existing = ruleRepository
                .findByAccountIdAndOperationAndKeywords(accountId, operation, keywords);
        
        if (existing.isPresent()) {
            throw new RuntimeException("A rule with these keywords already exists for this account and operation");
        }
        
        CategorizationRule rule = new CategorizationRule();
        rule.setAccount(account);
        rule.setOperation(operation);
        rule.setCategory(category);
        rule.setKeywords(keywords.trim());
        rule.setOriginalDescription(originalDescription);
        rule.setCreatedAt(LocalDateTime.now());
        
        return ruleRepository.save(rule);
    }
}