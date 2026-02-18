package com.everrich.spendmanager.service;

import com.everrich.spendmanager.entities.Category;
import com.everrich.spendmanager.entities.Transaction;
import com.everrich.spendmanager.entities.TransactionCategorizationStatus;
import com.everrich.spendmanager.repository.TransactionRepository;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.scheduling.annotation.Async;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.everrich.spendmanager.entities.Statement;
import com.everrich.spendmanager.entities.Account;
import com.everrich.spendmanager.entities.TransactionOperation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.temporal.TemporalAdjusters;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import com.everrich.spendmanager.multitenancy.TenantContext;

@Service
public class TransactionService {

    private final ChatClient chatClient;

    private final TransactionRepository transactionRepository;
    private final CategoryService categoryService;
    private final StatementService statementService; // Inject StatementService

    private final RagService ragService;
    private final VectorStoreService vectorStoreService;
    private final AccountBalanceService accountBalanceService;

    private static final Logger log = LoggerFactory.getLogger(TransactionService.class);

    public TransactionService(
            ChatClient.Builder chatClientBuilder,
            TransactionRepository transactionRepository,
            CategoryService categoryService,
            @Lazy StatementService statementService, // Add StatementService to constructor with @Lazy
            RagService ragService,
            VectorStoreService vectorStoreService,
            AccountBalanceService accountBalanceService) {

        this.chatClient = chatClientBuilder.build();
        this.transactionRepository = transactionRepository;
        this.categoryService = categoryService;
        this.statementService = statementService; // Initialize StatementService
        this.ragService = ragService;
        this.vectorStoreService = vectorStoreService;
        this.accountBalanceService = accountBalanceService;

        log.info("Transaction Service Wired Successfully");

        // Setup Gson with custom date handling (DD.MM.YYYY) for robustness

        Arrays.asList(
                "Groceries", "Dining", "Shopping", "Transport", "Utilities",
                "Healthcare", "Entertainment", "Income", "Other");
    }

    // @Async("transactionProcessingExecutor")
    public Transaction categorizeTransaction(Transaction transaction) {

        log.info("------------------------------------------------------------------------------");
        log.info("Resolve Category (with RAG-LLM): START");
        String categoryName = ragService.findBestCategory(transaction);
        log.info("Transaction: " + transaction.getDescription());
        log.info("Resolved Category: " + categoryName);
        transaction.setCategory(categoryName);
        transaction.setCategoryEntity(categoryService.findByName(categoryName));
        transaction.setCategorizationStatus(TransactionCategorizationStatus.LLM_CATEGORIZED);
        log.info("Resolve Category (with RAG-LLM): DONE");
        log.info("Saving transaction: START");
        transactionRepository.save(transaction);
        log.info("Saving transaction: DONE");
        log.info("------------------------------------------------------------------------------");
        return transaction;
    }

    /*
     * public List<Transaction> processTransactions(List<Transaction> transactions)
     * {
     * 
     * if (transactions != null)
     * log.info(LocalDateTime.now() + ": No. of trasactions parsed - " +
     * transactions.size());
     * 
     * log.info(
     * "=============================================================================="
     * );
     * List<Category> categories = categoryService.findAll();
     * 
     * for (Transaction transaction : transactions) {
     * 
     * log.info(
     * "------------------------------------------------------------------------------"
     * );
     * log.info("Resolve Category (with RAG-LLM) START");
     * String categoryName =
     * ragService.findBestCategory(transaction.getDescription(),
     * transaction.getOperation());
     * log.info("Transaction: " + transaction.getDescription());
     * log.info("Resolved Category: " + categoryName);
     * transaction.setCategory(categoryName);
     * log.info("Resolve Category (with RAG-LLM) END");
     * log.info(
     * "------------------------------------------------------------------------------"
     * );
     * }
     * 
     * log.info("Resolve Category (with RAG-LLM): Done - " + transactions.size() +
     * " transaction(s)");
     * List<Transaction> processedTransactions = resolveCategories(transactions,
     * categories);
     * log.info(
     * "=============================================================================="
     * );
     * 
     * return processedTransactions;
     * }
     */

