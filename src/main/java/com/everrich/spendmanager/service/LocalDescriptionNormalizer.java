package com.everrich.spendmanager.service;

import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * Local Java-based transaction description normalizer.
 * 
 * This class normalizes raw bank transaction descriptions by removing technical
 * identifiers (IBANs, BICs, account numbers, dates, reference numbers, etc.)
 * while preserving meaningful content like merchant names and transaction types.
 * 
 * This replaces the LLM-based normalization for improved performance (sub-millisecond
 * vs 500-2000ms per description) and zero API costs.
 * 
 * Examples:
 * - Input: "UNICREDIT BANK GMBH Kto.0046348710 PER 31.07.25..."
 *   Output: "Unicredit Bank Gmbh"
 * 
 * - Input: "Bargeldein-/auszahlung Deutsche Bank//Wiesloch/DE 2025-10-23T19:07:36 ..."
 *   Output: "Bargeldein-/auszahlung Deutsche Bank Wiesloch"
 * 
 * - Input: "Überweisung (Echtzeit) Sandeep Joseph COBADEHD055 DE212004115508674269..."
 *   Output: "Überweisung (Echtzeit) Sandeep Joseph"
 */
@Component
public class LocalDescriptionNormalizer {

    // ============================================================
    // IBAN and BIC patterns
    // ============================================================
    
    /**
     * IBAN pattern: 2 letters (country code) + 2 digits (check digits) + up to 30 alphanumeric (BBAN)
     * Examples: DE89370400440532013000, FR7630006000011234567890189
     */
    private static final Pattern IBAN_PATTERN = 
        Pattern.compile("[A-Z]{2}\\d{2}[A-Z0-9]{4,30}");
    
    /**
     * BIC/SWIFT pattern: 4 letters (bank) + 2 letters (country) + 2 alphanumeric (location) + optional 3 alphanumeric (branch)
     * Examples: COBADEHD055, DEUTDEDB, INGDDEFFXXX
     */
    private static final Pattern BIC_PATTERN = 
        Pattern.compile("\\b[A-Z]{4}[A-Z]{2}[A-Z0-9]{2}([A-Z0-9]{3})?\\b");

    // ============================================================
    // Account number patterns
    // ============================================================
    
    /**
     * German account number patterns
     * Examples: Kto.0046348710, Konto: 123456789, Kto 987654321
     */
    private static final Pattern ACCOUNT_PATTERN = 
        Pattern.compile("Kto\\.?\\s*\\d+|Konto[:\\s]+\\d+", Pattern.CASE_INSENSITIVE);

    // ============================================================
    // Date and timestamp patterns
    // ============================================================
    
