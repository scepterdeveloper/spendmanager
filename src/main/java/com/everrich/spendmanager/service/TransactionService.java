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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.everrich.spendmanager.entities.Statement;
import com.everrich.spendmanager.entities.Account;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Type;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
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
    private final StatementService statementService; // Inject StatementService

    private final RagService ragService;
    private final VectorStoreService vectorStoreService;

    private static final String JSON_CODE_FENCE = "```";
    private static final String JSON_MARKER = "json";

    @Value("classpath:/prompts/parse-transactions-prompt.st")
    private Resource parseTransactionsPromptResource;

    private static final Logger log = LoggerFactory.getLogger(TransactionService.class);

    public TransactionService(
            ChatClient.Builder chatClientBuilder,
            TransactionRepository transactionRepository,
            CategoryService categoryService,
            @Lazy StatementService statementService, // Add StatementService to constructor with @Lazy
            RagService ragService,
            VectorStoreService vectorStoreService) {

        this.chatClient = chatClientBuilder.build();
        this.transactionRepository = transactionRepository;
        this.categoryService = categoryService;
        this.statementService = statementService; // Initialize StatementService
        this.ragService = ragService;
        this.vectorStoreService = vectorStoreService;

        log.info("Transaction Service Wired Successfully");

        // Setup Gson with custom date handling (DD.MM.YYYY) for robustness
        this.gson = new GsonBuilder()
                .registerTypeAdapter(LocalDate.class,
                        (JsonDeserializer<LocalDate>) (json, typeOfT, context) -> LocalDate.parse(json.getAsString(),
                                DateTimeFormatter.ofPattern("dd.MM.yyyy")))
                .create();

        Arrays.asList(
                "Groceries", "Dining", "Shopping", "Transport", "Utilities",
                "Healthcare", "Entertainment", "Income", "Other");
    }

    public List<Transaction> processTransactions(String transactionText) {

        log.info("==============================================================================");
        LocalDateTime processingStartTime = LocalDateTime.now();
        Duration processingDuration = null;
        log.info(LocalDateTime.now() + ": Statement Processing START");
        log.info("Fetched categories");
        // 1. Get the list of all available categories
        List<Category> categories = categoryService.findAll();
        log.info("Parsing and cleaning START");
        // 2. Use the LLM to structure the raw text into a list of transactions (date,
        // description, amount).
        String parsedJson = parseTransactionsWithGemini(transactionText);
        log.info("Parsing Done");
        String cleanJson = cleanLLMResponse(parsedJson);
        log.info("Cleaning Done");
        List<Transaction> transactions = deserializeTransactions(cleanJson);
        log.info("Transactions Deserialized");

        if (transactions != null)
            log.info(LocalDateTime.now() + ": No. of trasactions parsed - " + transactions.size());

        // 3. Iterate through each parsed transaction and use RAG for precise
        // categorization
        for (Transaction t : transactions) {

            log.info("------------------------------------------------------------------------------");
            log.info(LocalDateTime.now() + ": Resolve Category (with RAG-LLM) START");
            LocalDateTime resolveCategorytartTime = LocalDateTime.now();
            String categoryName = ragService.findBestCategory(t.getDescription(), t.getOperation());
            t.setCategory(categoryName);
            processingDuration = Duration.between(resolveCategorytartTime, LocalDateTime.now());
            long durationInSeconds = processingDuration.toSeconds();
            long durationInMilliseconds = processingDuration.toMillis();
            log.info(
                    "Time taken for transaction with description " + t.getDescription() + " - " + durationInSeconds
                            + " Seconds ("
                            + durationInMilliseconds
                            + " Milliseconds)");

            log.info("------------------------------------------------------------------------------");
            log.info(LocalDateTime.now() + ": Resolve Category (with RAG-LLM) END");

        }

        log.info(LocalDateTime.now() + ":  Resolve Category (with RAG-LLM) finished for " + transactions.size()
                + " transactions");

        processingDuration = Duration.between(processingStartTime, LocalDateTime.now());
        long durationInSeconds = processingDuration.toSeconds();
        long durationInMilliseconds = processingDuration.toMillis();

        log.info("Total Time Elapsed from processing START to RAG-LLM Finished - " + durationInSeconds + " Seconds ("
                + durationInMilliseconds + " Milliseconds)");
        List<Transaction> processedTransactions = resolveCategories(transactions, categories);
        log.info("==============================================================================");

        return processedTransactions;
    }

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
        vectorStoreService.learnCorrectCategory(
                transaction.getDescription(),
                newCategoryName,
                transaction.getAmount(),
                transaction.getOperation());
    }

    // 🟢 REVISED: Simplified LLM call just for parsing the text into JSON
    // structure.
    private String parseTransactionsWithGemini(String transactionText) {

        PromptTemplate promptTemplate = new PromptTemplate(parseTransactionsPromptResource);
        Map<String, Object> model = Map.of("transactions", transactionText);
        log.info("Going to call LLM for parsing");
        String LLMOutput = chatClient.prompt(promptTemplate.create(model))
                .call()
                .content();
        log.info("LLM Output: " + LLMOutput);
        return LLMOutput;
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
            transactionRepository.saveAll(transactions);
            log.info("Saved " + transactions.size() + " transactions for statement: " + statementId + " and account: "
                    + account.getName());
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
        Type transactionListType = new TypeToken<List<Transaction>>() {
        }.getType();
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

    // You should move this class definition to its own file (e.g.,
    // utils/DateRange.java)
    private record DateRange(LocalDate start, LocalDate end) {
        public LocalDate getStart() {
            return start;
        }

        public LocalDate getEnd() {
            return end;
        }
    }

    private DateRange calculateDateRange(String timeframe, LocalDate startDate, LocalDate endDate) {
        LocalDate start, end;
        LocalDate today = LocalDate.now();
        YearMonth ym = YearMonth.from(today);

        switch (timeframe) {
            case "entire_timeframe":
                start = LocalDate.of(1900, 1, 1);
                end = LocalDate.of(9999, 12, 31);
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
            case "previous_year":
                LocalDate now = LocalDate.now();
                start = now.minusYears(1).with(TemporalAdjusters.firstDayOfYear());
                end = now.minusYears(1).with(TemporalAdjusters.lastDayOfYear());
                break;

            case "date_range":
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
                query);

        return filteredList;
    }
}
