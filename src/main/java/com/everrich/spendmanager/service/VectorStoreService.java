package com.everrich.spendmanager.service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import com.everrich.spendmanager.entities.TransactionOperation;

import org.springframework.ai.vectorstore.SearchRequest;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;

@Service
public class VectorStoreService {

    private final VectorStore vectorStore;
    private final ChatClient chatClient;

    public VectorStoreService(VectorStore vectorStore, ChatClient.Builder chatClientBuilder) {
        this.vectorStore = vectorStore;
        this.chatClient = chatClientBuilder.build();
    }

/**
 * Creates Documents from a user's manual category correction and stores them
 * in the VectorStore for future RAG queries. This includes **Data
 * Augmentation** using the Description and the Operation for high retrieval specificity.
 * * @param transactionDescription The raw description to be embedded.
 * @param correctCategory        The human-corrected category (the knowledge).
 * @param amount                 The transaction amount (ignored for indexing).
 * @param operation              🟢 The operation type (PLUS or MINUS).
 */
public void learnCorrectCategory(String transactionDescription, String correctCategory, double amount, TransactionOperation operation) {

    // 1. 🟢 Apply the cleaning logic to the description before indexing
    String cleanedDescription = normalizeDescription(transactionDescription);
    String operationName = operation.name(); // Get the string "PLUS" or "MINUS"

    // 2. 🟢 CONCATENATE ALL KNOWLEDGE: Description | OPERATION | Category
    // Example content: "paypal europe | MINUS | Internal Transfer - Outgoing"
    String contentWithOperationAndCategory = cleanedDescription + 
                                           " | " + operationName + 
                                           " | " + correctCategory;

    // 3. Define the minimal metadata
    Map<String, Object> metadata = Map.of(
            "category", correctCategory,
            "operation", operationName // Store operation in metadata as well (for redundancy)
    );

    // 4. Data Augmentation: Index multiple versions for better retrieval (higher recall)
    List<Document> documentsToStore = new ArrayList<>();
    
    // --- Strategy 1: Full Description + Operation (Search Key) ---
    // The searchable key includes the operation, but the content holds all the knowledge.
    String searchKey1 = cleanedDescription + " " + operationName; 
    
    // Store the full knowledge string as the content payload.
    documentsToStore.add(new Document(contentWithOperationAndCategory, metadata));

    // --- Strategy 2: Key Vendor Phrases + Operation (CRITICAL for specificity) ---
    String[] words = cleanedDescription.split(" ");
    
    // Index the first two words + Operation (e.g., "paypal europe MINUS")
    if (words.length >= 2) {
        String keyPhrase = words[0] + " " + words[1];
        if (keyPhrase.length() > 5) {
            String keyPhraseContent = keyPhrase + " | " + operationName + " | " + correctCategory;
            documentsToStore.add(new Document(keyPhraseContent, metadata));
        }
    }

    // Index the single most important word + Operation (e.g., "paypal MINUS")
    if (words.length >= 1) {
        String singleKeyword = words[0];
        if (singleKeyword.length() > 3) {
            String singleKeywordContent = singleKeyword + " | " + operationName + " | " + correctCategory;
            documentsToStore.add(new Document(singleKeywordContent, metadata));
        }
    }

    // 5. Add all generated documents.
    vectorStore.add(documentsToStore);

    // 🟢 LOGGING: Confirming Vector Store update
    System.out.println("--- RAG LEARNING EVENT ---");
    System.out.println("Indexed " + documentsToStore.size() + " documents to Vector Store:");
    System.out.println("  Content with Operation and Category: '" + contentWithOperationAndCategory + "'");
    System.out.println("--------------------------");
}
    /**
     * RAG Retrieval (Querying): Cleans the description before searching the
     * VectorStore.
     * * @param description The raw transaction description to search for.
     * 
     * @param topK The number of nearest documents to retrieve. **Increased to 10**
     *             for better recall.
     * @return A list of relevant Document objects (context).
     */
    public List<Document> similaritySearch(String description, int topK) {
        // 1. Apply the same cleaning logic to the query text
        String queryText = normalizeDescription(description);

        // 2. Perform the search using the cleaned text
        return vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(queryText)
                        .topK(topK)
                        .build());
    }

    // -------------------------------------------------------------------------
    // RAG HELPER FUNCTION
    // -------------------------------------------------------------------------

    /**
     * PRIVATE HELPER: Cleans transaction text by removing numbers and punctuation.
     * This must be applied consistently to both indexing and querying text.
     */
    private String normalizeDescription_old(String description) {

        if (description == null) {
            return "";
        }

        // 1. Convert to lowercase
        String cleaned = description.toLowerCase();

        // 2. Remove all digits (0-9)
        cleaned = cleaned.replaceAll("\\d", "");

        // 3. Remove most common special characters (keeping only letters and spaces)
        cleaned = cleaned.replaceAll("[^a-z\\s]", " ");

        // 4. Collapse multiple spaces and trim
        cleaned = cleaned.replaceAll("\\s+", " ").trim();

        return cleaned;
    }

    // Assuming this method exists within a class that has access to ChatClient
    // chatClient
    // and LlmGateway (recommended). For simplicity, using raw ChatClient here:

    // LLM based
    private String normalizeDescription(String transactionDescription) {

        // 1. Define the prompt template string
        String template = """
                You are an expert financial text processor. Analyze the following raw transaction description and return only the single, most essential vendor or service name that defines the transaction's purpose.

                RULES:
                1. Return only the core name, nothing else.
                2. Examples:
                   - Input: 'UNICREDIT BANK GMBH Kto.0046348710 PER 31.07.25...'
                   - Output: Unicredit Bank
                   - Input: 'Yau Yuet Chi INGDDEFF DE83500105175435016080 Le...'
                   - Output: Yau Yeut Chi
                   - Input: 'PayPal Europe S.a.r.l. et Cie S.C.A 10436995017...'
                   - Output: PayPal

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