    /**
     * Handles the manual category correction, triggering the RAG learning loop.
     */
    @Transactional
    @Async
    public void updateVectorStore(Long transactionId, String newCategoryName) {

        Optional<Transaction> optionalTransaction = transactionRepository.findById(transactionId);
        if (optionalTransaction.isEmpty()) {
            throw new IllegalArgumentException("Transaction with ID " + transactionId + " not found.");
        }

        Transaction transaction = optionalTransaction.get();
        Category newCategory = categoryService.findByName(newCategoryName);

        if (newCategory == null) {
            throw new IllegalArgumentException("Category with name " + newCategoryName + " not found.");
        }
        // 1. **RAG LEARNING STEP**: Index the new, corrected knowledge into the Vector
        // Store.
        String accountName = (transaction.getAccount() != null) ? transaction.getAccount().getName() : "Unknown Account";
        log.warn("Account is null for transaction ID {}. Using '{}' as account name for vector store.", transactionId, accountName);
        vectorStoreService.learnCorrectCategory(
                transaction.getDescription(),
                newCategoryName,
                transaction.getAmount(),
                transaction.getOperation(),
                accountName);
    }

    // 🟢 REVISED: Simplified LLM call just for parsing the text into JSON
    // structure.

    private List<Transaction> resolveCategories(List<Transaction> transactions, List<Category> availableCategories) {
        Map<String, Category> categoryMap = availableCategories.stream()
                .collect(Collectors.toMap(c -> c.getName().toLowerCase(), c -> c));

        for (Transaction t : transactions) {
            String categoryName = t.getCategory();

            // Use the RAG-determined category name to get the entity
            Category resolvedCategory = categoryMap.getOrDefault(categoryName.toLowerCase(),
                    categoryService.findByName("Other"));

            t.setCategoryEntity(resolvedCategory);
        }
        return transactions;
    }

    // ... (rest of the service methods remain the same) ...

    public void saveCategorizedTransactions(Long statementId, List<Transaction> transactions) {
        if (statementId != null && !transactions.isEmpty()) {
            Statement statement = statementService.getStatementById(statementId);
            if (statement == null) {
                log.error("Statement with ID {} not found. Cannot save transactions.", statementId);
                return;
            }
            Account account = statement.getAccount();
            if (account == null) {
                log.error("Account not associated with statement ID {}. Cannot save transactions.", statementId);
                return;
            }

            for (Transaction t : transactions) {
                t.setStatementId(statementId);
                t.setAccount(account); // Set the account for each transaction
            }
            // Delegate to a transactional method for actual saving
            performSaveAllTransactions(transactions, statementId, account);
        }
    }

    @Transactional
    private void performSaveAllTransactions(List<Transaction> transactions, Long statementId, Account account) {
        transactionRepository.saveAll(transactions);
        log.info("Saved " + transactions.size() + " transactions for statement: " + statementId + " and account: "
                + account.getName());
    }

    // ... (utility and filter methods) ...

    public Optional<Transaction> findById(Long id) {
        return transactionRepository.findById(id);
    }

    @Transactional
    public Transaction saveTransaction(Transaction transaction) {
        boolean isNew = transaction.getId() == null;
        Transaction oldTransaction = null;
        
        // If updating, capture the old state for balance adjustment
        if (!isNew) {
            oldTransaction = transactionRepository.findById(transaction.getId()).orElse(null);
            if (oldTransaction != null) {
                // Create a copy of relevant fields for balance comparison
                oldTransaction = copyTransactionForBalanceComparison(oldTransaction);
            }
        }
        
        Transaction savedTransaction = transactionRepository.save(transaction);
        
        // Trigger async balance update
        String tenantId = TenantContext.getTenantId();
        if (isNew) {
            accountBalanceService.processTransactionCreateAsync(savedTransaction, tenantId);
        } else if (oldTransaction != null) {
            accountBalanceService.processTransactionUpdateAsync(oldTransaction, savedTransaction, tenantId);
        }
        
        return savedTransaction;
    }
    
    /**
     * Creates a shallow copy of transaction with fields needed for balance comparison.
     */
    private Transaction copyTransactionForBalanceComparison(Transaction original) {
        Transaction copy = new Transaction();
        copy.setId(original.getId());
        copy.setDate(original.getDate());
        copy.setAmount(original.getAmount());
        copy.setOperation(original.getOperation());
        copy.setAccount(original.getAccount());
        return copy;
    }

    @Transactional
    public boolean updateReviewedStatus(Long transactionId, boolean reviewed) {
        Optional<Transaction> optionalTransaction = transactionRepository.findById(transactionId);
        if (optionalTransaction.isPresent()) {
            Transaction transaction = optionalTransaction.get();
            transaction.setReviewed(reviewed);
            transactionRepository.save(transaction);
            log.info("Updated reviewed status for transaction ID {} to {}", transactionId, reviewed);
            return true;
        }
        log.warn("Transaction with ID {} not found for reviewed status update", transactionId);
        return false;
    }

    @Transactional
    public void saveAllTransactions(List<Transaction> transactions) {
        transactionRepository.saveAll(transactions);
    }

