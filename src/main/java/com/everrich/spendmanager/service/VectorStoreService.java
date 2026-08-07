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
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class VectorStoreService {

    private final ChatClient chatClient;
    private final RedisAdapter redisAdapter;
    private final LlmRateLimiter llmRateLimiter;
    private final LocalDescriptionNormalizer localNormalizer;
    private final Gson gson;
    private static final Logger log = LoggerFactory.getLogger(VectorStoreService.class);
    
    private static final String JSON_CODE_FENCE = "```";
    private static final String JSON_MARKER = "json";

    @Value("classpath:/prompts/normalize-description-prompt.st")
    private Resource normalizeDescriptionPromptResource;
    
    @Value("classpath:/prompts/normalize-description-prompt-batch.st")
    private Resource normalizeDescriptionBatchPromptResource;
    
    /**
     * Configuration to switch between local and LLM-based normalization.
     * Default is true (use local normalization for better performance).
     * Set to false to use LLM-based normalization (original behavior).
     */
    @Value("${spendmanager.normalization.use-local:true}")
    private boolean useLocalNormalization;

    public VectorStoreService(RedisAdapter redisAdapter, ChatClient.Builder chatClientBuilder, 
            LlmRateLimiter llmRateLimiter, LocalDescriptionNormalizer localNormalizer) {
        this.chatClient = chatClientBuilder.build();
        this.redisAdapter = redisAdapter;
        this.llmRateLimiter = llmRateLimiter;
        this.localNormalizer = localNormalizer;
        this.gson = new GsonBuilder().create();

        if (redisAdapter == null) {
            log.error("RedisAdapter is null while wiring VectorStore");
            return;
        }
        if (chatClient == null) {
            log.error("Chat Client is null while wiring VectorStore");
            return;
        }
        log.info("VectorStore constructor through... (useLocalNormalization={})", useLocalNormalization);
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
     * Uses batch normalization to reduce LLM calls - normalizes all unique descriptions
     * in a single LLM call instead of one call per description.
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
        
        // STEP 1: Collect unique descriptions and their associated transaction info
        // Map from original description to list of transactions with that description
        Map<String, List<Transaction>> descriptionToTransactions = new HashMap<>();
        for (Transaction transaction : transactions) {
            String desc = transaction.getDescription();
            descriptionToTransactions.computeIfAbsent(desc, k -> new ArrayList<>()).add(transaction);
        }
        
        List<String> uniqueDescriptions = new ArrayList<>(descriptionToTransactions.keySet());
        log.info("LLM_TIMING: Found {} unique descriptions from {} transactions", 
                uniqueDescriptions.size(), transactions.size());
        
        // STEP 2: Batch normalize all unique descriptions in a single LLM call
        long normalizeStart = System.currentTimeMillis();
        Map<String, String> normalizedMap = batchNormalizeDescriptions(uniqueDescriptions);
        long normalizeDuration = System.currentTimeMillis() - normalizeStart;
        log.info("LLM_TIMING: batchNormalizeDescriptions took {} ms for {} descriptions (single LLM call)", 
                normalizeDuration, uniqueDescriptions.size());
        
        // STEP 3: Perform similarity searches using normalized descriptions
        Set<String> processedNormalizedDescriptions = new HashSet<>();
        List<RedisDocument> allResults = new ArrayList<>();
        
        for (Map.Entry<String, List<Transaction>> entry : descriptionToTransactions.entrySet()) {
            String originalDescription = entry.getKey();
            List<Transaction> txnsWithDesc = entry.getValue();
            
            // Get normalized description (fallback to original if not found)
            String normalizedDescription = normalizedMap.getOrDefault(originalDescription, originalDescription);
            
            // Skip if we've already searched for this normalized description
            if (processedNormalizedDescriptions.contains(normalizedDescription)) {
                continue;
            }
            processedNormalizedDescriptions.add(normalizedDescription);
            
            // Use first transaction's info for the search (they all have the same description)
            Transaction firstTxn = txnsWithDesc.get(0);
            String accountName = firstTxn.getAccount() != null ? firstTxn.getAccount().getName() : "Unknown";
            
            List<RedisDocument> searchResults = redisAdapter.searchDocuments(
                    normalizedDescription, 
                    firstTxn.getOperation().name(),
                    accountName);
            
            if (searchResults != null && !searchResults.isEmpty()) {
                allResults.addAll(searchResults);
            }
        }
        
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
        log.info("LLM_TIMING: batchSimilaritySearch total took {} ms for {} transactions ({} unique descriptions, {} unique normalized)", 
                totalDuration, transactions.size(), uniqueDescriptions.size(), processedNormalizedDescriptions.size());
        log.info("Batch similarity search completed. Found {} unique context entries from {} searches", 
                seenContexts.size(), processedNormalizedDescriptions.size());
        
        return context;
    }
    
    /**
     * Batch normalize multiple descriptions.
     * Uses local normalization (fast, no API cost) when useLocalNormalization is true,
     * otherwise falls back to LLM-based normalization.
     * 
     * @param descriptions List of descriptions to normalize
     * @return Map from original description to normalized description
     */
    private Map<String, String> batchNormalizeDescriptions(List<String> descriptions) {
        Map<String, String> results = new HashMap<>();
        
        if (descriptions == null || descriptions.isEmpty()) {
            return results;
        }
        
        // Use local normalization if configured (default: true)
        if (useLocalNormalization) {
            long start = System.currentTimeMillis();
            for (String desc : descriptions) {
                results.put(desc, localNormalizer.normalize(desc));
            }
            long duration = System.currentTimeMillis() - start;
            log.debug("Local batch normalization completed for {} descriptions in {} ms", 
                    descriptions.size(), duration);
            return results;
        }
        
        // LLM-based batch normalization (legacy path)
        return batchNormalizeDescriptionsWithLlm(descriptions);
    }
    
    /**
     * Batch normalize multiple descriptions using LLM.
     * This is the legacy implementation, kept for backward compatibility.
     * 
     * @param descriptions List of descriptions to normalize
     * @return Map from original description to normalized description
     */
    private Map<String, String> batchNormalizeDescriptionsWithLlm(List<String> descriptions) {
        Map<String, String> results = new HashMap<>();
        
        // For very small batches (1-2 items), use individual normalization
        // This avoids JSON parsing overhead for trivial cases
        if (descriptions.size() <= 2) {
            for (String desc : descriptions) {
                results.put(desc, normalizeDescriptionWithLlm(desc));
            }
            return results;
        }
        
        // Build the numbered list of descriptions for the prompt
        StringBuilder descriptionsBuilder = new StringBuilder();
        Map<Integer, String> indexToOriginal = new HashMap<>();
        for (int i = 0; i < descriptions.size(); i++) {
            int index = i + 1; // 1-based index
            String desc = descriptions.get(i);
            descriptionsBuilder.append(String.format("%d. %s%n", index, desc));
            indexToOriginal.put(index, desc);
        }
        
        try {
            String response = llmRateLimiter.executeWithRetryOrThrow(() -> {
                PromptTemplate promptTemplate = new PromptTemplate(normalizeDescriptionBatchPromptResource);
                Map<String, Object> model = Map.of("descriptions", descriptionsBuilder.toString());
                
                return chatClient.prompt(promptTemplate.create(model))
                        .call()
                        .content();
            });
            
            // Parse the JSON response
            String cleanedResponse = cleanLLMResponse(response);
            Type listType = new TypeToken<List<NormalizedDescription>>(){}.getType();
            List<NormalizedDescription> normalizedList = gson.fromJson(cleanedResponse, listType);
            
            if (normalizedList != null) {
                for (NormalizedDescription nd : normalizedList) {
                    String originalDesc = indexToOriginal.get(nd.getIndex());
                    if (originalDesc != null && nd.getNormalized() != null) {
                        results.put(originalDesc, nd.getNormalized().trim());
                    }
                }
            }
            
            log.debug("LLM Batch normalization: Successfully normalized {} of {} descriptions", 
                    results.size(), descriptions.size());
            
            // Fill in any missing normalizations with original descriptions
            for (String desc : descriptions) {
                if (!results.containsKey(desc)) {
                    log.warn("LLM Batch normalization: No result for description, using original: {}", desc);
                    results.put(desc, desc);
                }
            }
            
        } catch (LlmRateLimiter.LlmRateLimitException e) {
            log.warn("LLM Batch normalization rate limited, falling back to originals: {}", e.getMessage());
            // Fall back to original descriptions
            for (String desc : descriptions) {
                results.put(desc, desc);
            }
        } catch (Exception e) {
            log.warn("LLM Batch normalization failed, falling back to originals: {}", e.getMessage());
            // Fall back to original descriptions
            for (String desc : descriptions) {
                results.put(desc, desc);
            }
        }
        
        return results;
    }
    
    /**
     * Clean LLM response by removing code fences and trimming.
     */
    private String cleanLLMResponse(String rawResponse) {
        String cleaned = rawResponse.trim();
        String fullFenceStart = JSON_CODE_FENCE + JSON_MARKER;
        
        if (cleaned.startsWith(fullFenceStart)) {
            cleaned = cleaned.substring(fullFenceStart.length()).trim();
        } else if (cleaned.startsWith(JSON_CODE_FENCE)) {
            cleaned = cleaned.substring(JSON_CODE_FENCE.length()).trim();
        }
        
        if (cleaned.endsWith(JSON_CODE_FENCE)) {
            cleaned = cleaned.substring(0, cleaned.lastIndexOf(JSON_CODE_FENCE)).trim();
        }
        
        return cleaned;
    }
    
    /**
     * DTO for parsing batch normalization response.
     */
    private static class NormalizedDescription {
        private int index;
        private String normalized;
        
        public int getIndex() {
            return index;
        }
        
        public String getNormalized() {
            return normalized;
        }
    }

    /**
     * Normalizes a transaction description.
     * Uses local normalization (fast, no API cost) when useLocalNormalization is true,
     * otherwise falls back to LLM-based normalization.
     * 
     * @param transactionDescription The raw transaction description
     * @return Normalized description
     */
    private String normalizeDescription(String transactionDescription) {
        if (useLocalNormalization) {
            long start = System.currentTimeMillis();
            String result = localNormalizer.normalize(transactionDescription);
            long duration = System.currentTimeMillis() - start;
            log.debug("NORMALIZE_TIMING: Local normalization took {} ms for: {}", 
                    duration, transactionDescription);
            return result;
        }
        
        // LLM-based normalization (legacy path)
        return normalizeDescriptionWithLlm(transactionDescription);
    }
    
    /**
     * Normalizes a transaction description using LLM.
     * This is the legacy implementation, kept for backward compatibility.
     * 
     * @param transactionDescription The raw transaction description
     * @return Normalized description
     */
    private String normalizeDescriptionWithLlm(String transactionDescription) {
        long start = System.currentTimeMillis();
        
        // Use the rate limiter with retry support
        // Falls back to original description if rate limited or all retries fail
        String result = llmRateLimiter.executeWithRetry(
            () -> {
                PromptTemplate promptTemplate = new PromptTemplate(normalizeDescriptionPromptResource);
                Map<String, Object> model = Map.of(
                        "transactionDescription", transactionDescription
                );

                // Create, call, and return the response content
                return chatClient.prompt(promptTemplate.create(model))
                        .call()
                        .content()
                        .trim(); // Always good practice to trim the output
            },
            transactionDescription // Fallback to original description
        );
        
        long duration = System.currentTimeMillis() - start;
        log.debug("LLM_TIMING: normalizeDescription LLM call took {} ms for: {}", 
                duration, transactionDescription);
        
        return result;
    }
}
