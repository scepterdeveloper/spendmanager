package com.everrich.spendmanager.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.stereotype.Service;

import com.everrich.spendmanager.entities.TransactionOperation;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.StringCodec;
import io.lettuce.core.output.CommandOutput;
import io.lettuce.core.output.StatusOutput;
import io.lettuce.core.protocol.CommandArgs;
import io.lettuce.core.protocol.ProtocolKeyword;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Service
public class VectorStoreService {

    // private final VectorStore vectorStore;
    private final ChatClient chatClient;
    private RedisAdapter redisAdapter;
    private static final Logger log = LoggerFactory.getLogger(VectorStoreService.class);

    public VectorStoreService(RedisAdapter redisAdapter, ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
        this.redisAdapter = redisAdapter;

        if (redisAdapter == null) {
            log.info("RedisAdapter is null while wiring VectorStore");
        }
        if (chatClient == null) {
            log.info("Chat Client is null while wiring VectorStore");
        }

        this.createTransactionIndex();
    }

    private void createTransactionIndex() {

        try {
            log.info("Creating index in Redis...Index Name: " + redisAdapter.getIndexName());
            log.info("Connection Params: Redis URI - " + redisAdapter.getRedisURI());

        } catch (Exception e) {
            log.info("Error connecting to Redis: " + e.getMessage());
        }

        RedisClient redisClient = RedisClient.create(RedisURI.create(redisAdapter.getRedisURI()));
        StatefulRedisConnection<String, String> connection = redisClient.connect();
        RedisCommands<String, String> syncCommands = connection.sync();
        ProtocolKeyword ftCreateCommand = new ProtocolKeyword() {
            public byte[] getBytes() {
                return "FT.CREATE".getBytes(StandardCharsets.UTF_8);
            }
        };

        CommandArgs<String, String> argsBuilder = new CommandArgs<>(StringCodec.UTF8);
        argsBuilder.add(redisAdapter.getIndexName());
        argsBuilder.add("ON").add("HASH");
        argsBuilder.add("PREFIX").add(1).add("doc:");
        argsBuilder.add("SCHEMA");
        argsBuilder.add("description_op").add("TEXT");
        argsBuilder.add("content_payload").add("TEXT");
        argsBuilder.add("category").add("TAG");
        argsBuilder.add("operation").add("TAG");
        argsBuilder.add("vector").add("AS").add("vector");
        argsBuilder.add("VECTOR");
        argsBuilder.add("FLAT");
        argsBuilder.add("6");
        argsBuilder.add("TYPE").add("FLOAT32");
        argsBuilder.add("DIM").add(redisAdapter.getVectorDimension());
        argsBuilder.add("DISTANCE_METRIC").add("COSINE");

        try {

            CommandOutput<String, String, String> output = new StatusOutput<>(StringCodec.UTF8);
            String result = syncCommands.dispatch(ftCreateCommand, output, argsBuilder);
            log.info("FT.CREATE command executed.");
            log.info("Result: " + result);

        } catch (Exception e) {
            log.info("Index already exists, or creation failed.");
        } finally {
            connection.close();
            redisClient.shutdown();
            log.info("\nConnection closed.");
        }
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