    /**
     * ISO timestamp: 2025-10-23T19:07:36
     */
    private static final Pattern ISO_TIMESTAMP_PATTERN = 
        Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}");
    
    /**
     * German date with optional PER prefix: PER 31.07.25, 31.07.2025, 03.12.25
     */
    private static final Pattern GERMAN_DATE_PATTERN = 
        Pattern.compile("(PER\\s+)?\\d{2}\\.\\d{2}\\.\\d{2,4}");
    
    /**
     * ISO date format: 2025-10-23
     */
    private static final Pattern ISO_DATE_PATTERN = 
        Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    // ============================================================
    // Reference number patterns
    // ============================================================
    
    /**
     * Mandate reference: Mandatsref: ABC123, Mandatsref. 588880043460001
     */
    private static final Pattern MANDATE_REF_PATTERN = 
        Pattern.compile("Mandatsref[:.\\s]+[\\w-]+", Pattern.CASE_INSENSITIVE);
    
    /**
     * End-to-End reference: End-to-End-Ref.: 140500028161, End-to-End-Ref: ABC123
     */
    private static final Pattern END_TO_END_REF_PATTERN = 
        Pattern.compile("End-to-End-Ref[:.\\s]+[\\w-]+", Pattern.CASE_INSENSITIVE);
    
    /**
     * Creditor ID: Gläubiger-ID: DE50ZZZ00000094129
     * Note: Using explicit Unicode code points for ä (U+00E4) and Ä (U+00C4) for reliable matching
     */
    private static final Pattern CREDITOR_ID_PATTERN = 
        Pattern.compile("Gl[äÄaA]ubiger-ID[:.\\s]+[A-Za-z0-9]+");
    
    /**
     * Generic reference patterns: Ref. 123456, Referenz: ABC123
     */
    private static final Pattern GENERIC_REF_PATTERN = 
        Pattern.compile("(Ref\\.|Referenz)[:\\s]+[\\w-]+", Pattern.CASE_INSENSITIVE);

    // ============================================================
    // Long number patterns (likely identifiers)
    // ============================================================
    
    /**
     * Standalone long numeric sequences (10+ digits) - likely transaction IDs, internal references
     * Must be word-bounded to avoid removing numbers within meaningful text
     */
    private static final Pattern LONG_NUMBER_PATTERN = 
        Pattern.compile("(?<=\\s|^|/)\\d{10,}(?=\\s|$|/)");

    // ============================================================
    // Separator and formatting patterns
    // ============================================================
    
    /**
     * Multiple consecutive slashes: // or ///
     */
    private static final Pattern MULTIPLE_SLASHES_PATTERN = 
        Pattern.compile("/{2,}");
    
    /**
     * Ellipsis: ... or ..
     */
    private static final Pattern ELLIPSIS_PATTERN = 
        Pattern.compile("\\.{2,}");
    
    /**
     * Country code suffix: /DE, /AT, /CH at word boundary
     */
    private static final Pattern COUNTRY_CODE_SUFFIX_PATTERN = 
        Pattern.compile("/[A-Z]{2}(?=\\s|$|/)");
    
    /**
     * Multiple consecutive spaces
     */
    private static final Pattern MULTIPLE_SPACES_PATTERN = 
        Pattern.compile("\\s{2,}");
    
    /**
     * Leading/trailing slashes
     */
    private static final Pattern LEADING_TRAILING_SLASH_PATTERN = 
        Pattern.compile("^/+|/+$");

    // ============================================================
    // SEPA-specific patterns
    // ============================================================
    
    /**
     * SEPA transaction type suffixes that can be removed
     * Examples: "wiederholend", "einmalig"
     */
    private static final Pattern SEPA_SUFFIX_PATTERN = 
        Pattern.compile("\\s+(wiederholend|einmalig)\\s*$", Pattern.CASE_INSENSITIVE);

    /**
     * Normalizes a raw transaction description by removing technical identifiers
     * while preserving meaningful content.
     * 
     * @param description The raw transaction description from bank statement
     * @return Normalized description with technical identifiers removed
     */
    public String normalize(String description) {
        if (description == null || description.isBlank()) {
            return "";
        }
        
        String result = description;
        
        // Step 1: Remove reference numbers FIRST (before IBAN, as some ref values look like IBANs)
        // E.g., Gläubiger-ID: DE50ZZZ00000094129 - the value matches IBAN pattern
        result = MANDATE_REF_PATTERN.matcher(result).replaceAll("");
        result = END_TO_END_REF_PATTERN.matcher(result).replaceAll("");
        result = CREDITOR_ID_PATTERN.matcher(result).replaceAll("");
        result = GENERIC_REF_PATTERN.matcher(result).replaceAll("");
        
        // Step 2: Remove financial identifiers (IBANs, BICs, account numbers)
        result = IBAN_PATTERN.matcher(result).replaceAll("");
        result = BIC_PATTERN.matcher(result).replaceAll("");
        result = ACCOUNT_PATTERN.matcher(result).replaceAll("");
        
        // Step 3: Remove date and timestamp patterns
        result = ISO_TIMESTAMP_PATTERN.matcher(result).replaceAll("");
        result = GERMAN_DATE_PATTERN.matcher(result).replaceAll("");
        result = ISO_DATE_PATTERN.matcher(result).replaceAll("");
        
        // Step 4: Remove standalone long numbers (likely internal IDs)
        result = LONG_NUMBER_PATTERN.matcher(result).replaceAll("");
        
        // Step 5: Clean separators and formatting
        result = MULTIPLE_SLASHES_PATTERN.matcher(result).replaceAll(" ");
        result = ELLIPSIS_PATTERN.matcher(result).replaceAll("");
        result = COUNTRY_CODE_SUFFIX_PATTERN.matcher(result).replaceAll("");
        result = LEADING_TRAILING_SLASH_PATTERN.matcher(result).replaceAll("");
        
        // Step 6: Remove SEPA suffixes (optional cleanup)
        result = SEPA_SUFFIX_PATTERN.matcher(result).replaceAll("");
        
        // Step 7: Normalize whitespace
        result = MULTIPLE_SPACES_PATTERN.matcher(result).replaceAll(" ");
        result = result.trim();
        
        // Step 8: Normalize case (title case for all-uppercase words > 3 chars)
        result = normalizeCase(result);
        
        return result;
    }
    
    /**
     * Normalizes the case of words in the text.
     * Converts all-uppercase words (longer than 3 characters) to title case.
     * 
     * Examples:
     * - "UNICREDIT BANK GMBH" → "Unicredit Bank Gmbh"
     * - "AMAZON DE" → "Amazon DE" (DE kept uppercase as it's 2 chars)
     * - "REWE" → "Rewe"
     * 
     * @param text The text to normalize
     * @return Text with normalized case
     */
    private String normalizeCase(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        
        StringBuilder result = new StringBuilder();
        String[] words = text.split("\\s+");
        
        for (int i = 0; i < words.length; i++) {
            String word = words[i];
            
            // Skip empty words
            if (word.isEmpty()) {
                continue;
            }
            
            // Only transform words that are:
            // - All uppercase letters (including German umlauts)
            // - Longer than 3 characters
            // - Not likely to be acronyms (we keep short uppercase words)
            if (word.length() > 3 && isAllUppercase(word)) {
                word = toTitleCase(word);
            }
            
            if (result.length() > 0) {
                result.append(" ");
            }
            result.append(word);
        }
        
        return result.toString();
    }
    
    /**
     * Checks if a word consists only of uppercase letters (including German umlauts).
     */
    private boolean isAllUppercase(String word) {
        for (char c : word.toCharArray()) {
            // Allow uppercase letters, German umlauts, hyphens, and some punctuation
            if (Character.isLetter(c) && !Character.isUpperCase(c)) {
                return false;
            }
        }
        // Must have at least one letter
        return word.chars().anyMatch(Character::isLetter);
    }
    
    /**
     * Converts a word to title case (first letter uppercase, rest lowercase).
     * Handles German umlauts correctly.
     * 
     * @param word The word to convert
     * @return Word in title case
     */
    private String toTitleCase(String word) {
        if (word == null || word.isEmpty()) {
            return word;
        }
        
        StringBuilder result = new StringBuilder();
        boolean capitalizeNext = true;
        
        for (char c : word.toCharArray()) {
            if (Character.isLetter(c)) {
                if (capitalizeNext) {
                    result.append(Character.toUpperCase(c));
                    capitalizeNext = false;
                } else {
                    result.append(Character.toLowerCase(c));
                }
            } else {
                result.append(c);
                // Capitalize after hyphens (e.g., "BARGELDEIN-/AUSZAHLUNG" → "Bargeldein-/Auszahlung")
                if (c == '-') {
                    capitalizeNext = true;
                }
            }
        }
        
        return result.toString();
    }
}