package com.everrich.spendmanager.service;

import java.lang.reflect.Type;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.everrich.spendmanager.entities.Registration;
import com.everrich.spendmanager.entities.RegistrationStatus;
import com.everrich.spendmanager.entities.Statement;
import com.everrich.spendmanager.entities.StatementStatus;
import com.everrich.spendmanager.entities.Transaction;
import com.everrich.spendmanager.entities.TransactionCategorizationStatus;
import com.everrich.spendmanager.multitenancy.TenantContext;
import com.everrich.spendmanager.repository.RegistrationRepository;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.TypeAdapter;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;

/**
 * Processes PDF statements to extract transactions.
 * 
 * Transaction Processing:
 * - The entire statement processing (transaction creation + balance updates) is done
 *   within a single database transaction
 * - If the application crashes during processing, the transaction is rolled back
 * - On startup, any statements stuck in PROCESSING status are reset to OPEN for reprocessing
 */
@Component
public class StatementProcessor {

    private final StatementService statementService;
    private final TransactionService transactionService;
    private final ChatClient chatClient;
    private final PdfProcessor pdfProcessor;
    private final RegistrationRepository registrationRepository;
    private static final Logger log = LoggerFactory.getLogger(StatementProcessor.class);
    @Value("classpath:/prompts/parse-transactions-prompt.st")
    private Resource parseTransactionsPromptResource;
    private final Gson gson;
    private static final String JSON_CODE_FENCE = "```";
    private static final String JSON_MARKER = "json";
    
