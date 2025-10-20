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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Type;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class TransactionService {

    private final ChatClient chatClient;
    private final Gson gson;
    private final TransactionRepository transactionRepository;
    private final CategoryService categoryService;

    
    private static final String JSON_CODE_FENCE = "```";
    private static final String JSON_MARKER = "json";

    // Constructor Injection
    public TransactionService(ChatClient.Builder chatClientBuilder, TransactionRepository transactionRepository, CategoryService categoryService) {
        this.chatClient = chatClientBuilder.build();
        this.transactionRepository = transactionRepository;
        this.categoryService = categoryService;
        
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

    public List<Transaction> processTransactions(String transactionText) {
        List<Category> categories = categoryService.findAll();
        String categorizedJson = categorizeTransactionsWithGemini(transactionText, categories);
        String cleanJson = cleanLLMResponse(categorizedJson);
        
        List<Transaction> transactions = deserializeTransactions(cleanJson);
        
        return resolveCategories(transactions, categories); 
    }

    private List<Transaction> resolveCategories(List<Transaction> transactions, List<Category> availableCategories) {
        Map<String, Category> categoryMap = availableCategories.stream()
                .collect(Collectors.toMap(c -> c.getName().toLowerCase(), c -> c));

        for (Transaction t : transactions) {
            String categoryName = t.getCategory(); 
            
            Category resolvedCategory = categoryMap.getOrDefault(categoryName.toLowerCase(), 
                                                                categoryService.findByName("Other"));
            
            t.setCategoryEntity(resolvedCategory);
        }
        return transactions;
    }


    private String categorizeTransactionsWithGemini(String transactionText, List<Category> categories) {
        
        // 1. Build a clear category list string for the LLM
        String categoryList = categories.stream()
            .map(c -> String.format("%s (Description: %s)", c.getName(), c.getDescription()))
            .collect(Collectors.joining("; "));
        
        String template = """
            You are a helpful assistant that categorizes financial transactions.
            Your task is to review the provided text from a bank statement, identify each transaction, and assign it to one of the following categories, 
            strictly using the exact category NAME provided. Consider the description of the categories to gather more context information to do a categorization 
            as precise as possible. If you realize that a minus symbol is used in the bank statement to indicate amounts of outgoing transactions (expense), please
            convert it to a positive number while creating the transaction. 
            
            Available Categories:
            {category_list}
            
            Your output MUST be ONLY a JSON array, with each object containing a 'date', 'description', 'amount', and 'category'.
            DO NOT include any other text or explanation in your response. Please only use the dot as the decimal separator format for amounts.

            Transactions:
            {transactions}
            """;

        PromptTemplate promptTemplate = new PromptTemplate(template);

        Map<String, Object> model = Map.of(
            "category_list", categoryList, 
            "transactions", transactionText
        );

        return chatClient.prompt(promptTemplate.create(model))
                .call()
                .content();
    }

    @Transactional 
    public void saveCategorizedTransactions(Long statementId, List<Transaction> transactions) {
        if (statementId != null && !transactions.isEmpty()) {
            for (Transaction t : transactions) {
                t.setStatementId(statementId); 
            }
            transactionRepository.saveAll(transactions); 
            System.out.println("Saved " + transactions.size() + " transactions for statement: " + statementId);
        }
    }
    
    public List<Transaction> getTransactionsByStatementId(Long statementId) {
        if (statementId == null) {
            return List.of();
        }
        return transactionRepository.findByStatementId(statementId); 
    }    
    
    private DateRange calculateDateRange(String timeframe, LocalDate startDate, LocalDate endDate) {
        LocalDate start, end;
        LocalDate today = LocalDate.now();
        YearMonth ym = YearMonth.from(today);

        switch (timeframe) {
            case "entire_timeframe":
                // 🟢 UPDATED: Use DateUtils constants for full range
                start = DateUtils.MIN_DATE; 
                end = DateUtils.MAX_DATE;   
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
                start = Optional.ofNullable(startDate).orElse(DateUtils.MIN_DATE);
                end = Optional.ofNullable(endDate).orElse(DateUtils.MAX_DATE);
                break;
            case "current_month":
            default:
                start = ym.atDay(1);
                end = ym.atEndOfMonth(); 
                break;
        }

        return new DateRange(start, end);
    }

    /**
     * Fetches transactions based on the full set of filter criteria.
     * Includes the logic for date range calculation and passing the search query.
     */
    public List<Transaction> getFilteredTransactions(
            String timeframe, 
            LocalDate customStartDate, 
            LocalDate customEndDate, 
            List<Long> categoryIds,
            String query) {

        // 1. Calculate the required date range
        DateRange range = calculateDateRange(timeframe, customStartDate, customEndDate);
        
        // 2. Determine effective category IDs for the query
        List<Long> effectiveCategoryIds = (categoryIds == null || categoryIds.isEmpty()) ? null : categoryIds;

        // 3. Fetch data from the repository, including the query
        List<Transaction> filteredList = transactionRepository.findFiltered(
                range.getStart(), 
                range.getEnd(), 
                effectiveCategoryIds,
                query
        );
        
        return filteredList;
    }
    
    // ==========================================================
    //      FIXES FOR TransactionController ERRORS START HERE
    // ==========================================================

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

    // ==========================================================
    //      FIXES FOR TransactionController ERRORS END HERE
    // ==========================================================


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
}