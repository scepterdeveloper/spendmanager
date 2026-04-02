package com.everrich.spendmanager.service;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Collections;
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

import com.everrich.spendmanager.dto.ParsedStatementDTO;
import com.everrich.spendmanager.entities.Registration;
import com.everrich.spendmanager.entities.RegistrationStatus;
import com.everrich.spendmanager.entities.Statement;
import com.everrich.spendmanager.entities.StatementFileType;
import com.everrich.spendmanager.entities.StatementStatus;
import com.everrich.spendmanager.entities.Transaction;
import com.everrich.spendmanager.entities.TransactionCategorizationStatus;
import com.everrich.spendmanager.multitenancy.TenantContext;
import com.everrich.spendmanager.repository.RegistrationRepository;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import java.math.BigDecimal;

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
    private final CsvProcessor csvProcessor;
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
            CsvProcessor csvProcessor,
            RegistrationRepository registrationRepository) {

        this.transactionService = transactionService;
        this.statementService = statementService;
        this.pdfProcessor = pdfProcessor;
        this.csvProcessor = csvProcessor;
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
                // First, mark the statement as PROCESSING in a separate transaction
                // This prevents other scheduler instances from picking up the same statement
                markStatementProcessing(statement);
                
                // Then process the statement (transaction extraction, etc.)
                processStatementTransactionally(statement);
            } catch (Exception e) {
                log.error("Error processing statement {}: {}", statement.getId(), e.getMessage(), e);
                // Mark as failed in a separate transaction with error message
                markStatementFailed(statement, createHumanReadableError(e));
            }
        }
    }
    
    /**
     * Marks a statement as PROCESSING. This is done in a separate transaction
     * to ensure the status change is committed immediately, preventing other
     * scheduler instances from picking up the same statement.
     */
    @Transactional
    public void markStatementProcessing(Statement statement) {
        Statement currentStatement = statementService.getStatementById(statement.getId());
        if (currentStatement != null && currentStatement.getStatus() == StatementStatus.OPEN) {
            currentStatement.setStatus(StatementStatus.PROCESSING);
            statementService.saveStatement(currentStatement);
            log.info("Processing statement {} - {}", statement.getId(), statement.getOriginalFileName());
        } else {
            throw new IllegalStateException("Statement " + statement.getId() + 
                    " is no longer OPEN (current status: " + 
                    (currentStatement != null ? currentStatement.getStatus() : "NOT FOUND") + ")");
        }
    }
    
    /**
     * Processes a single statement within a transaction.
     * Extracts transactions from PDF and saves them with TO_BE_LLM_CATEGORIZED status.
     * Categorization is handled separately by CategorizationProcessor.
     * If anything fails, the entire operation is rolled back.
     * 
     * Note: The statement should already be in PROCESSING status (set by markStatementProcessing).
     * 
     * @param statement The statement to process
     */
    @Transactional
    public void processStatementTransactionally(Statement statement) {
        log.debug("Starting transactional processing for statement {}", statement.getId());
        
        // Reload the statement to get current state (should already be PROCESSING)
        Statement currentStatement = statementService.getStatementById(statement.getId());
        if (currentStatement == null || currentStatement.getStatus() != StatementStatus.PROCESSING) {
            log.warn("Statement {} is not in PROCESSING status, skipping", statement.getId());
            return;
        }
        
        // Extract transactions based on file type
        // PDF: Uses LLM for parsing unstructured text
        // CSV: Direct parsing (no LLM needed - faster and cheaper)
        List<Transaction> transactions = extractTransactionsFromStatement(currentStatement);

        if (transactions == null || transactions.isEmpty()) {
            log.warn("No transactions extracted from statement {}", currentStatement.getId());
            currentStatement.setStatus(StatementStatus.FAILED);
            statementService.saveStatement(currentStatement);
            return;
        }
        
        log.info("Statement {} parsed - {} transactions extracted", currentStatement.getId(), transactions.size());
        
        // Save each transaction with TO_BE_LLM_CATEGORIZED status
        // Categorization will be done asynchronously by CategorizationProcessor
        for (Transaction transaction : transactions) {
            transaction.setAccount(currentStatement.getAccount());
            transaction.setStatementId(currentStatement.getId());
            transaction.setCategorizationStatus(TransactionCategorizationStatus.TO_BE_LLM_CATEGORIZED);
            // Use synchronous balance update (asyncBalanceUpdate = false) for consistency
            transactionService.saveTransaction(transaction, false);
        }
        
        log.debug("Saved {} transactions with TO_BE_LLM_CATEGORIZED status for statement {}", 
                transactions.size(), currentStatement.getId());
        
        // Set statement to CATEGORIZING and record LLM categorization start time
        currentStatement.setStatus(StatementStatus.CATEGORIZING);
        currentStatement.setLlmCategorizationStart(LocalDateTime.now());
        statementService.saveStatement(currentStatement);
        
        log.debug("Statement {} set to CATEGORIZING with {} transactions pending categorization", 
                currentStatement.getId(), transactions.size());
    }
    
    /**
     * Marks a statement as failed with an error message. Called when processing fails outside the transaction.
     * 
     * @param statement The statement to mark as failed
     * @param errorMessage A human-readable error message describing what went wrong
     */
    @Transactional
    public void markStatementFailed(Statement statement, String errorMessage) {
        try {
            // Reload the statement to get current state
            Statement currentStatement = statementService.getStatementById(statement.getId());
            if (currentStatement != null) {
                currentStatement.setStatus(StatementStatus.FAILED);
                currentStatement.setErrorMessage(errorMessage);
                statementService.saveStatement(currentStatement);
                log.warn("Marked statement {} as FAILED: {}", statement.getId(), errorMessage);
            }
        } catch (Exception e) {
            log.error("Failed to mark statement {} as FAILED: {}", statement.getId(), e.getMessage(), e);
        }
    }
    
    /**
     * Creates a human-readable error message from an exception.
     * Translates technical exceptions into user-friendly messages.
     * 
     * @param e The exception to translate
     * @return A human-readable error message
     */
    private String createHumanReadableError(Exception e) {
        String message = e.getMessage();
        
        // Handle DateTimeParseException specifically
        if (e instanceof DateTimeParseException || 
            (e.getCause() != null && e.getCause() instanceof DateTimeParseException)) {
            DateTimeParseException dtpe = (e instanceof DateTimeParseException) 
                    ? (DateTimeParseException) e 
                    : (DateTimeParseException) e.getCause();
            return "Date parsing error: The system couldn't recognize a date in the statement. " +
                   "The problematic value was: '" + dtpe.getParsedString() + "'. " +
                   "Please ensure dates in the statement are in a standard format (e.g., 03.12.2025).";
        }
        
        // Handle JSON parsing errors
        if (message != null && (message.contains("JsonSyntax") || message.contains("JSON"))) {
            return "Data extraction error: The AI model returned data in an unexpected format. " +
                   "This may be due to an unusual statement layout. Please try uploading again.";
        }
        
        // Handle IOException (PDF processing)
        if (e instanceof java.io.IOException) {
            return "PDF processing error: Unable to read the uploaded file. " +
                   "Please ensure the file is a valid PDF document.";
        }
        
        // Generic fallback with original message if available
        if (message != null && !message.isBlank()) {
            // Truncate very long messages
            if (message.length() > 500) {
                message = message.substring(0, 497) + "...";
            }
            return "Processing error: " + message;
        }
        
        return "An unexpected error occurred while processing the statement. Please try again or contact support.";
    }

    /**
     * Extracts transactions from a statement based on its file type.
     * Routes to PDF or CSV processor accordingly.
     * 
     * @param statement The statement to extract transactions from
     * @return List of transactions extracted from the statement
     */
    public List<Transaction> extractTransactionsFromStatement(Statement statement) {
        StatementFileType fileType = statement.getFileType();
        
        // Default to PDF for backward compatibility with existing statements
        if (fileType == null) {
            fileType = StatementFileType.PDF;
        }
        
        if (fileType == StatementFileType.CSV) {
            return extractTransactionsFromCsv(statement);
        } else {
            return extractTransactionsFromPdf(statement);
        }
    }
    
    /**
     * Extracts transactions from a CSV statement using LLM parsing.
     * CSV text is sent directly to the LLM for intelligent parsing.
     * This handles varying bank formats, encodings, and column layouts.
     * 
     * @param statement The CSV statement to extract transactions from
     * @return List of transactions extracted from the CSV
     */
    public List<Transaction> extractTransactionsFromCsv(Statement statement) {
        log.info("Extracting transactions from CSV (LLM parsing)");
        
        try {
            // Convert CSV bytes to string
            String csvText = new String(statement.getContent(), java.nio.charset.StandardCharsets.UTF_8);
            
            // Use the same LLM parsing flow as PDF
            String parsedJson = parseTransactionsWithGemini(csvText);
            String cleanJson = cleanLLMResponse(parsedJson);
            log.debug("LLM parsing completed for CSV statement {}", statement.getId());
            
            // Deserialize to ParsedStatementDTO which contains both metadata and transactions
            ParsedStatementDTO parsedStatement = deserializeParsedStatement(cleanJson);
            
            if (parsedStatement == null) {
                log.warn("Failed to deserialize LLM response for CSV statement {}", statement.getId());
                return Collections.emptyList();
            }
            
            // Apply statement metadata if available
            applyStatementMetadata(statement, parsedStatement);
            
            List<Transaction> transactions = parsedStatement.getTransactions();
            if (transactions == null) {
                transactions = Collections.emptyList();
            }
            
            log.info("CSV LLM parsing completed for statement {}: {} transactions extracted", 
                    statement.getId(), transactions.size());
            
            return transactions;
            
        } catch (Exception e) {
            log.error("Error while processing CSV to extract transactions for statement {}", 
                    statement.getId(), e);
            statement.setStatus(StatementStatus.FAILED);
            statement.setErrorMessage(createHumanReadableError(e));
            return null;
        }
    }

    /**
     * Extracts transactions from a PDF statement and updates the statement with metadata.
     * The LLM response now includes both statement-level metadata and transactions.
     * 
     * @param statement The statement to extract transactions from (will be updated with metadata)
     * @return List of transactions extracted from the statement
     */
    public List<Transaction> extractTransactionsFromPdf(Statement statement) {

        try {

            String extractedText = pdfProcessor.extractTextFromPdf(statement.getContent());
            log.debug("Extract Text from PDF: DONE");
            String parsedJson = parseTransactionsWithGemini(extractedText);
            String cleanJson = cleanLLMResponse(parsedJson);
            log.debug("LLM parsing completed for statement {}", statement.getId());
            
            // Deserialize to ParsedStatementDTO which contains both metadata and transactions
            ParsedStatementDTO parsedStatement = deserializeParsedStatement(cleanJson);
            
            if (parsedStatement == null) {
                log.warn("Failed to deserialize LLM response for statement {}", statement.getId());
                return Collections.emptyList();
            }
            
            // Apply statement metadata if available
            applyStatementMetadata(statement, parsedStatement);
            
            List<Transaction> transactions = parsedStatement.getTransactions();
            if (transactions == null) {
                transactions = Collections.emptyList();
            }
            
            log.debug("Parse-clean (LLM Based) and deserialized: {} transaction(s), metadata applied: periodStart={}, periodEnd={}, openingBal={}, closingBal={}",
                    transactions.size(),
                    statement.getPeriodStartDate(),
                    statement.getPeriodEndDate(),
                    statement.getOpeningBalance(),
                    statement.getClosingBalance());

            return transactions;
        } catch (Exception e) {
            log.error("Error while processing PDF to extract transactions", e);
            statement.setStatus(StatementStatus.FAILED);
            return null;
        }
    }
    
    /**
     * Applies metadata from the parsed statement DTO to the statement entity.
     * Only non-null values from the DTO are applied.
     * 
     * @param statement The statement entity to update
     * @param parsedStatement The parsed statement DTO containing metadata
     */
    private void applyStatementMetadata(Statement statement, ParsedStatementDTO parsedStatement) {
        if (parsedStatement.getPeriodStartDate() != null) {
            statement.setPeriodStartDate(parsedStatement.getPeriodStartDate());
            log.debug("Set periodStartDate to {}", parsedStatement.getPeriodStartDate());
        }
        
        if (parsedStatement.getPeriodEndDate() != null) {
            statement.setPeriodEndDate(parsedStatement.getPeriodEndDate());
            log.debug("Set periodEndDate to {}", parsedStatement.getPeriodEndDate());
        }
        
        if (parsedStatement.getOpeningBalance() != null) {
            statement.setOpeningBalance(parsedStatement.getOpeningBalance());
            log.debug("Set openingBalance to {}", parsedStatement.getOpeningBalance());
        }
        
        if (parsedStatement.getClosingBalance() != null) {
            statement.setClosingBalance(parsedStatement.getClosingBalance());
            log.debug("Set closingBalance to {}", parsedStatement.getClosingBalance());
        }
        
        if (parsedStatement.getDescription() != null && !parsedStatement.getDescription().isBlank()) {
            statement.setDescription(parsedStatement.getDescription());
            log.debug("Set description to {}", parsedStatement.getDescription());
        }
    }

    private String parseTransactionsWithGemini(String transactionText) {

        PromptTemplate promptTemplate = new PromptTemplate(parseTransactionsPromptResource);
        Map<String, Object> model = Map.of("transactions", transactionText);
        log.debug("Going to call LLM for parsing");
        Prompt prompt = promptTemplate.create(model);
        log.debug("Prompt created for LLM parsing");
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

    /**
     * Deserializes the LLM JSON response to a ParsedStatementDTO.
     * This includes both statement metadata and the list of transactions.
     * 
     * @param json The JSON string from the LLM
     * @return ParsedStatementDTO containing metadata and transactions
     */
    private ParsedStatementDTO deserializeParsedStatement(String json) {
        try {
            return gson.fromJson(json, ParsedStatementDTO.class);
        } catch (Exception e) {
            log.error("Error deserializing parsed statement JSON: {}", e.getMessage(), e);
            return null;
        }
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