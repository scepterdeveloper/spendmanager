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

@RestController
@RequestMapping("/api/transactions")
public class TransactionRESTController {

    @Autowired
    private TransactionService transactionService;
    private static final Logger log = LoggerFactory.getLogger(TransactionRESTController.class);

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