    @Transactional
    public void deleteTransaction(Long id) {
        // Get the transaction before deletion for balance adjustment
        Optional<Transaction> optionalTransaction = transactionRepository.findById(id);
        if (optionalTransaction.isPresent()) {
            Transaction transaction = optionalTransaction.get();
            String tenantId = TenantContext.getTenantId();
            
            // Delete the transaction first
            transactionRepository.deleteById(id);
            
            // Then trigger async balance removal
            accountBalanceService.processTransactionDeleteAsync(transaction, tenantId);
        } else {
            transactionRepository.deleteById(id);
        }
    }

    /**
     * Retrieves all transactions associated with a specific statement ID.
     * This method was present in the original TransactionService and is needed
     * to resolve the regression error.
     */
    public List<Transaction> getTransactionsByStatementId(Long statementId) {
        if (statementId == null) {
            return List.of();
        }
        return transactionRepository.findByStatementId(statementId);
    }

    // DateRange record using LocalDateTime for timestamp-based queries
    private record DateTimeRange(LocalDateTime start, LocalDateTime end) {
        public LocalDateTime getStart() {
            return start;
        }

        public LocalDateTime getEnd() {
            return end;
        }
    }

    private DateTimeRange calculateDateTimeRange(String timeframe, LocalDate startDate, LocalDate endDate) {
        LocalDateTime start, end;
        LocalDate today = LocalDate.now();
        YearMonth ym = YearMonth.from(today);

        switch (timeframe) {
            case "entire_timeframe":
                start = LocalDateTime.of(1900, 1, 1, 0, 0, 0);
                end = LocalDateTime.of(9999, 12, 31, 23, 59, 59);
                break;
            case "last_month":
                YearMonth lastMonth = ym.minusMonths(1);
                start = lastMonth.atDay(1).atStartOfDay();
                end = lastMonth.atEndOfMonth().atTime(LocalTime.MAX);
                break;
            case "current_year":
                start = LocalDate.of(today.getYear(), 1, 1).atStartOfDay();
                end = today.atTime(LocalTime.MAX);
                break;
            case "previous_year":
                LocalDate now = LocalDate.now();
                start = now.minusYears(1).with(TemporalAdjusters.firstDayOfYear()).atStartOfDay();
                end = now.minusYears(1).with(TemporalAdjusters.lastDayOfYear()).atTime(LocalTime.MAX);
                break;

            case "date_range":
                start = Optional.ofNullable(startDate).map(LocalDate::atStartOfDay).orElse(LocalDateTime.MIN);
                end = Optional.ofNullable(endDate).map(d -> d.atTime(LocalTime.MAX)).orElse(LocalDateTime.MAX);
                break;
            case "current_month":
            default:
                start = ym.atDay(1).atStartOfDay();
                end = ym.atEndOfMonth().atTime(LocalTime.MAX);
                break;
        }

        return new DateTimeRange(start, end);
    }

    public List<Transaction> getFilteredTransactions(
            String timeframe,
            LocalDate customStartDate,
            LocalDate customEndDate,
            List<Long> accountIds,
            List<Long> categoryIds,
            String query) {

        DateTimeRange range = calculateDateTimeRange(timeframe, customStartDate, customEndDate);

        List<Long> effectiveAccountIds = (accountIds == null || accountIds.isEmpty()) ? null : accountIds;
        List<Long> effectiveCategoryIds = (categoryIds == null || categoryIds.isEmpty()) ? null : categoryIds;

        List<Transaction> filteredList = transactionRepository.findFiltered(
                range.getStart(),
                range.getEnd(),
                effectiveAccountIds,
                effectiveCategoryIds,
                query);

        return filteredList;
    }

    /**
     * Calculate total credits (PLUS operations) from a list of transactions.
     */
    public BigDecimal calculateTotalCredits(List<Transaction> transactions) {
        if (transactions == null || transactions.isEmpty()) {
            return BigDecimal.ZERO;
        }
        double sum = transactions.stream()
                .filter(t -> t.getOperation() == TransactionOperation.PLUS)
                .map(Transaction::getAmount)
                .filter(amount -> amount != null)
                .mapToDouble(Double::doubleValue)
                .sum();
        return BigDecimal.valueOf(sum);
    }

    /**
     * Calculate total debits (MINUS operations) from a list of transactions.
     */
    public BigDecimal calculateTotalDebits(List<Transaction> transactions) {
        if (transactions == null || transactions.isEmpty()) {
            return BigDecimal.ZERO;
        }
        double sum = transactions.stream()
                .filter(t -> t.getOperation() == TransactionOperation.MINUS)
                .map(Transaction::getAmount)
                .filter(amount -> amount != null)
                .mapToDouble(Double::doubleValue)
                .sum();
        return BigDecimal.valueOf(sum);
    }
}
