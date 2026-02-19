package com.everrich.spendmanager.service;

import com.everrich.spendmanager.entities.Account;
import com.everrich.spendmanager.entities.Statement;
import com.everrich.spendmanager.entities.StatementStatus;
import com.everrich.spendmanager.entities.Transaction;
import com.everrich.spendmanager.entities.TransactionCategorizationStatus;
import com.everrich.spendmanager.repository.StatementRepository;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializer;
import com.google.gson.reflect.TypeToken;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import java.lang.reflect.Type;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class StatementService {

    private final StatementRepository statementRepository;
    private final PdfProcessor pdfProcessor;
    private final TransactionService transactionService;
    private final CategoryService categoryService;
    private final ChatClient chatClient;
    private final Gson gson;

    private static final Logger log = LoggerFactory.getLogger(StatementService.class);

    @Value("classpath:/prompts/parse-transactions-prompt.st")
    private Resource parseTransactionsPromptResource;

    private static final String JSON_CODE_FENCE = "```";
    private static final String JSON_MARKER = "json";

    public StatementService(
            StatementRepository statementRepository,
            ChatClient.Builder chatClientBuilder,
            CategoryService categoryService,
            PdfProcessor pdfProcessor,
            @Lazy TransactionService transactionService) { // Add @Lazy

        this.statementRepository = statementRepository;
        this.pdfProcessor = pdfProcessor;
        this.transactionService = transactionService;
        this.categoryService = categoryService;
        this.chatClient = chatClientBuilder.build();

        this.gson = new GsonBuilder()
                .registerTypeAdapter(LocalDate.class,
                        (JsonDeserializer<LocalDate>) (json, typeOfT, context) -> LocalDate.parse(json.getAsString(),
                                DateTimeFormatter.ofPattern("dd.MM.yyyy")))
                .create();

    }

    public Statement createInitialStatement(String fileName, Account account, byte[] content) {
        Statement statement = new Statement();
        statement.setOriginalFileName(fileName);
        statement.setUploadDateTime(LocalDateTime.now());
        statement.setStatus(StatementStatus.OPEN); 
        statement.setAccount(account); 
        statement.setContent(content);
        return statementRepository.save(statement);
    }

    /**
     * Retrieves all Statement records from the database.
     */
    public List<Statement> getAllStatements() {
        // REPLACEMENT: Use JpaRepository's findAll()
        return statementRepository.findAll();
    }

    /**
     * Retrieves a single Statement by its ID.
     */
    public Statement getStatementById(Long id) {
        // REPLACEMENT: Use JpaRepository's findById() and handle the Optional
        // Returns null if not found, matching the old signature.
        return statementRepository.findById(id).orElse(null);
    }

    public List<Statement> getOpenStatements() {
        return statementRepository.findByStatus(StatementStatus.OPEN);
    }

    public List<Statement> getStatementsBeingCategorized() {
        return statementRepository.findByStatus(StatementStatus.CATEGORIZING);
    }
    
    /**
     * Gets all statements with the specified status.
     */
    public List<Statement> getStatementsByStatus(StatementStatus status) {
        return statementRepository.findByStatus(status);
    }

    public void saveStatement(Statement statement)  {
        statementRepository.save(statement);
    }

    private String parseTransactionsWithGemini(String transactionText) {

        PromptTemplate promptTemplate = new PromptTemplate(parseTransactionsPromptResource);
        Map<String, Object> model = Map.of("transactions", transactionText);
        log.info("Going to call LLM for parsing");
        Prompt prompt = promptTemplate.create(model);
        log.info("-------------------Prompt to PARSE----------------");
        log.info(prompt.getContents());
        log.info("--------------------------------------------------");
        String LLMOutput = chatClient.prompt(prompt)
                .call()
                .content();
        return LLMOutput;
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

    public List<Transaction> extractTransactionsFromPdf(Long statementId, byte[] fileBytes) {

        log.info("Extracting transactions from PDF (LLM Based)");
        Optional<Statement> statementOptional = statementRepository.findById(statementId);
        if (statementOptional.isEmpty()) {
            log.error("Statement not found for ID: " + statementId);
            return null;
        }

        Statement statement = statementOptional.get();
        try {

            String extractedText = pdfProcessor.extractTextFromPdf(fileBytes);
            log.info("Extract Text from PDF: DONE");
            String parsedJson = parseTransactionsWithGemini(extractedText);
            String cleanJson = cleanLLMResponse(parsedJson);
            log.info("--------------------Parsed JSON from PDF->LLM------------------------");
            log.info(cleanJson);
            log.info("---------------------------------------------------------------------");
            List<Transaction> transactions = deserializeTransactions(cleanJson);
            log.info("Parse-clean (LLM Based) and deserialized transactions: DONE - " + transactions.size()
                    + " transaction(s)");

            return transactions;
        } catch (Exception e) {
            log.error("Error while processing PDF to extract transactions", e);
            statement.setStatus(StatementStatus.FAILED);
            statementRepository.save(statement);
            return null;
        }
    }

    public void categorizeTransactions(Long statementId, List<Transaction> transactions) {

        log.info("Resolving categories (LLM Based)");
        Optional<Statement> statementOptional = statementRepository.findById(statementId);

        if (statementOptional.isEmpty()) {
            log.error("Statement not found for ID: " + statementId);
            return;
        }

        Statement statement = statementOptional.get();

        for (Transaction transaction : transactions) {

            transaction.setStatementId(statementId);
            transaction.setAccount(statement.getAccount());
            transaction.setCategorizationStatus(TransactionCategorizationStatus.NOT_CATEGORIZED);
            transactionService.saveTransaction(transaction);
            transactionService.categorizeTransaction(transaction);
        }

        statement.setStatus(StatementStatus.CATEGORIZING);
        statementRepository.save(statement);
    }

    /*public void resolveCategories(Long statementId, List<Transaction> transactions) {

        log.info("Resolving categories (LLM Based)");
        Optional<Statement> statementOptional = statementRepository.findById(statementId);

        if (statementOptional.isEmpty()) {
            log.error("Statement not found for ID: " + statementId);
            return;
        }

        Statement statement = statementOptional.get();

        try {

            transactionService.saveCategorizedTransactions(statementId,
                    transactionService.processTransactions(transactions));
            statement.setStatus(StatementStatus.COMPLETED);
            statementRepository.save(statement);
            log.info("Resolve Categories: DONE");

        } catch (Exception e) {
            log.error("Error while resolving categories", e);
            statement.setStatus(StatementStatus.FAILED);
            statementRepository.save(statement);
        }
    }*/
}
