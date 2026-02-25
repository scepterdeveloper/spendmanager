package com.everrich.spendmanager.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.everrich.spendmanager.multitenancy.TenantContext;
import com.everrich.spendmanager.service.RedisAdapter;
import com.everrich.spendmanager.service.RedisDocument;
import com.everrich.spendmanager.repository.TransactionRepository;
import com.everrich.spendmanager.entities.Transaction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
        model.addAttribute("documents", null);
        model.addAttribute("showDocuments", false);
        return "rag-config";
    }

    @GetMapping("/view")
    public String viewRagConfiguration(Model model) {
        model.addAttribute("appName", appName);
        
        try {
            String tenantId = TenantContext.getTenantId();
            log.info("Viewing RAG configuration for tenant: {}", tenantId);
            
            List<RedisDocument> documents = redisAdapter.getDocumentsByTenant(tenantId);
            
            model.addAttribute("documents", documents);
            model.addAttribute("showDocuments", true);
            model.addAttribute("documentCount", documents.size());
            
            log.info("Retrieved {} documents for tenant {}", documents.size(), tenantId);
            
        } catch (Exception e) {
            log.error("Error retrieving RAG configuration documents", e);
            model.addAttribute("errorMessage", "Failed to retrieve configuration: " + e.getMessage());
            model.addAttribute("documents", null);
            model.addAttribute("showDocuments", false);
        }
        
        return "rag-config";
    }

    @PostMapping("/reset")
    public String resetRagConfiguration(RedirectAttributes redirectAttributes) {
        try {
            String tenantId = TenantContext.getTenantId();
            log.info("Resetting RAG configuration for tenant: {} - deleting tenant-specific documents", tenantId);
            
            // Delete only the documents belonging to the current tenant (tenant-aware)
            int deletedCount = redisAdapter.deleteDocumentsByTenant(tenantId);
            log.info("Deleted {} documents for tenant {}", deletedCount, tenantId);
            
            redirectAttributes.addFlashAttribute("successMessage", 
                String.format("RAG configuration reset successfully. Deleted %d training documents for your account.", deletedCount));
            
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
            String tenantId = TenantContext.getTenantId();
            log.info("Recreating RAG configuration for tenant: {} - loading all transactions and creating documents", tenantId);
            
            // First, delete existing documents for this tenant to avoid duplicates
            int deletedCount = redisAdapter.deleteDocumentsByTenant(tenantId);
            log.info("Cleared {} existing documents for tenant {}", deletedCount, tenantId);
            
            // Fetch all transactions from the database (tenant-aware via Hibernate schema)
            List<Transaction> transactions = transactionRepository.findAll();
            log.info("Found {} transactions to process for tenant {}", transactions.size(), tenantId);
            
            int processedCount = 0;
            int skippedCount = 0;
            
            for (Transaction transaction : transactions) {
                // Only process transactions that have a category assigned
                if (transaction.getCategoryEntity() != null && transaction.getAccount() != null) {
                    String categoryName = transaction.getCategoryEntity().getName();
                    String description = transaction.getDescription();
                    String operationName = transaction.getOperation().name(); // "PLUS" or "MINUS"
                    String accountName = transaction.getAccount().getName();
                    
                    // Create document in Redis (tenant-aware via TenantContext)
                    redisAdapter.createDocument(categoryName, description, operationName, accountName);
                    processedCount++;
                } else {
                    skippedCount++;
                }
            }
            
            log.info("RAG configuration recreated for tenant {}. Processed: {}, Skipped: {}", tenantId, processedCount, skippedCount);
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

    @PostMapping("/delete-selected")
    public String deleteSelectedDocuments(
            @RequestParam(value = "documentIds", required = false) String documentIds,
            RedirectAttributes redirectAttributes) {
        try {
            if (documentIds == null || documentIds.trim().isEmpty()) {
                redirectAttributes.addFlashAttribute("errorMessage", "No documents selected for deletion.");
                return "redirect:/rag-config/view";
            }

            String tenantId = TenantContext.getTenantId();
            log.info("Deleting selected documents for tenant: {}", tenantId);

            // Parse comma-separated document IDs
            List<String> documentKeyList = Arrays.stream(documentIds.split(","))
                    .map(String::trim)
                    .filter(id -> !id.isEmpty())
                    // Validate that the document belongs to this tenant (security check)
                    .filter(id -> id.startsWith("doc:" + tenantId + ":"))
                    .collect(Collectors.toList());

            if (documentKeyList.isEmpty()) {
                redirectAttributes.addFlashAttribute("errorMessage", "No valid documents selected for deletion.");
                return "redirect:/rag-config/view";
            }

            int deletedCount = redisAdapter.deleteDocumentsByKeys(documentKeyList);
            log.info("Deleted {} documents for tenant {}", deletedCount, tenantId);

            redirectAttributes.addFlashAttribute("successMessage",
                    String.format("Successfully deleted %d training document(s).", deletedCount));

        } catch (Exception e) {
            log.error("Error deleting selected documents", e);
            redirectAttributes.addFlashAttribute("errorMessage",
                    "Failed to delete selected documents: " + e.getMessage());
        }

        return "redirect:/rag-config/view";
    }

    // ==================== REST API Endpoints for Async Operations ====================

    @PostMapping("/api/reset")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> resetRagConfigurationAsync() {
        Map<String, Object> response = new HashMap<>();
        try {
            String tenantId = TenantContext.getTenantId();
            log.info("Resetting RAG configuration (async) for tenant: {}", tenantId);
            
            int deletedCount = redisAdapter.deleteDocumentsByTenant(tenantId);
            log.info("Deleted {} documents for tenant {}", deletedCount, tenantId);
            
            response.put("success", true);
            response.put("message", String.format("RAG configuration reset successfully. Deleted %d training documents.", deletedCount));
            response.put("deletedCount", deletedCount);
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("Error resetting RAG configuration", e);
            response.put("success", false);
            response.put("message", "Failed to reset RAG configuration: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    @PostMapping("/api/recreate")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> recreateRagConfigurationAsync() {
        Map<String, Object> response = new HashMap<>();
        try {
            String tenantId = TenantContext.getTenantId();
            log.info("Recreating RAG configuration (async) for tenant: {}", tenantId);
            
            int deletedCount = redisAdapter.deleteDocumentsByTenant(tenantId);
            log.info("Cleared {} existing documents for tenant {}", deletedCount, tenantId);
            
            List<Transaction> transactions = transactionRepository.findAll();
            log.info("Found {} transactions to process for tenant {}", transactions.size(), tenantId);
            
            int processedCount = 0;
            int skippedCount = 0;
            
            for (Transaction transaction : transactions) {
                if (transaction.getCategoryEntity() != null && transaction.getAccount() != null) {
                    String categoryName = transaction.getCategoryEntity().getName();
                    String description = transaction.getDescription();
                    String operationName = transaction.getOperation().name();
                    String accountName = transaction.getAccount().getName();
                    
                    redisAdapter.createDocument(categoryName, description, operationName, accountName);
                    processedCount++;
                } else {
                    skippedCount++;
                }
            }
            
            log.info("RAG configuration recreated for tenant {}. Processed: {}, Skipped: {}", tenantId, processedCount, skippedCount);
            response.put("success", true);
            response.put("message", String.format("RAG configuration recreated successfully. Processed %d transactions, skipped %d uncategorized.", processedCount, skippedCount));
            response.put("processedCount", processedCount);
            response.put("skippedCount", skippedCount);
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("Error recreating RAG configuration", e);
            response.put("success", false);
            response.put("message", "Failed to recreate RAG configuration: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    @PostMapping("/api/delete-selected")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> deleteSelectedDocumentsAsync(
            @RequestParam(value = "documentIds", required = false) String documentIds) {
        Map<String, Object> response = new HashMap<>();
        try {
            if (documentIds == null || documentIds.trim().isEmpty()) {
                response.put("success", false);
                response.put("message", "No documents selected for deletion.");
                return ResponseEntity.badRequest().body(response);
            }

            String tenantId = TenantContext.getTenantId();
            log.info("Deleting selected documents (async) for tenant: {}", tenantId);

            List<String> documentKeyList = Arrays.stream(documentIds.split(","))
                    .map(String::trim)
                    .filter(id -> !id.isEmpty())
                    .filter(id -> id.startsWith("doc:" + tenantId + ":"))
                    .collect(Collectors.toList());

            if (documentKeyList.isEmpty()) {
                response.put("success", false);
                response.put("message", "No valid documents selected for deletion.");
                return ResponseEntity.badRequest().body(response);
            }

            int deletedCount = redisAdapter.deleteDocumentsByKeys(documentKeyList);
            log.info("Deleted {} documents for tenant {}", deletedCount, tenantId);

            response.put("success", true);
            response.put("message", String.format("Successfully deleted %d training document(s).", deletedCount));
            response.put("deletedCount", deletedCount);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error deleting selected documents", e);
            response.put("success", false);
            response.put("message", "Failed to delete selected documents: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }
}
