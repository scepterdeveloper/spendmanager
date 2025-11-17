package com.everrich.spendmanager.service;

import com.everrich.spendmanager.controller.PdfController;
import com.everrich.spendmanager.entities.Statement;
import com.everrich.spendmanager.entities.StatementStatus;
import com.everrich.spendmanager.entities.Transaction;
import com.everrich.spendmanager.repository.StatementRepository;

import org.springframework.scheduling.annotation.Async;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional; // Import for data safety

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional; // New Import for findById

// NOTE: Ensure your main Spring Boot class has @EnableAsync

@Service
public class StatementService {

    private final StatementRepository statementRepository;
    private final PdfProcessor pdfProcessor;
    private final TransactionService transactionService;

    private static final Logger log = LoggerFactory.getLogger(StatementService.class);

    public StatementService(
        StatementRepository statementRepository, 
        PdfProcessor pdfProcessor, 
        @Lazy TransactionService transactionService) { // Add @Lazy
        
        this.statementRepository = statementRepository;
        this.pdfProcessor = pdfProcessor;
        this.transactionService = transactionService;
    }

    /**
     * Creates and persists an initial Statement record in the database.
     * @param fileName The original file name.
     * @param account The account associated with the statement.
     * @return The newly created Statement entity.
     */
    public Statement createInitialStatement(String fileName, com.everrich.spendmanager.entities.Account account) {
        Statement statement = new Statement();
        statement.setOriginalFileName(fileName);
        statement.setUploadDateTime(LocalDateTime.now());
        statement.setStatus(StatementStatus.PROCESSING); // Start as Processing
        statement.setAccount(account); // Set the associated account
        
        // REPLACEMENT: Use JpaRepository's save() instead of list.add()
        return statementRepository.save(statement);
    }

    /**
     * Retrieves all Statement records from the database.
     */
    public List<Statement> getAllStatements() {
        // REPLACEMENT: Use JpaRepository's findAll()
        return statementRepository.findAll();
    }

    /**
     * Retrieves a single Statement by its ID.
     */
    public Statement getStatementById(Long id) {
        // REPLACEMENT: Use JpaRepository's findById() and handle the Optional
        // Returns null if not found, matching the old signature.
        return statementRepository.findById(id).orElse(null);
    }

    /**
     * Runs the long-running PDF processing and LLM categorization in a background thread.
     */
    @Async 
    @Transactional // Ensures status updates and transaction saving are atomic
    public void startProcessingAsync(Long statementId, byte[] fileBytes) {
        // Use findById to retrieve the entity managed by the persistence context
        log.info("Start async. processing for Statment with Id: " + statementId);
        Optional<Statement> statementOptional = statementRepository.findById(statementId);
        
        if (statementOptional.isEmpty()) {
            System.err.println("Statement not found for ID: " + statementId);
            return;
        }

        Statement statement = statementOptional.get();

        try {
            // 1. Extract Text
            String extractedText = pdfProcessor.extractTextFromPdf(fileBytes);
            log.info("Extracted Text: " + extractedText);
            log.info("START: Resolve Categories");

            // 2. Process and Categorize
            List<Transaction> transactions = transactionService.processTransactions(extractedText);
            log.info("No. of Transactions:  " + transactions.size());

            // 3. Persist Transactions (TransactionService will use its new JPA Repository)
            transactionService.saveCategorizedTransactions(statementId, transactions);

            // 4. Update Status
            statement.setStatus(StatementStatus.COMPLETED);
            
            // JpaRepository.save is not strictly needed here if @Transactional is used 
            // and the entity is managed, but explicitly calling save is safer and clearer.
            statementRepository.save(statement); 

        } catch (Exception e) {
            e.printStackTrace();
            
            // 5. Update Status on Failure
            statement.setStatus(StatementStatus.FAILED);
            statementRepository.save(statement); // Persist the failed status
            System.err.println("Processing failed for statement ID: " + statementId + ". Reason: " + e.getMessage());
        }
    }
}
