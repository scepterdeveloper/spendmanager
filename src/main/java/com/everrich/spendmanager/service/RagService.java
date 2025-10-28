package com.everrich.spendmanager.service;

import org.springframework.ai.chat.client.ChatClient;
// import org.springframework.ai.vectorstore.VectorStore; // Removed, now using VectorStoreService
import org.springframework.stereotype.Service;

import com.everrich.spendmanager.entities.TransactionOperation;

import org.springframework.ai.document.Document;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class RagService {

    private final ChatClient chatClient;
    private final CategoryService categoryService;
    private final VectorStoreService vectorStoreService; // Now injected

    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    @Value("classpath:/prompts/category-rag-prompt.st")
    private Resource categoryPromptResource;

    public RagService(VectorStoreService vectorStoreService, CategoryService categoryService,
            ChatClient.Builder chatClientBuilder) {
        this.vectorStoreService = vectorStoreService;
        this.chatClient = chatClientBuilder.build();
        this.categoryService = categoryService;
    }

    /**
     * Finds the best category for a new transaction by using RAG.
     * * @param newTransactionDescription The description of the new transaction.
     * 
     * @param newTransactionOperation 🟢 The operation type (PLUS or MINUS) of the
     *                                new transaction.
     * @return The determined category name (e.g., "Groceries").
     */
    public String findBestCategory(String newTransactionDescription, TransactionOperation newTransactionOperation) {

        log.info("\n>>> RAG INFERENCE START <<<");
        log.info("Querying Vector Store for: '" + newTransactionDescription + "' with Operation: "
                + newTransactionOperation.name());

        // ----------------------------------------
        // Step 1: Retrieval (Search the VectorStore for similar transactions)
        // ----------------------------------------

        // 🟢 CRITICAL CHANGE: Augment the query with the operation name.
        // This ensures we search for the specific vector (e.g., "PAYPAL MINUS").
        String augmentedQuery = newTransactionDescription + " " + newTransactionOperation.name();

        // NOTE: If you are using an internal normalizeDescription method before
        // similaritySearch,
        // you should pass the augmentedQuery through it:
        // String cleanedAugmentedQuery = normalizeDescription(augmentedQuery);

        List<Document> relevantDocuments = vectorStoreService.similaritySearch(augmentedQuery, 6);

        // ----------------------------------------
        // Step 2: Augmentation (Format the retrieved context for the LLM)
        // ----------------------------------------

        // Extract the content (description and category) from the Documents
        String context = relevantDocuments.stream()
                .map(doc -> {
                    // Expected format: "Description | OPERATION | Category"
                    String fullContent = doc.getText();
                    String[] parts = fullContent.split(" \\| ");

                    String description;
                    String categoryFromContent;

                    // 🟢 CRITICAL CHANGE: Check for 3 parts (Description, Operation, Category)
                    if (parts.length >= 3) {
                        description = parts[0];
                        // parts[1] is the operation (which we skip in the final prompt context)
                        categoryFromContent = parts[2]; // Category is now the third part (index 2)
                    } else {
                        // Fallback for documents that might still be in the old 2-part format
                        // (You should re-index your entire database to avoid this!)
                        description = fullContent;
                        categoryFromContent = "UNCATEGORIZED_MISSING";
                    }

                    String cleanCategory = categoryFromContent.equalsIgnoreCase("null")
                            ? "UNCATEGORIZED_MISSING"
                            : categoryFromContent;

                    return String.format(
                            "Description: '%s', Corrected Category: '%s'",
                            description,
                            cleanCategory);
                })
                .collect(Collectors.joining("\n---\n"));

        // 🟢 LOGGING: The Retrieved Context (Unchanged)
        log.info("\n--- RETRIEVED CONTEXT ---");
        log.info("Total Documents Retrieved: " + relevantDocuments.size());
        if (relevantDocuments.isEmpty()) {
            log.info("No similar historical data found. Relying on base model only.");
        } else {
            log.info("Context Snippets:\n" + context);
        }
        log.info("-------------------------");

        // Get a list of all available categories to restrict the model's output
        // (Unchanged)
        String availableCategories = categoryService.findAll().stream()
                .map(c -> c.getName())
                .collect(Collectors.joining(", "));

        // ----------------------------------------
        // Step 3: Generation (Call the LLM with the context and query)
        // ----------------------------------------

        // 🟢 CRITICAL CHANGE: Augment the description in the final prompt
        // so the LLM knows the direction (PLUS/MINUS) of the new transaction.
        String newTransactionDescriptionWithOperation = newTransactionDescription + " (Operation: "
                + newTransactionOperation.name() + ")";

        PromptTemplate promptTemplate = new PromptTemplate(categoryPromptResource);

        Map<String, Object> promptParameters = Map.of(
                "context", context.isBlank() ? "No historical context found." : context,
                "newTransactionDescription", newTransactionDescriptionWithOperation, // Use the augmented description
                "categories", availableCategories);

        Prompt prompt = promptTemplate.create(promptParameters);

        // 🟢 LOGGING: The Final Prompt and Categories (Unchanged)
        log.info("\n--- LLM PROMPT DATA ---");
        log.info("Available Categories: " + availableCategories);
        // Only log a snippet of the potentially long prompt
        String promptSnippet = prompt.getContents().substring(0, Math.min(prompt.getContents().length(), 500))
                + (prompt.getContents().length() > 500 ? "..." : "");
        log.info("Final Prompt Snippet:\n" + promptSnippet);
        log.info("Final Prompt: " + prompt);
        log.info("-----------------------");

        String response = chatClient.prompt(prompt)
                .call()
                .content();

        // 🟢 LOGGING: The Final Result (Unchanged)
        log.info("Final LLM Response (Category): " + response.trim());
        log.info("<<< RAG INFERENCE END >>>\n");

        return response.trim();
    }
}