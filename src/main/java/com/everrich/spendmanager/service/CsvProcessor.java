package com.everrich.spendmanager.service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.everrich.spendmanager.dto.ParsedStatementDTO;
import com.everrich.spendmanager.entities.Transaction;
import com.everrich.spendmanager.entities.TransactionOperation;

/**
 * Processes CSV files to extract transactions.
 * Supports auto-detection of column mappings based on common header names.
 * 
 * CSV files are parsed directly without LLM - the structured format allows
 * deterministic parsing which is faster, cheaper, and more reliable than
 * AI-based parsing.
 */
@Component
public class CsvProcessor {

    private static final Logger log = LoggerFactory.getLogger(CsvProcessor.class);

    // Common header name variations for auto-detection
    private static final List<String> DATE_HEADERS = Arrays.asList(
            "date", "datum", "booking date", "buchungsdatum", "transaction date", 
            "valutadatum", "value date", "wertstellung", "buchungstag", "umsatztag"
    );
    
    private static final List<String> DESCRIPTION_HEADERS = Arrays.asList(
            "description", "beschreibung", "verwendungszweck", "purpose", "details",
            "narrative", "text", "memo", "reference", "referenz", "empfänger/zahlungspflichtiger",
            "recipient", "payee", "zahlungsempfänger", "auftraggeber/empfänger",
            "buchungstext", "vorgang"
    );
    
    private static final List<String> AMOUNT_HEADERS = Arrays.asList(
            "amount", "betrag", "value", "wert", "sum", "summe", "transaction amount",
            "umsatz", "umsatz in eur", "umsatz in euro"
    );
    
    private static final List<String> CREDIT_HEADERS = Arrays.asList(
            "credit", "haben", "eingang", "gutschrift", "deposit", "credit amount"
    );
    
    private static final List<String> DEBIT_HEADERS = Arrays.asList(
            "debit", "soll", "ausgang", "lastschrift", "withdrawal", "debit amount"
    );
    
