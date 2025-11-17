package com.everrich.spendmanager.service;

import org.springframework.ai.chat.client.ChatClient;
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
    private final VectorStoreService vectorStoreService;

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

        //log.info("Querying Vector Store for: '" + newTransactionDescription + "' with Operation: " + newTransactionOperation.name());
        String context = vectorStoreService.similaritySearch(newTransactionDescription, newTransactionOperation.name());
        String availableCategories = categoryService.findAll().stream()
                .map(c -> c.getName())
                .collect(Collectors.joining(", "));

        // ----------------------------------------
        // Step 3: Generation (Call the LLM with the context and query)
        // ----------------------------------------
        String newTransactionDescriptionWithOperation = newTransactionDescription + " (Operation: "
                + newTransactionOperation.name() + ")";

        PromptTemplate promptTemplate = new PromptTemplate(categoryPromptResource);

        Map<String, Object> promptParameters = Map.of(
                "context", context.isBlank() ? "No historical context found." : context,
                "newTransactionDescription", newTransactionDescriptionWithOperation, // Use the augmented description
                "categories", availableCategories);

        Prompt prompt = promptTemplate.create(promptParameters);
        String response = chatClient.prompt(prompt)
                .call()
                .content();

        return response.trim();
    }
}