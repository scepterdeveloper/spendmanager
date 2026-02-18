package com.everrich.spendmanager.controller;

import com.everrich.spendmanager.dto.ScannedTransactionDTO;
import com.everrich.spendmanager.entities.Transaction;
import com.everrich.spendmanager.entities.TransactionCategorizationStatus;
import com.everrich.spendmanager.entities.TransactionOperation;
import com.everrich.spendmanager.service.ReceiptScannerService;
import com.everrich.spendmanager.service.ReceiptScannerService.ReceiptScanException;
import com.everrich.spendmanager.service.TransactionService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api/transactions")
public class TransactionRESTController {

    @Autowired
    private TransactionService transactionService;
    
    @Autowired
    private ReceiptScannerService receiptScannerService;
    
    private static final Logger log = LoggerFactory.getLogger(TransactionRESTController.class);
    
    private static final long MAX_IMAGE_SIZE = 10 * 1024 * 1024; // 10 MB

    @PatchMapping("/{id}/reviewed")
    public ResponseEntity<Map<String, Object>> updateReviewedStatus(
            @PathVariable Long id,
            @RequestBody Map<String, Boolean> payload) {
        
        Boolean reviewed = payload.get("reviewed");
        if (reviewed == null) {
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "message", "Missing 'reviewed' field in request body"
            ));
        }
        
        log.info("Updating reviewed status for transaction ID {} to {}", id, reviewed);
        boolean updated = transactionService.updateReviewedStatus(id, reviewed);
        
        if (updated) {
            return ResponseEntity.ok(Map.of(
                "success", true,
                "reviewed", reviewed,
                "message", reviewed ? "Transaction marked as reviewed" : "Transaction marked as not reviewed"
            ));
        } else {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "success", false,
                "message", "Transaction not found"
            ));
        }
    }

    @PostMapping
    public ResponseEntity<Transaction> addTransaction(@RequestBody Transaction transaction) {
        // You might want to add validation here for the incoming transaction object
        // For example, checking if date, amount, description, operation are not null
        log.info("REST Endpoint Call for Create Transaction");
        log.info("Details: " + transaction.getDate() + " | " + transaction.getAmount() + " | " + transaction.getDescription());
        transaction.setOperation(TransactionOperation.PLUS);
        transaction.setCategorizationStatus(TransactionCategorizationStatus.NOT_CATEGORIZED);

        Transaction savedTransaction = transactionService.saveTransaction(transaction);
        return new ResponseEntity<>(savedTransaction, HttpStatus.CREATED);
    }
    
    /**
     * Scans a receipt image and extracts transaction information using AI.
     * 
     * @param image The receipt image file (JPEG, PNG, WEBP supported)
     * @return ScannedTransactionDTO with extracted transaction data, or error response
     */
    @PostMapping(value = "/scan-receipt", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> scanReceipt(@RequestParam("image") MultipartFile image) {
        log.info("Received receipt scan request, file: {}, size: {} bytes, type: {}", 
                image.getOriginalFilename(), image.getSize(), image.getContentType());
        
        // Validate file is present
        if (image.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "No image file provided"
            ));
        }
        
        // Validate file size
        if (image.getSize() > MAX_IMAGE_SIZE) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Map.of(
                "error", "Image file too large. Maximum size is 10 MB."
            ));
        }
        
        // Validate content type
        String contentType = image.getContentType();
        if (contentType == null || !isValidImageType(contentType)) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "Invalid image type. Supported types: JPEG, PNG, WEBP, GIF"
            ));
        }
        
        try {
            byte[] imageBytes = image.getBytes();
            ScannedTransactionDTO result = receiptScannerService.scanReceipt(imageBytes, contentType);
            
            log.info("Successfully scanned receipt: {}", result);
            return ResponseEntity.ok(result);
            
        } catch (ReceiptScanException e) {
            log.error("Receipt scan failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                "error", "Failed to extract transaction from receipt. Please try again or enter manually.",
                "details", e.getMessage()
            ));
        } catch (Exception e) {
            log.error("Unexpected error during receipt scan: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "error", "An unexpected error occurred. Please try again."
            ));
        }
    }
    
    /**
     * Checks if the content type is a supported image type.
     */
    private boolean isValidImageType(String contentType) {
        return contentType.equals("image/jpeg") ||
               contentType.equals("image/png") ||
               contentType.equals("image/webp") ||
               contentType.equals("image/gif");
    }
}