    // Multiple date formatters to handle various CSV date formats
    private static final List<DateTimeFormatter> DATE_FORMATTERS = Arrays.asList(
            DateTimeFormatter.ofPattern("dd.MM.yyyy"),           // German format: 02.12.2025
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),           // EU format: 02/12/2025
            DateTimeFormatter.ofPattern("MM/dd/yyyy"),           // US format: 12/02/2025
            DateTimeFormatter.ofPattern("yyyy-MM-dd"),           // ISO format: 2025-12-02
            DateTimeFormatter.ofPattern("dd-MM-yyyy"),           // Alternative: 02-12-2025
            DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH),  // 02 Dec 2025
            DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.GERMAN),   // 02 Dez 2025
            DateTimeFormatter.ofPattern("d.M.yyyy"),             // Short German: 2.12.2025
            DateTimeFormatter.ofPattern("d/M/yyyy")              // Short: 2/12/2025
    );

    /**
     * Parses a CSV file and returns a ParsedStatementDTO containing transactions.
     * The CSV must have headers - column mapping is done by auto-detecting
     * common header names.
     * 
     * Supports multiple CSV formats:
     * - Comma-delimited (standard CSV)
     * - Semicolon-delimited (European/German bank exports)
     * - Tab-delimited
     * 
     * @param fileBytes The CSV file content as bytes
     * @return ParsedStatementDTO with extracted transactions
     * @throws IOException If the file cannot be read
     */
    public ParsedStatementDTO parseTransactionsFromCsv(byte[] fileBytes) throws IOException {
        log.debug("Starting CSV parsing, file size: {} bytes", fileBytes.length);
        
        List<Transaction> transactions = new ArrayList<>();
        ParsedStatementDTO result = new ParsedStatementDTO();
        
        // First, detect the delimiter and find header row
        String content = new String(fileBytes, StandardCharsets.UTF_8);
        char delimiter = detectDelimiter(content);
        int headerRowIndex = findHeaderRow(content, delimiter);
        
        log.debug("Detected delimiter: '{}', header row index: {}", delimiter, headerRowIndex);
        
        // Skip metadata rows if header is not on first line
        String[] lines = content.split("\\R");
        StringBuilder dataContent = new StringBuilder();
        for (int i = headerRowIndex; i < lines.length; i++) {
            dataContent.append(lines[i]).append("\n");
        }
        
        try (Reader reader = new InputStreamReader(new ByteArrayInputStream(dataContent.toString().getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
             CSVParser csvParser = CSVFormat.DEFAULT
                     .builder()
                     .setDelimiter(delimiter)
                     .setQuote('"')
                     .setHeader()
                     .setIgnoreHeaderCase(true)
                     .setTrim(true)
                     .setSkipHeaderRecord(true)
                     .setIgnoreEmptyLines(true)
                     .setAllowMissingColumnNames(true)  // Allow empty column names in header row
                     .build()
                     .parse(reader)) {
            
            // Get headers and detect column mappings
            Map<String, Integer> headerMap = csvParser.getHeaderMap();
            log.debug("Detected headers: {}", headerMap.keySet());
            ColumnMapping mapping = detectColumnMapping(headerMap);
            
            if (mapping.dateColumn == null) {
                throw new IOException("CSV parsing failed: Could not find a date column. " +
                        "Expected column headers like: " + String.join(", ", DATE_HEADERS));
            }
            
            if (mapping.descriptionColumn == null) {
                throw new IOException("CSV parsing failed: Could not find a description column. " +
                        "Expected column headers like: " + String.join(", ", DESCRIPTION_HEADERS));
            }
            
            if (mapping.amountColumn == null && mapping.creditColumn == null && mapping.debitColumn == null) {
                throw new IOException("CSV parsing failed: Could not find amount column(s). " +
                        "Expected column headers like: " + String.join(", ", AMOUNT_HEADERS) +
                        " or separate credit/debit columns.");
            }
            
            log.debug("Detected column mapping - Date: {}, Description: {}, Amount: {}, Credit: {}, Debit: {}",
                    mapping.dateColumn, mapping.descriptionColumn, mapping.amountColumn, 
                    mapping.creditColumn, mapping.debitColumn);
            
            // Parse each row
            int rowNumber = 1; // Header is row 0
            for (CSVRecord record : csvParser) {
                rowNumber++;
                try {
                    Transaction transaction = parseTransaction(record, mapping, rowNumber);
                    if (transaction != null) {
                        transactions.add(transaction);
                    }
                } catch (Exception e) {
                    log.warn("Skipping row {}: {}", rowNumber, e.getMessage());
                }
            }
            
            log.info("CSV parsing completed: {} transactions extracted", transactions.size());
        }
        
        result.setTransactions(transactions);
        return result;
    }
    
    /**
     * Detects the delimiter used in the CSV (comma, semicolon, or tab).
     */
    private char detectDelimiter(String content) {
        // Check first few lines to determine delimiter
        String[] lines = content.split("\\R", 10);
        
        int commaCount = 0;
        int semicolonCount = 0;
        int tabCount = 0;
        
        for (String line : lines) {
            if (line.trim().isEmpty()) continue;
            commaCount += countChar(line, ',');
            semicolonCount += countChar(line, ';');
            tabCount += countChar(line, '\t');
        }
        
        if (semicolonCount > commaCount && semicolonCount > tabCount) {
            return ';';
        } else if (tabCount > commaCount && tabCount > semicolonCount) {
            return '\t';
        }
        return ',';
    }
    
    private int countChar(String s, char c) {
        int count = 0;
        for (char ch : s.toCharArray()) {
            if (ch == c) count++;
        }
        return count;
    }
    
    /**
     * Finds the row index containing the actual data headers.
     * Some bank exports have metadata rows before the actual header row.
     */
    private int findHeaderRow(String content, char delimiter) {
        String[] lines = content.split("\\R");
        
        for (int i = 0; i < Math.min(lines.length, 10); i++) {
            String line = lines[i].toLowerCase();
            // Check if this line contains typical header keywords
            if (containsAny(line, DATE_HEADERS) || 
                containsAny(line, DESCRIPTION_HEADERS) ||
                containsAny(line, AMOUNT_HEADERS)) {
                return i;
            }
        }
        
        return 0; // Default to first line
    }
    
    /**
     * Detects column mappings from CSV headers by matching against known header names.
     */
    private ColumnMapping detectColumnMapping(Map<String, Integer> headerMap) {
        ColumnMapping mapping = new ColumnMapping();
        
        for (String header : headerMap.keySet()) {
            String normalizedHeader = header.toLowerCase().trim();
            
            if (mapping.dateColumn == null && containsAny(normalizedHeader, DATE_HEADERS)) {
                mapping.dateColumn = header;
            }
            
            if (mapping.descriptionColumn == null && containsAny(normalizedHeader, DESCRIPTION_HEADERS)) {
                mapping.descriptionColumn = header;
            }
            
            if (mapping.amountColumn == null && containsAny(normalizedHeader, AMOUNT_HEADERS)) {
                mapping.amountColumn = header;
            }
            
            if (mapping.creditColumn == null && containsAny(normalizedHeader, CREDIT_HEADERS)) {
                mapping.creditColumn = header;
            }
            
            if (mapping.debitColumn == null && containsAny(normalizedHeader, DEBIT_HEADERS)) {
                mapping.debitColumn = header;
            }
        }
        
        return mapping;
    }
    
    /**
     * Checks if a header matches any of the known variations.
     */
    private boolean containsAny(String header, List<String> variations) {
        for (String variation : variations) {
            if (header.contains(variation) || variation.contains(header)) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Parses a single CSV record into a Transaction.
     */
    private Transaction parseTransaction(CSVRecord record, ColumnMapping mapping, int rowNumber) {
        // Parse date
        String dateStr = record.get(mapping.dateColumn);
        if (dateStr == null || dateStr.trim().isEmpty()) {
            log.debug("Row {}: Empty date, skipping", rowNumber);
            return null;
        }
        
        LocalDateTime date = parseDate(dateStr.trim());
        if (date == null) {
            throw new IllegalArgumentException("Could not parse date: " + dateStr);
        }
        
        // Parse description
        String description = record.get(mapping.descriptionColumn);
        if (description == null || description.trim().isEmpty()) {
            description = "No description";
        }
        
        // Parse amount and determine operation
        double amount;
        TransactionOperation operation;
        
        if (mapping.amountColumn != null) {
            // Single amount column - sign determines operation
            String amountStr = record.get(mapping.amountColumn);
            amount = parseAmount(amountStr);
            
            if (amount < 0) {
                operation = TransactionOperation.MINUS;
                amount = Math.abs(amount);
            } else {
                operation = TransactionOperation.PLUS;
            }
        } else {
            // Separate credit/debit columns
            String creditStr = mapping.creditColumn != null ? record.get(mapping.creditColumn) : "";
            String debitStr = mapping.debitColumn != null ? record.get(mapping.debitColumn) : "";
            
            double creditAmount = parseAmount(creditStr);
            double debitAmount = parseAmount(debitStr);
            
            if (creditAmount > 0) {
                amount = creditAmount;
                operation = TransactionOperation.PLUS;
            } else if (debitAmount > 0) {
                amount = debitAmount;
                operation = TransactionOperation.MINUS;
            } else {
                log.debug("Row {}: No valid amount found, skipping", rowNumber);
                return null;
            }
        }
        
        // Create transaction
        Transaction transaction = new Transaction();
        transaction.setDate(date);
        transaction.setDescription(description.trim());
        transaction.setAmount(amount);
        transaction.setOperation(operation);
        transaction.setCategory("UNCATEGORIZED");
        
        return transaction;
    }
    
    /**
     * Parses a date string using multiple date formats.
     */
    private LocalDateTime parseDate(String dateStr) {
        for (DateTimeFormatter formatter : DATE_FORMATTERS) {
            try {
                LocalDate date = LocalDate.parse(dateStr, formatter);
                return date.atStartOfDay();
            } catch (DateTimeParseException e) {
                // Try next formatter
            }
        }
        return null;
    }
    
    /**
     * Parses an amount string, handling various number formats.
     */
    private double parseAmount(String amountStr) {
        if (amountStr == null || amountStr.trim().isEmpty()) {
            return 0.0;
        }
        
        String cleaned = amountStr.trim()
                // Remove currency symbols
                .replaceAll("[€$£¥]", "")
                // Remove spaces and non-breaking spaces
                .replaceAll("[\\s\\u00A0]", "")
                .trim();
        
        if (cleaned.isEmpty()) {
            return 0.0;
        }
        
        // Handle German number format (1.234,56) vs English (1,234.56)
        // If both comma and dot exist, determine which is decimal separator
        boolean hasComma = cleaned.contains(",");
        boolean hasDot = cleaned.contains(".");
        
        if (hasComma && hasDot) {
            // Both exist - determine order to identify decimal separator
            int commaIndex = cleaned.lastIndexOf(',');
            int dotIndex = cleaned.lastIndexOf('.');
            
            if (commaIndex > dotIndex) {
                // German format: 1.234,56 -> remove dots, replace comma with dot
                cleaned = cleaned.replace(".", "").replace(",", ".");
            } else {
                // English format: 1,234.56 -> remove commas
                cleaned = cleaned.replace(",", "");
            }
        } else if (hasComma) {
            // Only comma - check if it's a decimal separator
            int commaIndex = cleaned.lastIndexOf(',');
            String afterComma = cleaned.substring(commaIndex + 1);
            
            if (afterComma.length() <= 2) {
                // Likely decimal separator: 123,45 -> 123.45
                cleaned = cleaned.replace(",", ".");
            } else {
                // Likely thousands separator: 1,234 -> 1234
                cleaned = cleaned.replace(",", "");
            }
        }
        // If only dot exists, it's either decimal or thousands separator
        // We assume dot is decimal separator in this case
        
        try {
            return Double.parseDouble(cleaned);
        } catch (NumberFormatException e) {
            log.warn("Could not parse amount: {}", amountStr);
            return 0.0;
        }
    }
    
    /**
     * Internal class to hold column mapping information.
     */
    private static class ColumnMapping {
        String dateColumn;
        String descriptionColumn;
        String amountColumn;
        String creditColumn;
        String debitColumn;
    }
}