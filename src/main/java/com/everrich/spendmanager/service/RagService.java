package com.everrich.spendmanager.service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import com.everrich.spendmanager.dto.BatchCategorizationResult.CategoryAssignment;
import com.everrich.spendmanager.entities.Transaction;

import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class RagService {

    private final ChatClient chatClient;
    private final CategoryService categoryService;
    private final VectorStoreService vectorStoreService;
    private final Gson gson;

    private static final Logger log = LoggerFactory.getLogger(RagService.class);
    
    private static final String JSON_CODE_FENCE = "```";
    private static final String JSON_MARKER = "json";

    @Value("classpath:/prompts/category-rag-prompt.st")
    private Resource categoryPromptResource;
    
    @Value("classpath:/prompts/category-rag-prompt-batch.st")
    private Resource categoryBatchPromptResource;

    public RagService(VectorStoreService vectorStoreService, CategoryService categoryService,
            ChatClient.Builder chatClientBuilder) {
        this.vectorStoreService = vectorStoreService;
        this.chatClient = chatClientBuilder.build();
        this.categoryService = categoryService;
        this.gson = new GsonBuilder().create();
    }


    public String findBestCategory(Transaction transaction) {
        long methodStart = System.currentTimeMillis();
        
        long similaritySearchStart = System.currentTimeMillis();
        String context = vectorStoreService.similaritySearch(transaction);
        long similaritySearchDuration = System.currentTimeMillis() - similaritySearchStart;
        log.info("LLM_TIMING: similaritySearch took {} ms for transaction: {}", 
                similaritySearchDuration, transaction.getDescription());
        
        String availableCategories = categoryService.findAll().stream()
                .map(c -> c.getName())
                .collect(Collectors.joining(", "));

        // ----------------------------------------
        // Step 3: Generation (Call the LLM with the context and query)
        // ----------------------------------------
        String newTransactionDescriptionWithOperation = transaction.getDescription() + " (Operation: "
                + transaction.getOperation().name() + ")";

        PromptTemplate promptTemplate = new PromptTemplate(categoryPromptResource);

        Map<String, Object> promptParameters = Map.of(
                "context", context.isBlank() ? "No historical context found." : context,
                "newTransactionDescription", newTransactionDescriptionWithOperation, // Use the augmented description
                "categories", availableCategories);

        Prompt prompt = promptTemplate.create(promptParameters);
        
        long llmCallStart = System.currentTimeMillis();
        String response = chatClient.prompt(prompt)
                .call()
                .content();
        long llmCallDuration = System.currentTimeMillis() - llmCallStart;
        log.info("LLM_TIMING: Category LLM call took {} ms for transaction: {}", 
                llmCallDuration, transaction.getDescription());
        
        long totalDuration = System.currentTimeMillis() - methodStart;
        log.info("LLM_TIMING: findBestCategory total took {} ms for transaction: {}", 
                totalDuration, transaction.getDescription());

        return response.trim();
    }
    
    /**
     * Batch categorize multiple transactions in a single synchronous LLM call.
     * This method makes ONE HTTP call to the LLM with all transactions.
     * No batching or chunking is performed to optimize for GCP Request Based billing.
     * 
     * If the LLM call fails (e.g., token limit exceeded, timeout, etc.), 
     * this method throws a RuntimeException to signal the caller to abort processing.
     * 
     * @param transactions List of transactions to categorize
     * @return Map of transaction index (0-based) to category name
     * @throws RuntimeException if the LLM call fails for any reason
     */
    public Map<Integer, String> findBestCategoriesBatch(List<Transaction> transactions) {
        if (transactions == null || transactions.isEmpty()) {
            return new HashMap<>();
        }
        
        long methodStart = System.currentTimeMillis();
        log.info("LLM_TIMING: Starting single-call batch categorization for {} transactions", transactions.size());
        
        // Get available categories
        String availableCategories = categoryService.findAll().stream()
                .map(c -> c.getName())
                .collect(Collectors.joining(", "));
        
        // Get aggregated context from vector store
        long batchSearchStart = System.currentTimeMillis();
        String context = vectorStoreService.batchSimilaritySearch(transactions);
        long batchSearchDuration = System.currentTimeMillis() - batchSearchStart;
        log.info("LLM_TIMING: batchSimilaritySearch took {} ms for {} transactions", 
                batchSearchDuration, transactions.size());
        
        if (context.isBlank()) {
            context = "No historical context found.";
        }
        
        // Process all transactions in a single LLM call (no chunking)
        try {
            Map<Integer, String> results = processSingleBatchCall(transactions, availableCategories, context);
            
            // Convert from 1-based to 0-based indexing
            Map<Integer, String> zeroBasedResults = new HashMap<>();
            for (Map.Entry<Integer, String> entry : results.entrySet()) {
                zeroBasedResults.put(entry.getKey() - 1, entry.getValue());
            }
            
            long totalDuration = System.currentTimeMillis() - methodStart;
            log.info("LLM_TIMING: findBestCategoriesBatch total took {} ms for {} transactions", 
                    totalDuration, transactions.size());
            log.info("Batch categorization completed. Categorized {} transactions.", zeroBasedResults.size());
            
            return zeroBasedResults;
            
        } catch (Exception e) {
            log.error("LLM batch categorization failed for {} transactions: {}", 
                    transactions.size(), e.getMessage(), e);
            throw new RuntimeException("LLM categorization failed: " + e.getMessage(), e);
        }
    }
    
    /**
     * Process all transactions in a single LLM call.
     * 
     * @param transactions List of transactions to categorize
     * @param availableCategories Comma-separated list of available categories
     * @param context Aggregated context from vector store
     * @return Map of 1-based index to category name
     * @throws RuntimeException if the LLM call fails
     */
    private Map<Integer, String> processSingleBatchCall(List<Transaction> transactions, 
                                                         String availableCategories, 
                                                         String context) {
        long callStart = System.currentTimeMillis();
        
        // Build transactions list for prompt
        StringBuilder transactionsBuilder = new StringBuilder();
        for (int i = 0; i < transactions.size(); i++) {
            Transaction t = transactions.get(i);
            String operationType = t.getOperation() != null ? t.getOperation().name() : "UNKNOWN";
            transactionsBuilder.append(String.format("%d. Description: \"%s\" (Operation: %s)%n", 
                    i + 1, t.getDescription(), operationType));
        }
        String transactionsList = transactionsBuilder.toString();
        
        // Create prompt
        PromptTemplate promptTemplate = new PromptTemplate(categoryBatchPromptResource);
        Map<String, Object> promptParameters = Map.of(
                "context", context,
                "transactions", transactionsList,
                "categories", availableCategories,
                "transactionCount", transactions.size());
        
        Prompt prompt = promptTemplate.create(promptParameters);
        
        log.info("LLM_TIMING: Single batch prompt prepared, content length: {} characters, {} transactions", 
                prompt.getContents().length(), transactions.size());
        
        // Call LLM - this is the single synchronous HTTP call
        long llmCallStart = System.currentTimeMillis();
        String response;
        try {
            response = chatClient.prompt(prompt)
                    .call()
                    .content();
        } catch (Exception e) {
            long llmCallDuration = System.currentTimeMillis() - llmCallStart;
            log.error("LLM call failed after {} ms: {}", llmCallDuration, e.getMessage());
            throw new RuntimeException("LLM call failed: " + e.getMessage(), e);
        }
        long llmCallDuration = System.currentTimeMillis() - llmCallStart;
        log.info("LLM_TIMING: Single batch LLM call took {} ms for {} transactions", 
                llmCallDuration, transactions.size());
        
        log.debug("LLM batch response: {}", response);
        
        // Parse response
        long parseStart = System.currentTimeMillis();
        Map<Integer, String> result = parseBatchResponse(response, transactions.size());
        long parseDuration = System.currentTimeMillis() - parseStart;
        log.info("LLM_TIMING: Batch response parsing took {} ms", parseDuration);
        
        long totalDuration = System.currentTimeMillis() - callStart;
        log.info("LLM_TIMING: processSingleBatchCall total took {} ms for {} transactions", 
                totalDuration, transactions.size());
        
        return result;
    }
    
    /**
     * Parse the LLM batch response JSON into a map of indices to categories.
     * 
     * @param response Raw LLM response
     * @param expectedCount Expected number of categorizations
     * @return Map of 1-based index to category name
     */
    private Map<Integer, String> parseBatchResponse(String response, int expectedCount) {
        Map<Integer, String> results = new HashMap<>();
        
        // Clean the response (remove code fences if present)
        String cleanedResponse = cleanLLMResponse(response);
        
        try {
            // Parse as array of CategoryAssignment
            Type listType = new TypeToken<List<CategoryAssignment>>(){}.getType();
            List<CategoryAssignment> assignments = gson.fromJson(cleanedResponse, listType);
            
            if (assignments != null) {
                for (CategoryAssignment assignment : assignments) {
                    results.put(assignment.getIndex(), assignment.getCategory());
                }
            }
            
            log.info("Successfully parsed {} category assignments from LLM response", results.size());
            
            if (results.size() != expectedCount) {
                log.warn("Expected {} categorizations but got {}. Some transactions may need individual processing.", 
                        expectedCount, results.size());
            }
        } catch (Exception e) {
            log.error("Failed to parse batch response JSON: {}. Response was: {}", e.getMessage(), cleanedResponse);
            throw new RuntimeException("Failed to parse LLM batch categorization response", e);
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
    
}
