package com.everrich.spendmanager.controller;

import java.io.IOException;
import java.time.LocalDate;
import java.time.Year;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.DeleteMapping;
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
import com.everrich.spendmanager.service.AccountService;
import com.everrich.spendmanager.entities.Account;
import com.everrich.spendmanager.entities.StatementFileType;

@Controller
public class PdfController {

    private final TransactionService transactionService;
    private final StatementService statementService;
    private final CategoryService categoryService;
    private final AccountService accountService;

    private static final Logger log = LoggerFactory.getLogger(PdfController.class);
    
    // Date range constants for filtering
    private static final LocalDate MIN_DATE = LocalDate.of(1970, 1, 1);
    private static final LocalDate MAX_DATE = LocalDate.of(9999, 12, 31);

    public PdfController(PdfProcessor pdfProcessor, TransactionService transactionService,
            StatementService statementService, CategoryService categoryService,
            AccountService accountService) {
        this.transactionService = transactionService;
        this.statementService = statementService;
        this.categoryService = categoryService;
        this.accountService = accountService;
    }

    // -----------------------------------------------------------------------------------
    // 1. View Endpoints (Returning Thymeleaf Templates)
    // -----------------------------------------------------------------------------------

    /**
     * Shows the main application dashboard.
     * Maps to: GET /
     */
    /*@GetMapping("/")
    public String showDashboard(Model model) {
        model.addAttribute("appName", "EverRich");

        long categoryCount = categoryService.countAll();
        model.addAttribute("categoryCount", categoryCount);

        return "dashboard";
    }*/

    @GetMapping("/statements")
    public String viewStatements(
            @RequestParam(required = false) Long accountId,
            @RequestParam(required = false, defaultValue = "current_month") String timeframe,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            Model model) {
        
        model.addAttribute("appName", "EverRich");

        // Fetch list of accounts for the selection field
        List<Account> accounts = accountService.findAll();
        model.addAttribute("accounts", accounts);
        
        // Determine selected account
        Account selectedAccount = null;
        if (accountId != null) {
            selectedAccount = accountService.findById(accountId).orElse(null);
        }
        
        // Calculate date range based on timeframe
        LocalDate filterStartDate;
        LocalDate filterEndDate;
        
        switch (timeframe) {
            case "current_month":
                YearMonth currentMonth = YearMonth.now();
                filterStartDate = currentMonth.atDay(1);
                filterEndDate = currentMonth.atEndOfMonth();
                break;
            case "last_month":
                YearMonth lastMonth = YearMonth.now().minusMonths(1);
                filterStartDate = lastMonth.atDay(1);
                filterEndDate = lastMonth.atEndOfMonth();
                break;
            case "current_year":
                Year currentYear = Year.now();
                filterStartDate = currentYear.atDay(1);
                filterEndDate = currentYear.atMonth(12).atEndOfMonth();
                break;
            case "previous_year":
                Year previousYear = Year.now().minusYears(1);
                filterStartDate = previousYear.atDay(1);
                filterEndDate = previousYear.atMonth(12).atEndOfMonth();
                break;
            case "date_range":
                filterStartDate = (startDate != null) ? startDate : MIN_DATE;
                filterEndDate = (endDate != null) ? endDate : MAX_DATE;
                break;
            case "entire_timeframe":
            default:
                filterStartDate = MIN_DATE;
                filterEndDate = MAX_DATE;
                break;
        }
        
        // Fetch filtered statements
        List<Statement> statements = statementService.getFilteredStatements(selectedAccount, filterStartDate, filterEndDate);
        model.addAttribute("statements", statements);
        
        // Pass filter state back to view for retention
        model.addAttribute("selectedAccountId", accountId);
        model.addAttribute("selectedTimeframe", timeframe);
        model.addAttribute("selectedStartDate", startDate);
        model.addAttribute("selectedEndDate", endDate);

        return "statement-management";
    }

    /**
     * Shows the transaction details for a specific statement ID.
     * Maps to: GET /statements/{statementId}
     */
    @GetMapping("/statements/{statementId}")
    public String viewStatementDetails(
            @PathVariable Long statementId,
            @RequestParam(required = false) Long accountId,
            @RequestParam(required = false) String timeframe,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            Model model) {
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
        
        // Pass filter parameters for back navigation
        model.addAttribute("filterAccountId", accountId);
        model.addAttribute("filterTimeframe", timeframe);
        model.addAttribute("filterStartDate", startDate);
        model.addAttribute("filterEndDate", endDate);
        
        return "transactions-view";
    }

