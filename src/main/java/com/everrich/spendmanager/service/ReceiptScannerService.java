package com.everrich.spendmanager.service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Base64;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;

import com.everrich.spendmanager.dto.ScannedTransactionDTO;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Service for scanning receipt images and extracting transaction information using AI.
 * Uses Google's Gemini multimodal model via Spring AI to analyze receipt images.
 */
@Service
public class ReceiptScannerService {

    private static final Logger log = LoggerFactory.getLogger(ReceiptScannerService.class);
    private static final String JSON_CODE_FENCE = "```";
    private static final String JSON_MARKER = "json";

    private final ChatClient chatClient;
    private final Gson gson;

    @Value("classpath:/prompts/parse-receipt-prompt.st")
    private Resource parseReceiptPromptResource;

    public ReceiptScannerService(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
        this.gson = new GsonBuilder()
                .registerTypeAdapter(LocalDate.class,
                        (JsonDeserializer<LocalDate>) (json, typeOfT, context) -> LocalDate.parse(json.getAsString(),
                                DateTimeFormatter.ofPattern("dd.MM.yyyy")))
                .create();
    }

    /**
     * Scans a receipt image and extracts transaction information.
     *
     * @param imageBytes The image data as a byte array
     * @param mimeType   The MIME type of the image (e.g., "image/jpeg", "image/png")
     * @return ScannedTransactionDTO containing the extracted transaction data
     * @throws ReceiptScanException if the image cannot be processed or the AI fails to extract data
     */
    public ScannedTransactionDTO scanReceipt(byte[] imageBytes, String mimeType) throws ReceiptScanException {
        log.info("Starting receipt scan, image size: {} bytes, MIME type: {}", imageBytes.length, mimeType);

        try {
            // Load the prompt template
            String promptText = new String(parseReceiptPromptResource.getInputStream().readAllBytes());
            log.debug("Loaded prompt template for receipt scanning");

            // Create a Resource from the image bytes
            Resource imageResource = new ByteArrayResource(imageBytes);
            MimeType imageMimeType = MimeType.valueOf(mimeType);
            
            log.info("Calling Gemini AI for receipt analysis...");
            
            // Use ChatClient fluent API with media
            String llmResponse = chatClient.prompt()
                    .user(u -> u.text(promptText).media(imageMimeType, imageResource))
                    .call()
                    .content();

            log.info("Received AI response, processing...");
            log.debug("Raw AI response: {}", llmResponse);

            // Clean and parse the response
            String cleanJson = cleanLLMResponse(llmResponse);
            log.debug("Cleaned JSON: {}", cleanJson);

            ScannedTransactionDTO result = parseResponse(cleanJson);
            log.info("Successfully extracted transaction: {}", result);

            return result;

        } catch (Exception e) {
            log.error("Error scanning receipt: {}", e.getMessage(), e);
            throw new ReceiptScanException("Failed to extract transaction from receipt: " + e.getMessage(), e);
        }
    }

    /**
     * Cleans the LLM response by removing code fences if present.
     */
    private String cleanLLMResponse(String rawLLMResponse) {
        String cleaned = rawLLMResponse.trim();
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
     * Parses the cleaned JSON response into a ScannedTransactionDTO.
     */
    private ScannedTransactionDTO parseResponse(String json) throws ReceiptScanException {
        try {
            // First validate it's valid JSON
            JsonObject jsonObject = JsonParser.parseString(json).getAsJsonObject();
            
            // Check for required fields
            if (!jsonObject.has("date") || !jsonObject.has("description") || 
                !jsonObject.has("amount") || !jsonObject.has("operation")) {
                throw new ReceiptScanException("AI response missing required fields");
            }

            return gson.fromJson(json, ScannedTransactionDTO.class);
        } catch (Exception e) {
            log.error("Failed to parse AI response: {}", json);
            throw new ReceiptScanException("Failed to parse AI response: " + e.getMessage(), e);
        }
    }

    /**
     * Custom exception for receipt scanning errors.
     */
    public static class ReceiptScanException extends Exception {
        public ReceiptScanException(String message) {
            super(message);
        }

        public ReceiptScanException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}