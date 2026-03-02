package com.everrich.spendmanager.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import com.everrich.spendmanager.entities.Transaction;
import com.everrich.spendmanager.entities.TransactionOperation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class VectorStoreService {

    private final ChatClient chatClient;
    private RedisAdapter redisAdapter;
    private static final Logger log = LoggerFactory.getLogger(VectorStoreService.class);

    @Value("classpath:/prompts/normalize-description-prompt.st")
    private Resource normalizeDescriptionPromptResource;

    public VectorStoreService(RedisAdapter redisAdapter, ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
        this.redisAdapter = redisAdapter;

        if (redisAdapter == null) {
            log.error("RedisAdapter is null while wiring VectorStore");
            return;
        }
        if (chatClient == null) {
            log.error("Chat Client is null while wiring VectorStore");
            return;
        }
        log.info("VectorStore constructor through...");
        redisAdapter.createTransactionIndex();
    }

    /**
     * Learns a correct category for RAG-based categorization.
     * This overload uses TenantContext to get the tenant ID.
     */
    public void learnCorrectCategory(String transactionDescription, String correctCategory, double amount,
            TransactionOperation operation, String accountName) {
        learnCorrectCategory(transactionDescription, correctCategory, amount, operation, accountName, null);
    }
    
    /**
     * Learns a correct category for RAG-based categorization with explicit tenant ID.
     * Use this overload for async operations where TenantContext may not be available.
     * 
     * @param transactionDescription The transaction description
     * @param correctCategory The correct category name
     * @param amount The transaction amount
     * @param operation The transaction operation (PLUS/MINUS)
     * @param accountName The account name
     * @param tenantId The tenant ID (pass null to use TenantContext)
     */
    public void learnCorrectCategory(String transactionDescription, String correctCategory, double amount,
            TransactionOperation operation, String accountName, String tenantId) {

        // 1. 🟢 Apply the cleaning logic to the description before indexing
        String cleanedDescription = normalizeDescription(transactionDescription);
        String operationName = operation.name(); // Get the string "PLUS" or "MINUS"
        
        if (tenantId != null && !tenantId.isEmpty()) {
            // Use explicit tenant ID (for async operations)
            redisAdapter.createDocument(correctCategory, cleanedDescription, operationName, accountName, tenantId);
        } else {
            // Use TenantContext (for synchronous operations)
            redisAdapter.createDocument(correctCategory, cleanedDescription, operationName, accountName);
        }
    }

    public String similaritySearch(Transaction transaction) {

        String description = normalizeDescription(transaction.getDescription());
        List<RedisDocument> searchResults = redisAdapter.searchDocuments(description, transaction.getOperation().name(),
                transaction.getAccount().getName());
        String context = "";

        // Handle null or empty results gracefully (e.g., when vector store is empty/reset)
        if (searchResults == null || searchResults.isEmpty()) {
            log.info("No similar documents found in vector store for transaction: {}", transaction.getDescription());
            return context;
        }

        for (RedisDocument redisDocument : searchResults) {

            context += "Description: " + redisDocument.getFields().get("description_op") + ", Corrected Category: "
                    + redisDocument.getFields().get("category") + "\n";
        }

        return context;
    }
    
    /**
     * Performs batch similarity search for multiple transactions.
     * This aggregates unique descriptions and performs searches, then combines
     * all results into a single context string to share across all transactions.
     * 
     * @param transactions List of transactions to search context for
     * @return Aggregated context string with historical categorization decisions
     */
    public String batchSimilaritySearch(List<Transaction> transactions) {
        if (transactions == null || transactions.isEmpty()) {
            return "";
        }
        
        long methodStart = System.currentTimeMillis();
        log.info("LLM_TIMING: Starting batch similarity search for {} transactions", transactions.size());
        
        // Collect unique normalized descriptions to avoid duplicate searches
        Set<String> processedDescriptions = new HashSet<>();
        List<RedisDocument> allResults = new ArrayList<>();
        
        long totalNormalizationTime = 0;
        int normalizationCount = 0;
        
        for (Transaction transaction : transactions) {
            long normalizeStart = System.currentTimeMillis();
            String normalizedDescription = normalizeDescription(transaction.getDescription());
            long normalizeDuration = System.currentTimeMillis() - normalizeStart;
            totalNormalizationTime += normalizeDuration;
            normalizationCount++;
            
            // Skip if we've already searched for this normalized description
            if (processedDescriptions.contains(normalizedDescription)) {
                continue;
            }
            processedDescriptions.add(normalizedDescription);
            
            // Perform similarity search
            String accountName = transaction.getAccount() != null ? transaction.getAccount().getName() : "Unknown";
            List<RedisDocument> searchResults = redisAdapter.searchDocuments(
                    normalizedDescription, 
                    transaction.getOperation().name(),
                    accountName);
            
            if (searchResults != null && !searchResults.isEmpty()) {
                allResults.addAll(searchResults);
            }
        }
        
        log.info("LLM_TIMING: normalizeDescription called {} times, total time: {} ms, avg: {} ms", 
                normalizationCount, totalNormalizationTime, 
                normalizationCount > 0 ? totalNormalizationTime / normalizationCount : 0);
        
        // Handle null or empty results gracefully
        if (allResults.isEmpty()) {
            log.info("No similar documents found in vector store for batch of {} transactions", transactions.size());
            return "";
        }
        
        // Deduplicate results based on description_op to avoid repetitive context
        Set<String> seenContexts = new HashSet<>();
        StringBuilder contextBuilder = new StringBuilder();
        
        for (RedisDocument redisDocument : allResults) {
            String descriptionOp = (String) redisDocument.getFields().get("description_op");
            String category = (String) redisDocument.getFields().get("category");
            
            // Create a unique key for deduplication
            String contextKey = descriptionOp + "|" + category;
            if (seenContexts.contains(contextKey)) {
                continue;
            }
            seenContexts.add(contextKey);
            
            contextBuilder.append("Description: ")
                    .append(descriptionOp)
                    .append(", Corrected Category: ")
                    .append(category)
                    .append("\n");
        }
        
        String context = contextBuilder.toString();
        long totalDuration = System.currentTimeMillis() - methodStart;
        log.info("LLM_TIMING: batchSimilaritySearch total took {} ms for {} transactions ({} unique descriptions)", 
                totalDuration, transactions.size(), processedDescriptions.size());
        log.info("Batch similarity search completed. Found {} unique context entries from {} searches", 
                seenContexts.size(), processedDescriptions.size());
        
        return context;
    }

    // LLM based
    private String normalizeDescription(String transactionDescription) {
        long start = System.currentTimeMillis();
        
        PromptTemplate promptTemplate = new PromptTemplate(normalizeDescriptionPromptResource);
        Map<String, Object> model = Map.of(
                "transactionDescription", transactionDescription // Corrected key and value
        );

        // 3. Create, call, and return the response content
        String result = chatClient.prompt(promptTemplate.create(model))
                .call()
                .content()
                .trim(); // Always good practice to trim the output
        
        long duration = System.currentTimeMillis() - start;
        log.debug("LLM_TIMING: normalizeDescription LLM call took {} ms for: {}", 
                duration, transactionDescription);
        
        return result;
    }
}