    @PostMapping("/api/statement/upload")
    @ResponseBody
    public ResponseEntity<Map<String, String>> handleFileUpload(@RequestParam("file") MultipartFile file,
            @RequestParam("accountId") Long accountId) {
        if (file.isEmpty()) {
            Map<String, String> error = Map.of("status", "error", "message", "Please select a file to upload.");
            return new ResponseEntity<>(error, HttpStatus.BAD_REQUEST);
        }

        if (accountId == null) {
            Map<String, String> error = Map.of("status", "error", "message", "Please select an account.");
            return new ResponseEntity<>(error, HttpStatus.BAD_REQUEST);
        }

        // Determine file type based on content type or file extension
        String originalFilename = file.getOriginalFilename();
        String contentType = file.getContentType();
        StatementFileType fileType = determineFileType(originalFilename, contentType);
        
        if (fileType == null) {
            Map<String, String> error = Map.of("status", "error", "message", 
                    "Unsupported file type. Please upload a PDF or CSV file.");
            return new ResponseEntity<>(error, HttpStatus.BAD_REQUEST);
        }

        log.info("START: Processing {} statement: {}", fileType, originalFilename);

        try {
            // Retrieve the account
            Account account = accountService.findById(accountId)
                    .orElseThrow(() -> new IllegalArgumentException("Account not found with ID: " + accountId));

            Statement newStatement = statementService.createInitialStatement(originalFilename, account, file.getBytes(), fileType);
            String statementIdString = newStatement.getId().toString();

            Map<String, String> success = Map.of(
                    "status", "success",
                    "message", "File '" + originalFilename + "' uploaded. Processing started.",
                    "statementId", statementIdString,
                    "fileType", fileType.name());

            return new ResponseEntity<>(success, HttpStatus.ACCEPTED);

        } catch (IOException e) {
            e.printStackTrace();
            Map<String, String> error = Map.of("status", "error", "message", "File I/O failed: " + e.getMessage());
            return new ResponseEntity<>(error, HttpStatus.INTERNAL_SERVER_ERROR);
        } catch (Exception e) {
            e.printStackTrace();
            Map<String, String> error = Map.of("status", "error", "message",
                    "Processing initiation failed: " + e.getMessage());
            return new ResponseEntity<>(error, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
    
    /**
     * Determines the file type (PDF or CSV) based on filename extension and content type.
     * 
     * @param filename The original filename
     * @param contentType The MIME content type
     * @return StatementFileType.PDF, StatementFileType.CSV, or null if unsupported
     */
    private StatementFileType determineFileType(String filename, String contentType) {
        // Check by file extension first (more reliable)
        if (filename != null) {
            String lowerFilename = filename.toLowerCase();
            if (lowerFilename.endsWith(".pdf")) {
                return StatementFileType.PDF;
            }
            if (lowerFilename.endsWith(".csv")) {
                return StatementFileType.CSV;
            }
        }
        
        // Fallback to content type
        if (contentType != null) {
            if (contentType.equals("application/pdf")) {
                return StatementFileType.PDF;
            }
            if (contentType.equals("text/csv") || 
                contentType.equals("application/csv") ||
                contentType.equals("text/comma-separated-values")) {
                return StatementFileType.CSV;
            }
        }
        
        return null; // Unsupported file type
    }

    /**
     * Rollback transactions - deletes all transactions associated with a statement.
     * DELETE /api/statement/{statementId}/rollback
     */
    @DeleteMapping("/api/statement/{statementId}/rollback")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> rollbackTransactions(@PathVariable Long statementId) {
        Statement statement = statementService.getStatementById(statementId);
        if (statement == null) {
            return new ResponseEntity<>(Map.of("status", "error", "message", "Statement not found"), HttpStatus.NOT_FOUND);
        }
        
        long count = transactionService.countTransactionsByStatementId(statementId);
        log.info("Rolling back {} transactions for statement ID {}", count, statementId);
        
        // Trigger async deletion
        transactionService.deleteTransactionsByStatementIdAsync(statementId);
        
        // Set statement status to ROLLED_BACK
        statement.setStatus(com.everrich.spendmanager.entities.StatementStatus.ROLLED_BACK);
        statementService.saveStatement(statement);
        log.info("Statement ID {} status set to ROLLED_BACK", statementId);
        
        return new ResponseEntity<>(Map.of(
            "status", "success",
            "message", "Rollback initiated for " + count + " transaction(s). Statement marked as rolled back.",
            "transactionCount", count
        ), HttpStatus.ACCEPTED);
    }

    /**
     * Update statement metadata - updates editable fields of a statement.
     * PUT /api/statement/{statementId}
     */
    @PostMapping("/api/statement/{statementId}/update")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> updateStatementMetadata(
            @PathVariable Long statementId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodStartDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodEndDate,
            @RequestParam(required = false) String openingBalance,
            @RequestParam(required = false) String closingBalance) {
        
        Statement statement = statementService.getStatementById(statementId);
        if (statement == null) {
            return new ResponseEntity<>(Map.of("status", "error", "message", "Statement not found"), HttpStatus.NOT_FOUND);
        }
        
        java.math.BigDecimal opening = null;
        java.math.BigDecimal closing = null;
        
        try {
            if (openingBalance != null && !openingBalance.trim().isEmpty()) {
                opening = new java.math.BigDecimal(openingBalance.trim());
            }
            if (closingBalance != null && !closingBalance.trim().isEmpty()) {
                closing = new java.math.BigDecimal(closingBalance.trim());
            }
        } catch (NumberFormatException e) {
            return new ResponseEntity<>(Map.of("status", "error", "message", "Invalid balance format"), HttpStatus.BAD_REQUEST);
        }
        
        Statement updated = statementService.updateStatementMetadata(statementId, periodStartDate, periodEndDate, opening, closing);
        
        if (updated == null) {
            return new ResponseEntity<>(Map.of("status", "error", "message", "Failed to update statement"), HttpStatus.INTERNAL_SERVER_ERROR);
        }
        
        return new ResponseEntity<>(Map.of(
            "status", "success",
            "message", "Statement updated successfully"
        ), HttpStatus.OK);
    }

    /**
     * Delete a statement - only allowed for FAILED or ROLLED_BACK status.
     * DELETE /api/statement/{statementId}
     */
    @DeleteMapping("/api/statement/{statementId}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> deleteStatement(@PathVariable Long statementId) {
        try {
            boolean deleted = statementService.deleteStatement(statementId);
            if (deleted) {
                return new ResponseEntity<>(Map.of(
                    "status", "success",
                    "message", "Statement deleted successfully"
                ), HttpStatus.OK);
            } else {
                return new ResponseEntity<>(Map.of(
                    "status", "error",
                    "message", "Statement not found"
                ), HttpStatus.NOT_FOUND);
            }
        } catch (IllegalStateException e) {
            return new ResponseEntity<>(Map.of(
                "status", "error",
                "message", e.getMessage()
            ), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Get statement details for editing.
     * GET /api/statement/{statementId}
     */
    @GetMapping("/api/statement/{statementId}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getStatementDetails(@PathVariable Long statementId) {
        Statement statement = statementService.getStatementById(statementId);
        if (statement == null) {
            return new ResponseEntity<>(Map.of("status", "error", "message", "Statement not found"), HttpStatus.NOT_FOUND);
        }
        
        long transactionCount = transactionService.countTransactionsByStatementId(statementId);
        
        java.util.HashMap<String, Object> response = new java.util.HashMap<>();
        response.put("status", "success");
        response.put("id", statement.getId());
        response.put("originalFileName", statement.getOriginalFileName() != null ? statement.getOriginalFileName() : "");
        response.put("accountName", statement.getAccount() != null ? statement.getAccount().getName() : "N/A");
        response.put("uploadDateTime", statement.getUploadDateTime() != null ? statement.getUploadDateTime().toString() : "");
        response.put("periodStartDate", statement.getPeriodStartDate() != null ? statement.getPeriodStartDate().toString() : "");
        response.put("periodEndDate", statement.getPeriodEndDate() != null ? statement.getPeriodEndDate().toString() : "");
        response.put("openingBalance", statement.getOpeningBalance() != null ? statement.getOpeningBalance().toString() : "");
        response.put("closingBalance", statement.getClosingBalance() != null ? statement.getClosingBalance().toString() : "");
        response.put("statementStatus", statement.getStatus().name());
        response.put("transactionCount", transactionCount);
        
        return new ResponseEntity<>(response, HttpStatus.OK);
    }
}
