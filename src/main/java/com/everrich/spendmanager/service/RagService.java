package com.everrich.spendmanager.service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import com.everrich.spendmanager.dto.BatchCategorizationResult;
import com.everrich.spendmanager.dto.BatchCategorizationResult.CategoryAssignment;
import com.everrich.spendmanager.entities.Transaction;
import com.everrich.spendmanager.entities.TransactionOperation;

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
import java.util.ArrayList;
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
    
    // Token estimation constants
    private static final int CHARS_PER_TOKEN = 4;  // Conservative estimate for English text
    private static final int MAX_INPUT_TOKENS = 800000;  // Leave buffer from 1M limit
    private static final int MAX_OUTPUT_TOKENS = 6000;   // Leave buffer from 8K limit
    private static final int SAFETY_BUFFER = 50000;      // Additional safety margin
    private static final int ESTIMATED_OUTPUT_TOKENS_PER_TRANSACTION = 15; // {"index": N, "category": "Name"},
    
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
     * Batch categorize multiple transactions in a single LLM call.
     * If the batch is too large for the token limit, it will be split into chunks.
     * 
     * @param transactions List of transactions to categorize
     * @return Map of transaction index (0-based) to category name
     */
    public Map<Integer, String> findBestCategoriesBatch(List<Transaction> transactions) {
        if (transactions == null || transactions.isEmpty()) {
            return new HashMap<>();
        }
        
        long methodStart = System.currentTimeMillis();
        log.info("LLM_TIMING: Starting batch categorization for {} transactions", transactions.size());
        
        // Get available categories once (shared across all chunks)
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
        
        // Calculate fixed token costs
        int fixedTokenCost = estimateTokens(availableCategories) + estimateTokens(context);
        
        // Estimate prompt template overhead (approximately 500 tokens for the template text)
        int templateOverhead = 500;
        
        // Calculate available tokens for transactions
        int availableInputTokens = MAX_INPUT_TOKENS - fixedTokenCost - templateOverhead - SAFETY_BUFFER;
        
        // Calculate max transactions per chunk based on output token limit
        int maxTransactionsForOutput = MAX_OUTPUT_TOKENS / ESTIMATED_OUTPUT_TOKENS_PER_TRANSACTION;
        
        log.info("Token budget - Fixed cost: {}, Available for transactions: {}, Max for output: {}", 
                fixedTokenCost, availableInputTokens, maxTransactionsForOutput);
        
        // Chunk transactions if needed
        List<List<Transaction>> chunks = chunkTransactions(transactions, availableInputTokens, maxTransactionsForOutput);
        
        log.info("Split {} transactions into {} chunk(s)", transactions.size(), chunks.size());
        
        // Process each chunk and collect results
        Map<Integer, String> results = new HashMap<>();
        int globalIndex = 0;
        
        for (int chunkIdx = 0; chunkIdx < chunks.size(); chunkIdx++) {
            List<Transaction> chunk = chunks.get(chunkIdx);
            log.info("Processing chunk {}/{} with {} transactions", chunkIdx + 1, chunks.size(), chunk.size());
            
            try {
                Map<Integer, String> chunkResults = processBatchChunk(chunk, availableCategories, context);
                
                // Map chunk-local indices to global indices
                for (int localIdx = 0; localIdx < chunk.size(); localIdx++) {
                    String category = chunkResults.get(localIdx + 1); // Chunk uses 1-based indexing
                    if (category != null) {
                        results.put(globalIndex + localIdx, category);
                    }
                }
            } catch (Exception e) {
                log.error("Batch chunk {} failed, falling back to individual categorization: {}", 
                        chunkIdx + 1, e.getMessage());
                
                // Fallback: categorize each transaction individually
                for (int localIdx = 0; localIdx < chunk.size(); localIdx++) {
                    try {
                        String category = findBestCategory(chunk.get(localIdx));
                        results.put(globalIndex + localIdx, category);
                    } catch (Exception ex) {
                        log.error("Individual categorization failed for transaction at index {}: {}", 
                                globalIndex + localIdx, ex.getMessage());
                        results.put(globalIndex + localIdx, "Other"); // Default fallback
                    }
                }
            }
            
            globalIndex += chunk.size();
        }
        
        long totalDuration = System.currentTimeMillis() - methodStart;
        log.info("LLM_TIMING: findBestCategoriesBatch total took {} ms for {} transactions", 
                totalDuration, transactions.size());
        log.info("Batch categorization completed. Categorized {} transactions.", results.size());
        return results;
    }
    
    /**
     * Process a single batch chunk of transactions.
     * 
     * @param chunk List of transactions in this chunk
     * @param availableCategories Comma-separated list of available categories
     * @param context Aggregated context from vector store
     * @return Map of 1-based index to category name
     */
    private Map<Integer, String> processBatchChunk(List<Transaction> chunk, 
                                                    String availableCategories, 
                                                    String context) {
        long chunkStart = System.currentTimeMillis();
        
        // Build transactions list for prompt
        StringBuilder transactionsBuilder = new StringBuilder();
        for (int i = 0; i < chunk.size(); i++) {
            Transaction t = chunk.get(i);
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
                "transactionCount", chunk.size());
        
        Prompt prompt = promptTemplate.create(promptParameters);
        
        log.debug("Batch prompt content length: {} characters", prompt.getContents().length());
        log.info("LLM_TIMING: Batch prompt prepared, content length: {} characters, {} transactions", 
                prompt.getContents().length(), chunk.size());
        
        // Call LLM
        long llmCallStart = System.currentTimeMillis();
        String response = chatClient.prompt(prompt)
                .call()
                .content();
        long llmCallDuration = System.currentTimeMillis() - llmCallStart;
        log.info("LLM_TIMING: Batch LLM call took {} ms for {} transactions", 
                llmCallDuration, chunk.size());
        
        log.debug("LLM batch response: {}", response);
        
        // Parse response
        long parseStart = System.currentTimeMillis();
        Map<Integer, String> result = parseBatchResponse(response, chunk.size());
        long parseDuration = System.currentTimeMillis() - parseStart;
        log.info("LLM_TIMING: Batch response parsing took {} ms", parseDuration);
        
        long chunkDuration = System.currentTimeMillis() - chunkStart;
        log.info("LLM_TIMING: processBatchChunk total took {} ms for {} transactions", 
                chunkDuration, chunk.size());
        
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
    
    /**
     * Chunk transactions based on token limits.
     * 
     * @param transactions All transactions to process
     * @param availableInputTokens Available input tokens for transactions
     * @param maxTransactionsForOutput Max transactions based on output token limit
     * @return List of transaction chunks
     */
    private List<List<Transaction>> chunkTransactions(List<Transaction> transactions, 
                                                       int availableInputTokens,
                                                       int maxTransactionsForOutput) {
        List<List<Transaction>> chunks = new ArrayList<>();
        List<Transaction> currentChunk = new ArrayList<>();
        int currentTokens = 0;
        
        for (Transaction txn : transactions) {
            int txnTokenCost = estimateTransactionTokenCost(txn);
            
            // Check if adding this transaction would exceed limits
            boolean exceedsInputLimit = currentTokens + txnTokenCost > availableInputTokens;
            boolean exceedsOutputLimit = currentChunk.size() >= maxTransactionsForOutput;
            
            if ((exceedsInputLimit || exceedsOutputLimit) && !currentChunk.isEmpty()) {
                // Start a new chunk
                chunks.add(currentChunk);
                currentChunk = new ArrayList<>();
                currentTokens = 0;
            }
            
            currentChunk.add(txn);
            currentTokens += txnTokenCost;
        }
        
        // Add the last chunk if not empty
        if (!currentChunk.isEmpty()) {
            chunks.add(currentChunk);
        }
        
        return chunks;
    }
    
    /**
     * Estimate token count for a string using character count heuristic.
     */
    private int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return text.length() / CHARS_PER_TOKEN;
    }
    
    /**
     * Estimate token cost for a single transaction in the prompt.
     */
    private int estimateTransactionTokenCost(Transaction txn) {
        // Format: "N. Description: "description" (Operation: TYPE)\n"
        // Estimate: index(5) + template(30) + description + operation(10)
        int descriptionTokens = estimateTokens(txn.getDescription());
        return 45 + descriptionTokens;
    }
}