    // Multiple date formatters to handle various LLM output formats
    private static final List<DateTimeFormatter> DATE_FORMATTERS = Arrays.asList(
            DateTimeFormatter.ofPattern("dd.MM.yyyy"),           // Primary format: 02.12.2025
            DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH),  // 02 Dec 2025
            DateTimeFormatter.ofPattern("dd-MM-yyyy"),           // 02-12-2025
            DateTimeFormatter.ofPattern("yyyy-MM-dd"),           // 2025-12-02 (ISO format)
            DateTimeFormatter.ofPattern("MM/dd/yyyy"),           // 12/02/2025 (US format)
            DateTimeFormatter.ofPattern("dd/MM/yyyy")            // 02/12/2025 (EU format)
    );

    public StatementProcessor(StatementService statementService,
            TransactionService transactionService,
            ChatClient.Builder chatClientBuilder,
            PdfProcessor pdfProcessor,
            RegistrationRepository registrationRepository) {

        this.transactionService = transactionService;
        this.statementService = statementService;
        this.pdfProcessor = pdfProcessor;
        this.registrationRepository = registrationRepository;
        this.chatClient = chatClientBuilder.build();
        DateTimeFormatter outputFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy");
        this.gson = new GsonBuilder()
                .registerTypeAdapter(LocalDate.class, new TypeAdapter<LocalDate>() {
                    @Override
                    public void write(JsonWriter out, LocalDate value) throws IOException {
                        if (value == null) {
                            out.nullValue();
                        } else {
                            out.value(value.format(outputFormatter));
                        }
                    }

                    @Override
                    public LocalDate read(JsonReader in) throws IOException {
                        if (in.peek() == JsonToken.NULL) {
                            in.nextNull();
                            return null;
                        }
                        String dateStr = in.nextString();
                        return parseLocalDate(dateStr);
                    }
                })
                .registerTypeAdapter(LocalDateTime.class, new TypeAdapter<LocalDateTime>() {
                    @Override
                    public void write(JsonWriter out, LocalDateTime value) throws IOException {
                        if (value == null) {
                            out.nullValue();
                        } else {
                            out.value(value.toLocalDate().format(outputFormatter));
                        }
                    }

                    @Override
                    public LocalDateTime read(JsonReader in) throws IOException {
                        if (in.peek() == JsonToken.NULL) {
                            in.nextNull();
                            return null;
                        }
                        String dateStr = in.nextString();
                        LocalDate date = parseLocalDate(dateStr);
                        return date.atStartOfDay();
                    }
                })
                .create();

    }

    /**
     * On application startup, reset any statements that were stuck in PROCESSING status.
     * This handles crash recovery - if the app crashed during processing, those statements
     * will be reset to OPEN so they can be reprocessed.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        log.info("Application started - checking for stuck PROCESSING statements across all tenants");
        
        try {
            List<Registration> activeTenants = registrationRepository.findByStatus(RegistrationStatus.COMPLETE);
            
            for (Registration tenant : activeTenants) {
                String tenantId = tenant.getRegistrationId();
                try {
                    TenantContext.setTenantId(tenantId);
                    resetProcessingStatements();
                } catch (Exception e) {
                    log.error("Error resetting PROCESSING statements for tenant {}: {}", tenantId, e.getMessage(), e);
                } finally {
                    TenantContext.clear();
                }
            }
            
            log.info("Completed startup check for stuck statements");
        } catch (Exception e) {
            // This may happen in test environments where the database is not fully configured
            log.warn("Unable to perform startup check for stuck statements: {}. " +
                    "This is expected in test environments.", e.getMessage());
        }
    }
    
    /**
     * Resets any statements in PROCESSING status back to OPEN.
     * This is called on startup to recover from crashes.
     */
    @Transactional
    public void resetProcessingStatements() {
        List<Statement> processingStatements = statementService.getStatementsByStatus(StatementStatus.PROCESSING);
        if (!processingStatements.isEmpty()) {
            log.warn("Found {} statement(s) stuck in PROCESSING status for tenant {}. Resetting to OPEN.", 
                    processingStatements.size(), TenantContext.getTenantId());
            for (Statement statement : processingStatements) {
                statement.setStatus(StatementStatus.OPEN);
                statementService.saveStatement(statement);
                log.info("Reset statement {} from PROCESSING to OPEN", statement.getId());
            }
        }
    }

    /**
     * Scheduled task to process open statements across all tenants.
     * This method iterates over all active tenants (COMPLETE registrations),
     * sets the tenant context for each, and processes any open statements.
     */
    @Scheduled(fixedRate = 60000)
    public void processStatement() {
        log.debug("Starting scheduled statement processing across all tenants");
        
        // Get all active tenants (COMPLETE registrations)
        List<Registration> activeTenants = registrationRepository.findByStatus(RegistrationStatus.COMPLETE);
        log.debug("Found {} active tenants to process", activeTenants.size());
        
        for (Registration tenant : activeTenants) {
            String tenantId = tenant.getRegistrationId();
            try {
                // Set the tenant context for this tenant
                TenantContext.setTenantId(tenantId);
                log.debug("Processing statements for tenant: {}", tenantId);
                
                // Process statements for this tenant
                processStatementsForCurrentTenant();
                
            } catch (Exception e) {
                log.error("Error processing statements for tenant {}: {}", tenantId, e.getMessage(), e);
            } finally {
                // Always clear the tenant context
                TenantContext.clear();
            }
        }
        
        log.debug("Completed scheduled statement processing");
    }
    
    /**
     * Processes all open statements for the current tenant context.
     * Each statement is processed in its own transaction to ensure atomicity.
     */
    private void processStatementsForCurrentTenant() {
        List<Statement> openStatements = statementService.getOpenStatements();
        log.debug("Found {} open statements for tenant {}", openStatements.size(), TenantContext.getTenantId());
        
        for (Statement statement : openStatements) {
            try {
                // Process each statement in a separate transaction
                processStatementTransactionally(statement);
            } catch (Exception e) {
                log.error("Error processing statement {}: {}", statement.getId(), e.getMessage(), e);
                // Mark as failed in a separate transaction
                markStatementFailed(statement);
            }
        }
    }
    
    /**
     * Processes a single statement within a transaction.
     * Creates all transactions and balance entries atomically.
     * If anything fails, the entire operation is rolled back.
     * 
     * @param statement The statement to process
     */
    @Transactional
    public void processStatementTransactionally(Statement statement) {
        log.info("Starting transactional processing for statement {}", statement.getId());
        
        // Set status to PROCESSING immediately to indicate work has begun
        statement.setStatus(StatementStatus.PROCESSING);
        statementService.saveStatement(statement);
        log.info("Statement {} status set to PROCESSING", statement.getId());
        
        // Extract transactions from PDF (this calls LLM, so it's outside the main transaction work)
        List<Transaction> transactions = extractTransactionsFromPdf(statement);

        if (transactions == null || transactions.isEmpty()) {
            log.warn("No transactions extracted from statement {}", statement.getId());
            statement.setStatus(StatementStatus.FAILED);
            statementService.saveStatement(statement);
            return;
        }
        
        log.info("Extracted {} transactions from statement {}", transactions.size(), statement.getId());
        
        // Process each transaction with synchronous balance updates (within same transaction)
        for (Transaction uncategorizedTransaction : transactions) {
            uncategorizedTransaction.setAccount(statement.getAccount());
            Transaction categorizedTransaction = transactionService.categorizeTransaction(uncategorizedTransaction);
            categorizedTransaction.setStatementId(statement.getId());
            categorizedTransaction.setCategorizationStatus(TransactionCategorizationStatus.LLM_CATEGORIZED);
            // Use synchronous balance update (asyncBalanceUpdate = false)
            transactionService.saveTransaction(categorizedTransaction, false);
        }
        
        // Mark statement as completed only after all transactions and balances are saved
        statement.setStatus(StatementStatus.COMPLETED);
        statementService.saveStatement(statement);
        
        log.info("Successfully completed processing statement {} with {} transactions", 
                statement.getId(), transactions.size());
    }
    
    /**
     * Marks a statement as failed. Called when processing fails outside the transaction.
     */
    @Transactional
    public void markStatementFailed(Statement statement) {
        try {
            // Reload the statement to get current state
            Statement currentStatement = statementService.getStatementById(statement.getId());
            if (currentStatement != null) {
                currentStatement.setStatus(StatementStatus.FAILED);
                statementService.saveStatement(currentStatement);
                log.warn("Marked statement {} as FAILED", statement.getId());
            }
        } catch (Exception e) {
            log.error("Failed to mark statement {} as FAILED: {}", statement.getId(), e.getMessage(), e);
        }
    }

    public List<Transaction> extractTransactionsFromPdf(Statement statement) {

        try {

            String extractedText = pdfProcessor.extractTextFromPdf(statement.getContent());
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
            // statementRepository.save(statement); TODO: Implement retry and fail
            return null;
        }
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
    
    /**
     * Attempts to parse a date string using multiple date formats.
     * This provides resilience against LLM output format variations.
     * 
     * @param dateStr the date string to parse
     * @return the parsed LocalDate
     * @throws DateTimeParseException if none of the formatters can parse the date
     */
    private static LocalDate parseLocalDate(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) {
            return null;
        }
        
        String trimmedDate = dateStr.trim();
        
        for (DateTimeFormatter formatter : DATE_FORMATTERS) {
            try {
                return LocalDate.parse(trimmedDate, formatter);
            } catch (DateTimeParseException e) {
                // Try next formatter
            }
        }
        
        // If all formatters fail, throw an exception with helpful message
        throw new DateTimeParseException(
                "Unable to parse date '" + dateStr + "' using any of the supported formats. " +
                "Expected formats: dd.MM.yyyy, dd MMM yyyy, dd-MM-yyyy, yyyy-MM-dd, MM/dd/yyyy, dd/MM/yyyy",
                dateStr, 0);
    }

}