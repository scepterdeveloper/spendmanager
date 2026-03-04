package com.everrich.spendmanager.service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import com.everrich.spendmanager.dto.BatchCategorizationResult.CategoryAssignment;
import com.everrich.spendmanager.entities.Transaction;

import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.task.TaskExecutor;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@Service
public class RagService {

    private final ChatClient chatClient;
    private final CategoryService categoryService;
    private final VectorStoreService vectorStoreService;
    private final TaskExecutor taskExecutor;
    private final Gson gson;

    private static final Logger log = LoggerFactory.getLogger(RagService.class);
    
    private static final String JSON_CODE_FENCE = "```";
    private static final String JSON_MARKER = "json";

    @Value("classpath:/prompts/category-rag-prompt.st")
    private Resource categoryPromptResource;
    
    @Value("classpath:/prompts/category-rag-prompt-batch.st")
    private Resource categoryBatchPromptResource;
    
    @Value("${spendmanager.categorization.batch-size:5}")
    private int batchSize;

    public RagService(VectorStoreService vectorStoreService, 
                      CategoryService categoryService,
                      ChatClient.Builder chatClientBuilder,
                      @Qualifier("transactionProcessingExecutor") TaskExecutor taskExecutor) {
        this.vectorStoreService = vectorStoreService;
        this.chatClient = chatClientBuilder.build();
        this.categoryService = categoryService;
        this.taskExecutor = taskExecutor;
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
     * Batch categorize multiple transactions using parallel LLM calls.
     * Transactions are split into chunks of configurable size (default: 5) and 
     * processed in parallel. The method blocks until ALL parallel calls complete,
     * maintaining synchronous behavior from the caller's perspective.
     * 
     * This approach optimizes for GCP Request-Based billing by keeping the request
     * thread alive while executing multiple concurrent LLM calls.
     * 
     * If ANY chunk fails, the entire operation fails (fail-fast behavior).
     * 
     * @param transactions List of transactions to categorize
     * @return Map of transaction index (0-based) to category name
     * @throws RuntimeException if any LLM call fails
     */
    public Map<Integer, String> findBestCategoriesBatch(List<Transaction> transactions) {
        if (transactions == null || transactions.isEmpty()) {
            return new HashMap<>();
        }
        
        long methodStart = System.currentTimeMillis();
        int totalTransactions = transactions.size();
        
        // Calculate number of chunks
        int numChunks = (int) Math.ceil((double) totalTransactions / batchSize);
        log.info("LLM_TIMING: Starting PARALLEL batch categorization for {} transactions in {} chunks (batch size: {})", 
                totalTransactions, numChunks, batchSize);
        
        // STEP 1: Get available categories (synchronous, thread-safe read)
        String availableCategories = categoryService.findAll().stream()
                .map(c -> c.getName())
                .collect(Collectors.joining(", "));
        
        // STEP 2: Get aggregated context from vector store for ALL transactions (synchronous)
        // This is done ONCE before parallel execution to avoid redundant LLM calls for normalization
        long batchSearchStart = System.currentTimeMillis();
        String context = vectorStoreService.batchSimilaritySearch(transactions);
        long batchSearchDuration = System.currentTimeMillis() - batchSearchStart;
        log.info("LLM_TIMING: batchSimilaritySearch took {} ms for {} transactions", 
                batchSearchDuration, totalTransactions);
        
        if (context.isBlank()) {
            context = "No historical context found.";
        }
        final String sharedContext = context; // Make effectively final for lambda
        
        // STEP 3: Partition transactions into chunks
        List<List<Transaction>> chunks = partitionList(transactions, batchSize);
        log.info("LLM_TIMING: Split {} transactions into {} chunks", totalTransactions, chunks.size());
        
        // STEP 4: Execute LLM calls in PARALLEL but BLOCKING
        // Using ConcurrentHashMap for thread-safe result aggregation
        ConcurrentHashMap<Integer, String> results = new ConcurrentHashMap<>();
        
        // Track first error for fail-fast behavior
        AtomicReference<Throwable> firstError = new AtomicReference<>(null);
        
        // Create CompletableFutures for each chunk
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        
        for (int chunkIndex = 0; chunkIndex < chunks.size(); chunkIndex++) {
            final int currentChunkIndex = chunkIndex;
            final List<Transaction> chunk = chunks.get(chunkIndex);
            final int globalOffset = chunkIndex * batchSize; // Offset for global indexing
            
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                // Check if another chunk already failed (fail-fast)
                if (firstError.get() != null) {
                    log.debug("Skipping chunk {} because another chunk already failed", currentChunkIndex);
                    return;
                }
                
                try {
                    log.info("LLM_TIMING: Processing chunk {} with {} transactions (global offset: {})", 
                            currentChunkIndex, chunk.size(), globalOffset);
                    
                    // Process this chunk with the shared context
                    Map<Integer, String> chunkResults = processChunkWithGlobalIndexing(
                            chunk, availableCategories, sharedContext, globalOffset);
                    
                    // Add results to the concurrent map (thread-safe)
                    results.putAll(chunkResults);
                    
                    log.info("LLM_TIMING: Chunk {} completed, categorized {} transactions", 
                            currentChunkIndex, chunkResults.size());
                    
                } catch (Exception e) {
                    log.error("LLM_TIMING: Chunk {} failed: {}", currentChunkIndex, e.getMessage());
                    // Set the first error (only the first one wins due to compareAndSet)
                    firstError.compareAndSet(null, e);
                }
            }, taskExecutor);
            
            futures.add(future);
        }
        
