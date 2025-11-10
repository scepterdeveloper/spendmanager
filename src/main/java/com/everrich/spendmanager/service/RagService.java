package com.everrich.spendmanager.service;

import org.springframework.ai.chat.client.ChatClient;
// import org.springframework.ai.vectorstore.VectorStore; // Removed, now using VectorStoreService
import org.springframework.stereotype.Service;

import com.everrich.spendmanager.entities.TransactionOperation;

import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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


    public String findBestCategory(String newTransactionDescription, TransactionOperation newTransactionOperation) {

        log.info("\n>>> RAG INFERENCE START <<<");
        log.info("Querying Vector Store for: '" + newTransactionDescription + "' with Operation: " + newTransactionOperation.name());

        String context = vectorStoreService.similaritySearch(newTransactionDescription, newTransactionOperation.name());


        // 🟢 LOGGING: The Retrieved Context (Unchanged)
        log.info("\n--- RETRIEVED CONTEXT ---");
        if ("".equalsIgnoreCase(context)) {
            log.info("No similar historical data found. Relying on base model only.");
        } else {
            log.info("Context Snippets:\n" + context);
        }
        log.info("-------------------------");

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