package com.everrich.spendmanager.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.everrich.spendmanager.service.RedisAdapter;
import com.everrich.spendmanager.repository.TransactionRepository;
import com.everrich.spendmanager.entities.Transaction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

@Controller
@RequestMapping("/rag-config")
public class RagConfigController {

    private static final Logger log = LoggerFactory.getLogger(RagConfigController.class);

    @Value("${com.everrich.properties.appname}")
    private String appName;

    private final RedisAdapter redisAdapter;
    private final TransactionRepository transactionRepository;

    public RagConfigController(RedisAdapter redisAdapter, TransactionRepository transactionRepository) {
        this.redisAdapter = redisAdapter;
        this.transactionRepository = transactionRepository;
    }

    @GetMapping
    public String showRagConfig(Model model) {
        model.addAttribute("appName", appName);
        model.addAttribute("indexName", redisAdapter.getIndexName());
        model.addAttribute("vectorDimension", redisAdapter.getVectorDimension());
        return "rag-config";
    }

    @PostMapping("/reset")
    public String resetRagConfiguration(RedirectAttributes redirectAttributes) {
        try {
            log.info("Resetting RAG configuration - deleting and recreating index");
            
            // Delete the index
            redisAdapter.deleteIndex();
            log.info("Index deleted successfully");
            
            // Recreate the index
            redisAdapter.createTransactionIndex();
            log.info("Index recreated successfully");
            
            redirectAttributes.addFlashAttribute("successMessage", 
                "RAG configuration reset successfully. Index has been deleted and recreated.");
            
        } catch (Exception e) {
            log.error("Error resetting RAG configuration", e);
            redirectAttributes.addFlashAttribute("errorMessage", 
                "Failed to reset RAG configuration: " + e.getMessage());
        }
        
        return "redirect:/rag-config";
    }

    @PostMapping("/recreate")
    public String recreateRagConfiguration(RedirectAttributes redirectAttributes) {
        try {
            log.info("Recreating RAG configuration - loading all transactions and creating documents");
            
            // Fetch all transactions from the database
            List<Transaction> transactions = transactionRepository.findAll();
            log.info("Found {} transactions to process", transactions.size());
            
            int processedCount = 0;
            int skippedCount = 0;
            
            for (Transaction transaction : transactions) {
                // Only process transactions that have a category assigned
                if (transaction.getCategoryEntity() != null && transaction.getAccount() != null) {
                    String categoryName = transaction.getCategoryEntity().getName();
                    String description = transaction.getDescription();
                    String operationName = transaction.getOperation().name(); // "PLUS" or "MINUS"
                    String accountName = transaction.getAccount().getName();
                    
                    // Create document in Redis
                    redisAdapter.createDocument(categoryName, description, operationName, accountName);
                    processedCount++;
                } else {
                    skippedCount++;
                }
            }
            
            log.info("RAG configuration recreated successfully. Processed: {}, Skipped: {}", processedCount, skippedCount);
            redirectAttributes.addFlashAttribute("successMessage", 
                String.format("RAG configuration recreated successfully. Processed %d transactions, skipped %d uncategorized transactions.", 
                    processedCount, skippedCount));
            
        } catch (Exception e) {
            log.error("Error recreating RAG configuration", e);
            redirectAttributes.addFlashAttribute("errorMessage", 
                "Failed to recreate RAG configuration: " + e.getMessage());
        }
        
        return "redirect:/rag-config";
    }
}