        // BLOCKING JOIN - Wait for ALL futures to complete
        long parallelStart = System.currentTimeMillis();
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        } catch (Exception e) {
            log.error("LLM_TIMING: Parallel execution failed: {}", e.getMessage());
            throw new RuntimeException("Parallel LLM categorization failed", e);
        }
        long parallelDuration = System.currentTimeMillis() - parallelStart;
        log.info("LLM_TIMING: All {} parallel chunks completed in {} ms", chunks.size(), parallelDuration);
        
        // Check if any chunk failed
        Throwable error = firstError.get();
        if (error != null) {
            log.error("LLM batch categorization failed (fail-fast): {}", error.getMessage());
            throw new RuntimeException("LLM categorization failed: " + error.getMessage(), error);
        }
        
        // STEP 5: Return aggregated results (already in 0-based indexing)
        long totalDuration = System.currentTimeMillis() - methodStart;
        log.info("LLM_TIMING: findBestCategoriesBatch PARALLEL total took {} ms for {} transactions ({} chunks)", 
                totalDuration, totalTransactions, chunks.size());
        log.info("Parallel batch categorization completed. Categorized {} transactions in {} chunks.", 
                results.size(), chunks.size());
        
        return new HashMap<>(results); // Return a regular HashMap copy
    }
    
    /**
     * Process a chunk of transactions with global indexing.
     * The returned map uses 0-based global indices (considering the chunk's offset in the original list).
     * 
     * @param chunk List of transactions in this chunk
     * @param availableCategories Comma-separated list of available categories
     * @param context Shared context from vector store
     * @param globalOffset The starting index of this chunk in the original transaction list
     * @return Map of 0-based global index to category name
     */
    private Map<Integer, String> processChunkWithGlobalIndexing(List<Transaction> chunk, 
                                                                  String availableCategories, 
                                                                  String context,
                                                                  int globalOffset) {
        long callStart = System.currentTimeMillis();
        
        // Build transactions list for prompt (using 1-based local indexing for the LLM)
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
        
        log.debug("LLM_TIMING: Chunk prompt prepared, content length: {} characters, {} transactions", 
                prompt.getContents().length(), chunk.size());
        
        // Call LLM
        long llmCallStart = System.currentTimeMillis();
        String response;
        try {
            response = chatClient.prompt(prompt)
                    .call()
                    .content();
        } catch (Exception e) {
            long llmCallDuration = System.currentTimeMillis() - llmCallStart;
            log.error("LLM call failed after {} ms for chunk with offset {}: {}", 
                    llmCallDuration, globalOffset, e.getMessage());
            throw new RuntimeException("LLM call failed: " + e.getMessage(), e);
        }
        long llmCallDuration = System.currentTimeMillis() - llmCallStart;
        log.info("LLM_TIMING: Chunk LLM call (offset {}) took {} ms for {} transactions", 
                globalOffset, llmCallDuration, chunk.size());
        
        log.debug("LLM chunk response (offset {}): {}", globalOffset, response);
        
        // Parse response (returns 1-based local indices)
        Map<Integer, String> localResults = parseBatchResponse(response, chunk.size());
        
        // Convert from 1-based local to 0-based global indexing
        Map<Integer, String> globalResults = new HashMap<>();
        for (Map.Entry<Integer, String> entry : localResults.entrySet()) {
            int localIndex = entry.getKey(); // 1-based
            int globalIndex = globalOffset + (localIndex - 1); // Convert to 0-based global
            globalResults.put(globalIndex, entry.getValue());
        }
        
        long totalDuration = System.currentTimeMillis() - callStart;
        log.debug("LLM_TIMING: processChunkWithGlobalIndexing total took {} ms for chunk at offset {}", 
                totalDuration, globalOffset);
        
        return globalResults;
    }
    
    /**
     * Partitions a list into smaller sublists of the specified size.
     * 
     * @param list The list to partition
     * @param size The maximum size of each partition
     * @return List of partitions
     */
    private <T> List<List<T>> partitionList(List<T> list, int size) {
        List<List<T>> partitions = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            partitions.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return partitions;
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
            
            log.debug("Successfully parsed {} category assignments from LLM response", results.size());
            
            if (results.size() != expectedCount) {
                log.warn("Expected {} categorizations but got {}. Some transactions may use fallback.", 
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