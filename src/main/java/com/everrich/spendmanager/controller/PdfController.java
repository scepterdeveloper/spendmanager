package com.everrich.spendmanager.controller;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller; 
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody; 
import org.springframework.web.multipart.MultipartFile;

import com.everrich.spendmanager.entities.Statement;
import com.everrich.spendmanager.entities.Transaction;
import com.everrich.spendmanager.service.PdfProcessor;
import com.everrich.spendmanager.service.StatementService;
import com.everrich.spendmanager.service.TransactionService;
import com.everrich.spendmanager.service.CategoryService; 

@Controller 
public class PdfController {

    private final PdfProcessor pdfProcessor;
    private final TransactionService transactionService;
    private final StatementService statementService;
    private final CategoryService categoryService; 

    public PdfController(PdfProcessor pdfProcessor, TransactionService transactionService, 
                         StatementService statementService, CategoryService categoryService) {
        this.pdfProcessor = pdfProcessor;
        this.transactionService = transactionService;
        this.statementService = statementService;
        this.categoryService = categoryService; 
    }

// -----------------------------------------------------------------------------------
// 1. View Endpoints (Returning Thymeleaf Templates)
// -----------------------------------------------------------------------------------

    /**
     * Shows the main application dashboard.
     * Maps to: GET /
     */
    @GetMapping("/")
    public String showDashboard(Model model) {
        model.addAttribute("appName", "EverRich");
        
        long categoryCount = categoryService.countAll(); 
        model.addAttribute("categoryCount", categoryCount);
        
        return "dashboard";
    }

    /**
     * ❌ REMOVED: The dedicated file upload form endpoint is now combined with /statements.
     * Maps to: GET /upload-statement
     */
    /*
    @GetMapping("/upload-statement")
    public String showUploadForm(Model model) {
        model.addAttribute("appName", "EverRich");
        return "upload-form";
    }
    */

    /**
     * 🟢 UPDATED: Shows the unified page for statement upload AND listing.
     * Maps to: GET /statements
     */
    @GetMapping("/statements")
    public String viewStatements(Model model) {
        model.addAttribute("appName", "EverRich");
        
        // Fetch list of statements for the viewing section
        List<Statement> statements = statementService.getAllStatements();
        model.addAttribute("statements", statements);
        
        // 🟢 Return the new unified view name
        return "statement-management"; 
    }

    /**
     * Shows the transaction details for a specific statement ID.
     * Maps to: GET /statements/{statementId}
     */
    @GetMapping("/statements/{statementId}")
    public String viewStatementDetails(@PathVariable Long statementId, Model model) {
        model.addAttribute("appName", "EverRich");
        
        List<Transaction> transactions = transactionService.getTransactionsByStatementId(statementId);
        Statement statement = statementService.getStatementById(statementId);
        
        if (statement == null) {
            model.addAttribute("message", "Statement ID " + statementId + " not found.");
            // 🟢 Change redirect destination to the unified statement management page
            return "redirect:/statements"; 
        }
        
        model.addAttribute("statement", statement);
        model.addAttribute("transactions", transactions);
        return "transactions-view";
    }

// -----------------------------------------------------------------------------------
// 2. API Endpoint (Asynchronous File Upload) - Logic remains unchanged
// -----------------------------------------------------------------------------------

    /**
     * Handles the file upload asynchronously, returning a JSON response immediately.
     * Maps to: POST /api/statement/upload
     */
    @PostMapping("/api/statement/upload")
    @ResponseBody 
    public ResponseEntity<Map<String, String>> handleFileUpload(@RequestParam("file") MultipartFile file) {
        
        if (file.isEmpty()) {
            Map<String, String> error = Map.of("status", "error", "message", "Please select a file to upload.");
            return new ResponseEntity<>(error, HttpStatus.BAD_REQUEST);
        }

        try {
            Statement newStatement = statementService.createInitialStatement(file.getOriginalFilename());
            
            String statementIdString = newStatement.getId().toString();
            
            statementService.startProcessingAsync(newStatement.getId(), file.getBytes()); 

            Map<String, String> success = Map.of(
                "status", "success", 
                "message", "File '" + file.getOriginalFilename() + "' uploaded. Processing started.",
                "statementId", statementIdString 
            );

            return new ResponseEntity<>(success, HttpStatus.ACCEPTED); 

        } catch (IOException e) {
            e.printStackTrace();
            Map<String, String> error = Map.of("status", "error", "message", "File I/O failed: " + e.getMessage());
            return new ResponseEntity<>(error, HttpStatus.INTERNAL_SERVER_ERROR);
        } catch (Exception e) {
            e.printStackTrace();
            Map<String, String> error = Map.of("status", "error", "message", "Processing initiation failed: " + e.getMessage());
            return new ResponseEntity<>(error, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
}