package com.everrich.spendmanager.service;

import java.lang.reflect.Type;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

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
import com.google.gson.JsonDeserializer;
import com.google.gson.reflect.TypeToken;

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
        this.gson = new GsonBuilder()
                .registerTypeAdapter(LocalDate.class,
                        (JsonDeserializer<LocalDate>) (json, typeOfT, context) -> LocalDate.parse(json.getAsString(),
                                DateTimeFormatter.ofPattern("dd.MM.yyyy")))
                .create();

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
     */
    private void processStatementsForCurrentTenant() {
        List<Statement> openStatements = statementService.getOpenStatements();
        log.debug("Found {} open statements for tenant {}", openStatements.size(), TenantContext.getTenantId());
        
        for (Statement statement : openStatements) {
            try {
                statement.setStatus(StatementStatus.PROCESSING);
                statementService.saveStatement(statement);
                List<Transaction> transactions = extractTransactionsFromPdf(statement);

                if (transactions != null) {
                    for (Transaction uncategorizedTransaction : transactions) {
                        uncategorizedTransaction.setAccount(statement.getAccount());
                        Transaction categorizedTransaction = transactionService.categorizeTransaction(uncategorizedTransaction);
                        categorizedTransaction.setStatementId(statement.getId());
                        categorizedTransaction.setCategorizationStatus(TransactionCategorizationStatus.LLM_CATEGORIZED);
                        transactionService.saveTransaction(categorizedTransaction);
                    }
                    statement.setStatus(StatementStatus.COMPLETED);
                } else {
                    statement.setStatus(StatementStatus.FAILED);
                }
                statementService.saveStatement(statement);
            } catch (Exception e) {
                log.error("Error processing statement {}: {}", statement.getId(), e.getMessage(), e);
                statement.setStatus(StatementStatus.FAILED);
                statementService.saveStatement(statement);
            }
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

}
