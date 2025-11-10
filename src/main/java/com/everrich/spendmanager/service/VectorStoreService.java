package com.everrich.spendmanager.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.stereotype.Service;

import com.everrich.spendmanager.entities.TransactionOperation;

import java.util.List;
import java.util.Map;

@Service
public class VectorStoreService {

    private final ChatClient chatClient;
    private RedisAdapter redisAdapter;
    private static final Logger log = LoggerFactory.getLogger(VectorStoreService.class);

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
            TransactionOperation operation) {

        // 1. 🟢 Apply the cleaning logic to the description before indexing
        String cleanedDescription = normalizeDescription(transactionDescription);
        String operationName = operation.name(); // Get the string "PLUS" or "MINUS"
        redisAdapter.createDocument(correctCategory, cleanedDescription, operationName);

    }

    public String similaritySearch(String description, String operationName) {

        String queryText = normalizeDescription(description);
        List<RedisDocument> searchResults = redisAdapter.searchDocuments(queryText, operationName);
        String context = "";

        for (RedisDocument redisDocument : searchResults) {

            context += "Description: " + redisDocument.getFields().get("description_op") + ", Corrected Category: "
                    + redisDocument.getFields().get("category") + "\n";
        }

        return context;
    }

    // LLM based
    private String normalizeDescription(String transactionDescription) {

        // 1. Define the prompt template string
        String template = """
                You are an expert financial text processor. Analyze the following raw transaction description and return the keywords as a string that best describes the transaction. Below are some samples:

                Examples:
                   - Input: 'UNICREDIT BANK GMBH Kto.0046348710 PER 31.07.25...'
                   - Output: Unicredit Bank GMBH
                   - Input: 'Bargeldein-/auszahlung Deutsche Bank//Wiesloch/DE 2025-10-23T19:07:36 ...'
                   - Output: Bargeldein-/auszahlung Deutsche Bank Wiesloch
                   - Input: 'Überweisung (Echtzeit) Sandeep Joseph COBADEHD055 DE212004115508674269...'
                   - Output: Überweisung Echtzeit Sandeep Joseph

                Raw Description: {transactionDescription}
                            """;

        PromptTemplate promptTemplate = new PromptTemplate(template);

        // 2. Map the input parameter.
        // The key MUST exactly match the placeholder in the template:
        // {transactionDescription}
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