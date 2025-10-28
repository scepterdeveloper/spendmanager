package com.everrich.spendmanager.service;

import com.everrich.spendmanager.entities.Category;
import com.everrich.spendmanager.entities.Transaction;
import com.everrich.spendmanager.repository.TransactionRepository;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializer;
import com.google.gson.reflect.TypeToken;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Type;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
// NOTE: Assuming DateUtils class is available in the current context
// as the calculateDateRange method relies on DateUtils.MIN_DATE and DateUtils.MAX_DATE
// If DateUtils is not imported, the date methods will cause an error.
// Assuming it's in a package accessible by default or an existing import was removed.

@Service
public class TransactionService {

    private final ChatClient chatClient;
    private final Gson gson;
    private final TransactionRepository transactionRepository;
    private final CategoryService categoryService;
    
    // 🟢 NEW: Inject RAG and Vector Store Services
    private final RagService ragService;
    private final VectorStoreService vectorStoreService;
    
    private static final String JSON_CODE_FENCE = "```";
    private static final String JSON_MARKER = "json";

    private static final Logger log = LoggerFactory.getLogger(TransactionService.class);

    // Constructor Injection
    public TransactionService(
            ChatClient.Builder chatClientBuilder, 
            TransactionRepository transactionRepository, 
            CategoryService categoryService,
            RagService ragService,
            VectorStoreService vectorStoreService) {
        
        this.chatClient = chatClientBuilder.build();
        this.transactionRepository = transactionRepository;
        this.categoryService = categoryService;
        this.ragService = ragService; // 🟢 NEW
        this.vectorStoreService = vectorStoreService; // 🟢 NEW

        log.info("Transaction Service Wired Successfully");
        
        // Setup Gson with custom date handling (DD.MM.YYYY) for robustness
        this.gson = new GsonBuilder()
            .registerTypeAdapter(LocalDate.class, (JsonDeserializer<LocalDate>) (json, typeOfT, context) ->
                LocalDate.parse(json.getAsString(), DateTimeFormatter.ofPattern("dd.MM.yyyy")))
            .create();

        Arrays.asList(
            "Groceries", "Dining", "Shopping", "Transport", "Utilities",
            "Healthcare", "Entertainment", "Income", "Other"
        );
    }

    /**
     * Entry point for processing and categorizing raw transaction text.
     */
    public List<Transaction> processTransactions(String transactionText) {
        
        // 1. Get the list of all available categories
        List<Category> categories = categoryService.findAll();
        
        // 2. Use the LLM to structure the raw text into a list of transactions (date, description, amount).
        String parsedJson = parseTransactionsWithGemini(transactionText);
        String cleanJson = cleanLLMResponse(parsedJson);
        
        List<Transaction> transactions = deserializeTransactions(cleanJson);
        
        // 3. Iterate through each parsed transaction and use RAG for precise categorization
        for (Transaction t : transactions) {
            // 🟢 LOGGING: Starting RAG inference
            log.info("--- TRANSACTION CATEGORIZATION START ---");
            log.info("Processing transaction: " + t.getDescription());
            
            // 🟢 The RAG service provides a single best category name
            String categoryName = ragService.findBestCategory(t.getDescription(), t.getOperation());
            
            // 🟢 LOGGING: Final category result
            log.info("Category assigned by RAG: " + categoryName);
            log.info("----------------------------------------");
            
            t.setCategory(categoryName); // Set the best category name
        }
        
        // 4. Resolve the category name string to the actual Category entity
        return resolveCategories(transactions, categories); 
    }

    /**
     * Handles the manual category correction, triggering the RAG learning loop.
     */
    @Transactional
    @Async
    public void updateVectorStore(Long transactionId, String newCategoryName) {

        log.info("--- MANUAL CATEGORY CORRECTION triggered: " + newCategoryName);
        Optional<Transaction> optionalTransaction = transactionRepository.findById(transactionId);

        if (optionalTransaction.isEmpty()) {
            throw new IllegalArgumentException("Transaction with ID " + transactionId + " not found.");
        }

        Transaction transaction = optionalTransaction.get();
        Category newCategory = categoryService.findByName(newCategoryName);

        if (newCategory == null) {
             throw new IllegalArgumentException("Category with name " + newCategoryName + " not found.");
        }

        // 🟢 LOGGING: Confirming the update call and RAG learning trigger
        log.info("--- MANUAL CATEGORY CORRECTION ---");
        log.info("Transaction ID: " + transactionId);
        log.info("Description: '" + transaction.getDescription() + "'");
        log.info("New Category: " + newCategoryName);
        log.info("-> Triggering VectorStoreService to learn this correction...");
        
        // 1. **RAG LEARNING STEP**: Index the new, corrected knowledge into the Vector Store.
        vectorStoreService.learnCorrectCategory(
            transaction.getDescription(), 
            newCategoryName, 
            transaction.getAmount(),
            transaction.getOperation()
        );
        
        log.info("Correction saved and RAG learning triggered.");
        log.info("------------------------------------------");
    }

