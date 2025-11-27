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

import java.util.List;
import java.util.Map;

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

    public void learnCorrectCategory(String transactionDescription, String correctCategory, double amount,
            TransactionOperation operation, String accountName) {

        // 1. 🟢 Apply the cleaning logic to the description before indexing
        String cleanedDescription = normalizeDescription(transactionDescription);
        String operationName = operation.name(); // Get the string "PLUS" or "MINUS"
        redisAdapter.createDocument(correctCategory, cleanedDescription, operationName, accountName);

    }

    public String similaritySearch(Transaction transaction) {

        String description = normalizeDescription(transaction.getDescription());
        List<RedisDocument> searchResults = redisAdapter.searchDocuments(description, transaction.getOperation().name(),
                transaction.getAccount().getName());
        String context = "";

        for (RedisDocument redisDocument : searchResults) {

            context += "Description: " + redisDocument.getFields().get("description_op") + ", Corrected Category: "
                    + redisDocument.getFields().get("category") + "\n";
        }

        return context;
    }

    // LLM based
    private String normalizeDescription(String transactionDescription) {

        PromptTemplate promptTemplate = new PromptTemplate(normalizeDescriptionPromptResource);
        Map<String, Object> model = Map.of(
                "transactionDescription", transactionDescription // Corrected key and value
        );

        // 3. Create, call, and return the response content
        return chatClient.prompt(promptTemplate.create(model))
                .call()
                .content()
                .trim(); // Always good practice to trim the output
    }
}