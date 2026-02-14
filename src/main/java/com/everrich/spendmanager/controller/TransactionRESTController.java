package com.everrich.spendmanager.controller;

import com.everrich.spendmanager.entities.Transaction;
import com.everrich.spendmanager.entities.TransactionCategorizationStatus;
import com.everrich.spendmanager.entities.TransactionOperation;
import com.everrich.spendmanager.service.RagService;
import com.everrich.spendmanager.service.TransactionService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/transactions")
public class TransactionRESTController {

    @Autowired
    private TransactionService transactionService;
    private static final Logger log = LoggerFactory.getLogger(TransactionRESTController.class);

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
}