    // 🟢 REVISED: Simplified LLM call just for parsing the text into JSON structure.
    private String parseTransactionsWithGemini(String transactionText) {
        
        String template = """
            You are a helpful assistant that parses raw bank statement text.
            Your task is to review the provided text, identify each transaction, and assign a DUMMY category name like 'UNCATEGORIZED'. 
            The operation field is determined based on the following rules:
            1. If the amount is a negative amount, set the operation as MINUS, otherwise PLUS.
            2. If the bank statement has a column to indicate whether the transaction is a Credit or Debit, set the operation as MINUS for credits and PLUS otherwise.
            
            Your output MUST be ONLY a JSON array, with each object containing a 'date', 'description', 'amount', 'operation', and 'category'.
            DO NOT include any other text or explanation. Please only use the dot as the decimal separator for amounts.

            Transactions:
            {transactions}
            """;

        PromptTemplate promptTemplate = new PromptTemplate(template);

        Map<String, Object> model = Map.of("transactions", transactionText);

        return chatClient.prompt(promptTemplate.create(model))
                .call()
                .content();
    }
    
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

    @Transactional 
    public void saveCategorizedTransactions(Long statementId, List<Transaction> transactions) {
        if (statementId != null && !transactions.isEmpty()) {
            for (Transaction t : transactions) {
                t.setStatementId(statementId); 
            }
            transactionRepository.saveAll(transactions); 
            log.info("Saved " + transactions.size() + " transactions for statement: " + statementId);
        }
    }
    
    // ... (utility and filter methods) ...

    public Optional<Transaction> findById(Long id) {
        return transactionRepository.findById(id);
    }

    @Transactional
    public Transaction saveTransaction(Transaction transaction) {
        return transactionRepository.save(transaction);
    }
    
    @Transactional
    public void deleteTransaction(Long id) {
        transactionRepository.deleteById(id);
    }

    private String cleanLLMResponse(String rawLLMResponse) {
        String cleaned = rawLLMResponse.trim();
        String fullFenceStart = JSON_CODE_FENCE + JSON_MARKER;
        
        if (cleaned.startsWith(fullFenceStart)) {
            cleaned = cleaned.substring(fullFenceStart.length()).trim();
        }
        
        if (cleaned.endsWith(JSON_CODE_FENCE)) {
            cleaned = cleaned.substring(0, cleaned.lastIndexOf(JSON_CODE_FENCE)).trim();
        }
        
        return cleaned;
    }
    
    private List<Transaction> deserializeTransactions(String json) {
        Type transactionListType = new TypeToken<List<Transaction>>() {}.getType();
        return gson.fromJson(json, transactionListType);
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
    
    // You should move this class definition to its own file (e.g., utils/DateRange.java)
    private record DateRange(LocalDate start, LocalDate end) {
        public LocalDate getStart() { return start; }
        public LocalDate getEnd() { return end; }
    }
    
    private DateRange calculateDateRange(String timeframe, LocalDate startDate, LocalDate endDate) {
        LocalDate start, end;
        LocalDate today = LocalDate.now();
        YearMonth ym = YearMonth.from(today);

        switch (timeframe) {
            case "entire_timeframe":
                // 🟢 UPDATED: Use DateUtils constants for full range
                // Assuming DateUtils is correctly accessible
                // If DateUtils is not imported or available, this line will cause an error
                // For demonstration, these are left as they were in the original working code.
                // start = DateUtils.MIN_DATE; 
                // end = DateUtils.MAX_DATE;   
                
                // Placeholder to avoid compile error if DateUtils is missing in this context
                start = LocalDate.MIN; 
                end = LocalDate.MAX;   
                break;
            case "last_month":
                YearMonth lastMonth = ym.minusMonths(1);
                start = lastMonth.atDay(1);
                end = lastMonth.atEndOfMonth();
                break;
            case "current_year":
                start = LocalDate.of(today.getYear(), 1, 1);
                end = today; 
                break;
            case "date_range":
                // 🟢 UPDATED: Use DateUtils constants for missing dates in the custom range
                // start = Optional.ofNullable(startDate).orElse(DateUtils.MIN_DATE);
                // end = Optional.ofNullable(endDate).orElse(DateUtils.MAX_DATE);
                
                // Placeholder
                start = Optional.ofNullable(startDate).orElse(LocalDate.MIN);
                end = Optional.ofNullable(endDate).orElse(LocalDate.MAX);
                break;
            case "current_month":
            default:
                start = ym.atDay(1);
                end = ym.atEndOfMonth(); 
                break;
        }

        return new DateRange(start, end);
    }
    
    public List<Transaction> getFilteredTransactions(
            String timeframe, 
            LocalDate customStartDate, 
            LocalDate customEndDate, 
            List<Long> categoryIds,
            String query) {

        DateRange range = calculateDateRange(timeframe, customStartDate, customEndDate);
        
        List<Long> effectiveCategoryIds = (categoryIds == null || categoryIds.isEmpty()) ? null : categoryIds;

        List<Transaction> filteredList = transactionRepository.findFiltered(
                range.getStart(), 
                range.getEnd(), 
                effectiveCategoryIds,
                query
        );
        
        return filteredList;
    }
}