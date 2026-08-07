# Feasibility Study: Local Java-Based Description Normalization

## Executive Summary

This study analyzes the feasibility of replacing the LLM-based transaction description normalization with local Java code. **The recommendation is to implement a hybrid approach** - using local Java code for the majority of cases (estimated 80-90%) while keeping an optional LLM fallback for complex cases.

## Current Implementation Analysis

### Where Normalization Happens

The normalization occurs in `VectorStoreService.java` with two methods:

1. **Single Description Normalization** (`normalizeDescription`)
   - Used when learning categories and during individual similarity searches
   - Makes one LLM call per description

2. **Batch Description Normalization** (`batchNormalizeDescriptions`)
   - Used during batch similarity search (before categorization)
   - Makes one LLM call for multiple descriptions (more efficient)

### Current LLM Prompt

```
You are an expert financial text processor. Analyze the following raw transaction description 
and return the keywords as a string that best describes the transaction. 

Examples:
    - Input: 'UNICREDIT BANK GMBH Kto.0046348710 PER 31.07.25...'
    - Output: Unicredit Bank GMBH
    - Input: 'Bargeldein-/auszahlung Deutsche Bank//Wiesloch/DE 2025-10-23T19:07:36 ...'
    - Output: Bargeldein-/auszahlung Deutsche Bank Wiesloch
    - Input: 'Überweisung (Echtzeit) Sandeep Joseph COBADEHD055 DE212004115508674269...'
    - Output: Überweisung Echtzeit Sandeep Joseph
```

### What the LLM Does

Based on the examples, the LLM performs these transformations:

| Transformation Type | Example |
|---------------------|---------|
| Remove account numbers | `Kto.0046348710` → removed |
| Remove dates/timestamps | `2025-10-23T19:07:36` → removed |
| Remove BIC/SWIFT codes | `COBADEHD055` → removed |
| Remove IBAN numbers | `DE212004115508674269` → removed |
| Remove ellipses | `...` → removed |
| Remove double slashes | `//` → single space |
| Remove country codes | `/DE` → removed |
| Remove period dates | `PER 31.07.25` → removed |
| Normalize case | `UNICREDIT BANK GMBH` → `Unicredit Bank GMBH` |
| Extract payee/location | Keep merchant names and locations |
| Clean parentheses | `(Echtzeit)` → `Echtzeit` |

## Feasibility Analysis

### ✅ **High Confidence Patterns (Can Be Handled Locally)**

| Pattern | Regex/Rule | Confidence |
|---------|------------|------------|
| IBAN numbers | `[A-Z]{2}\d{2}[A-Z0-9]{4,30}` | 99% |
| BIC/SWIFT codes | `[A-Z]{4}[A-Z]{2}[A-Z0-9]{2}([A-Z0-9]{3})?` | 99% |
| Account numbers | `Kto\.\d+`, `Konto[:\s]\d+` | 95% |
| ISO timestamps | `\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}` | 99% |
| Date patterns | `\d{2}\.\d{2}\.\d{2,4}`, `PER \d{2}\.\d{2}\.\d{2}` | 95% |
| Mandate references | `Mandatsref[:.\s]*[\w-]+` | 95% |
| End-to-End references | `End-to-End-Ref[:.\s]*[\w-]+` | 95% |
| Creditor IDs | `Gläubiger-ID[:.\s]*[\w]+` | 95% |
| Reference numbers | `Ref\.\s*\d+`, `Referenz[:.\s]*\d+` | 90% |
| Multiple slashes | `//+` | 99% |
| Ellipses | `\.{2,}` | 99% |
| Country code suffixes | `/[A-Z]{2}(?=\s|$)` | 90% |
| Numeric sequences (>10 digits) | `\d{10,}` (when standalone) | 85% |

### ⚠️ **Medium Confidence Patterns (Require Care)**

| Pattern | Challenge | Approach |
|---------|-----------|----------|
| Transaction type extraction | "SEPA-BASISLASTSCHRIFT" vs "SEPA-ÜBERWEISUNG" | Keyword dictionary |
| Bank name extraction | Various formats | Entity dictionary |
| Location extraction | "Wiesloch", "München" | Heuristics + dictionary |
| Case normalization | When to title-case vs preserve | Rules based on word type |

### ❌ **Low Confidence Patterns (May Need LLM)**

| Pattern | Challenge |
|---------|-----------|
| Free-form purpose text | Deciding what's meaningful vs noise |
| Abbreviation expansion | Unknown abbreviations |
| Multi-language descriptions | German/English mix |
| Novel formats | Unseen bank statement formats |

## Proposed Local Implementation

### Core Algorithm

```java
public class LocalDescriptionNormalizer {
    
    // Pre-compiled regex patterns for efficiency
    private static final Pattern IBAN_PATTERN = 
        Pattern.compile("[A-Z]{2}\\d{2}[A-Z0-9]{4,30}");
    private static final Pattern BIC_PATTERN = 
        Pattern.compile("[A-Z]{4}[A-Z]{2}[A-Z0-9]{2}([A-Z0-9]{3})?");
    private static final Pattern ACCOUNT_PATTERN = 
        Pattern.compile("Kto\\.?\\s*\\d+|Konto[:\\s]*\\d+", Pattern.CASE_INSENSITIVE);
    private static final Pattern TIMESTAMP_PATTERN = 
        Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}");
    private static final Pattern DATE_PATTERN = 
        Pattern.compile("(PER\\s+)?\\d{2}\\.\\d{2}\\.\\d{2,4}");
    private static final Pattern MANDATE_REF_PATTERN = 
        Pattern.compile("Mandatsref[:.\\s]*[\\w-]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern END_TO_END_PATTERN = 
        Pattern.compile("End-to-End-Ref[:.\\s]*[\\w-]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern CREDITOR_ID_PATTERN = 
        Pattern.compile("Gläubiger-ID[:.\\s]*[\\w]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern LONG_NUMBER_PATTERN = 
        Pattern.compile("(?<=\\s|^)\\d{10,}(?=\\s|$)");
    private static final Pattern MULTIPLE_SLASHES = Pattern.compile("//+");
    private static final Pattern ELLIPSIS = Pattern.compile("\\.{2,}");
    private static final Pattern COUNTRY_CODE_SUFFIX = Pattern.compile("/[A-Z]{2}(?=\\s|$)");
    private static final Pattern MULTIPLE_SPACES = Pattern.compile("\\s{2,}");
    
    public String normalize(String description) {
        if (description == null || description.isBlank()) {
            return "";
        }
        
        String result = description;
        
        // Step 1: Remove technical identifiers
        result = IBAN_PATTERN.matcher(result).replaceAll("");
        result = BIC_PATTERN.matcher(result).replaceAll("");
        result = ACCOUNT_PATTERN.matcher(result).replaceAll("");
        
        // Step 2: Remove date/time patterns
        result = TIMESTAMP_PATTERN.matcher(result).replaceAll("");
        result = DATE_PATTERN.matcher(result).replaceAll("");
        
        // Step 3: Remove reference numbers
        result = MANDATE_REF_PATTERN.matcher(result).replaceAll("");
        result = END_TO_END_PATTERN.matcher(result).replaceAll("");
        result = CREDITOR_ID_PATTERN.matcher(result).replaceAll("");
        result = LONG_NUMBER_PATTERN.matcher(result).replaceAll("");
        
        // Step 4: Clean separators
        result = MULTIPLE_SLASHES.matcher(result).replaceAll(" ");
        result = ELLIPSIS.matcher(result).replaceAll("");
        result = COUNTRY_CODE_SUFFIX.matcher(result).replaceAll("");
        
        // Step 5: Clean parentheses content that looks like codes
        result = cleanParentheses(result);
        
        // Step 6: Normalize whitespace
        result = MULTIPLE_SPACES.matcher(result).replaceAll(" ");
        result = result.trim();
        
        // Step 7: Normalize case (title case for known entities)
        result = normalizeCase(result);
        
        return result;
    }
    
    private String cleanParentheses(String text) {
        // Remove parentheses with purely technical content
        // Keep meaningful ones like "(Echtzeit)"
        return text.replaceAll("\\([^)]*\\d{5,}[^)]*\\)", "");
    }
    
    private String normalizeCase(String text) {
        // Title-case words that are all uppercase and > 3 chars
        // Preserve German umlauts and special characters
        StringBuilder result = new StringBuilder();
        String[] words = text.split("\\s+");
        for (int i = 0; i < words.length; i++) {
            String word = words[i];
            if (word.length() > 3 && word.equals(word.toUpperCase()) 
                    && word.matches("[A-ZÄÖÜ]+")) {
                // Title case: AMAZON -> Amazon
                word = word.substring(0, 1) + word.substring(1).toLowerCase();
            }
            result.append(word);
            if (i < words.length - 1) {
                result.append(" ");
            }
        }
        return result.toString();
    }
}
```

### Transaction Type Dictionary

```java
private static final Set<String> PRESERVE_KEYWORDS = Set.of(
    // Transaction types (keep as-is or with minor normalization)
    "Überweisung", "Lastschrift", "Gutschrift", "Kartenzahlung",
    "SEPA-BASISLASTSCHRIFT", "SEPA-ÜBERWEISUNG", "Echtzeit",
    "Bargeldein", "Bargeldauszahlung", "Dauerauftrag",
    
    // Common banks
    "Deutsche Bank", "Commerzbank", "Sparkasse", "Volksbank",
    "Unicredit", "ING", "DKB", "N26", "Postbank"
);
```

## Performance Comparison

| Metric | LLM-Based | Local Java |
|--------|-----------|------------|
| Latency (single) | 500-2000ms | <1ms |
| Latency (batch of 10) | 1000-3000ms | <5ms |
| Cost per call | $0.0001-0.001 | $0 |
| Reliability | 95-99% (network dependent) | 100% |
| Accuracy | ~95% | ~85-90% (with rules) |
| Handles novel formats | Yes | Limited |

## Recommendation: Hybrid Approach

### Phase 1: Implement Local Normalizer (Immediate)

1. Create `LocalDescriptionNormalizer` class with regex patterns
2. Add comprehensive unit tests with real examples
3. Keep LLM normalizer as fallback (configurable)

### Phase 2: Monitor and Improve (1-2 weeks)

1. Log cases where local normalization differs significantly from LLM
2. Add new patterns based on production data
3. Build pattern library specific to user's bank formats

### Phase 3: Optional LLM Fallback (Ongoing)

#### Decision Logic: When to Call LLM?

There are **three possible strategies** for deciding when to use the LLM fallback:

---

**Strategy A: No LLM Fallback (Recommended for Most Cases)**

The simplest approach - always use local processing, never call LLM for normalization.

```java
@Service
public class LocalOnlyNormalizer implements DescriptionNormalizer {
    
    public String normalize(String description) {
        return localNormalizer.normalize(description);
    }
}
```

**Pros**: Maximum performance, zero LLM cost, predictable behavior
**Cons**: May produce suboptimal normalization for unusual descriptions
**When to use**: When similarity search quality is "good enough" with local normalization

---

**Strategy B: Confidence-Based LLM Fallback**

Call LLM only when local normalization produces a "low confidence" result.

```java
@Service
public class HybridDescriptionNormalizer {
    
    @Value("${spendmanager.normalization.use-llm-fallback:false}")
    private boolean useLlmFallback;
    
    @Value("${spendmanager.normalization.confidence-threshold:0.8}")
    private double confidenceThreshold;
    
    public String normalize(String description) {
        NormalizationResult localResult = localNormalizer.normalizeWithConfidence(description);
        
        if (localResult.getConfidence() >= confidenceThreshold || !useLlmFallback) {
            return localResult.getNormalized();
        }
        
        // Fall back to LLM for low-confidence cases
        return llmNormalizer.normalize(description);
    }
}
```

**How is "confidence" calculated?**

```java
public class NormalizationResult {
    private String normalized;
    private double confidence;  // 0.0 to 1.0
    
    // Confidence is based on:
    // 1. How much content was removed (too much = suspicious)
    // 2. Whether recognized patterns were found (known patterns = high confidence)
    // 3. Length of remaining text (too short = may have over-stripped)
}

public NormalizationResult normalizeWithConfidence(String description) {
    String result = description;
    int patternsMatched = 0;
    int originalLength = description.length();
    
    // Apply patterns and count matches
    if (IBAN_PATTERN.matcher(result).find()) {
        result = IBAN_PATTERN.matcher(result).replaceAll("");
        patternsMatched++;
    }
    // ... repeat for other patterns
    
    // Calculate confidence
    double confidence = calculateConfidence(
        originalLength, 
        result.length(), 
        patternsMatched
    );
    
    return new NormalizationResult(result.trim(), confidence);
}

private double calculateConfidence(int originalLen, int resultLen, int patternsMatched) {
    // High confidence if:
    // - At least one known pattern was matched
    // - Result is not too short (at least 20% of original)
    // - Result is not too similar to original (some cleanup happened)
    
    double lengthRatio = (double) resultLen / originalLen;
    
    if (patternsMatched == 0 && lengthRatio > 0.95) {
        // Nothing was cleaned - might be an unknown format
        return 0.5;
    }
    
    if (lengthRatio < 0.15) {
        // Too much was removed - suspicious
        return 0.4;
    }
    
    if (patternsMatched >= 2) {
        // Multiple known patterns matched - high confidence
        return 0.95;
    }
    
    if (patternsMatched == 1 && lengthRatio > 0.3) {
        return 0.85;
    }
    
    return 0.7; // Default moderate confidence
}
```

**Pros**: Best of both worlds - fast for common cases, accurate for edge cases
**Cons**: More complex, still has some LLM costs
**When to use**: When you need high accuracy and can tolerate occasional LLM calls

---

**Strategy C: LLM Verification for Learning (Recommended)**

Never use LLM during similarity search, but optionally verify/improve local normalization when **learning** new categories (user corrections).

```java
@Service
public class LearningAwareNormalizer {
    
    @Value("${spendmanager.normalization.verify-on-learn:true}")
    private boolean verifyOnLearn;
    
    // During similarity search - always local (performance critical)
    public String normalizeForSearch(String description) {
        return localNormalizer.normalize(description);
    }
    
    // During learning - optionally use LLM for better quality
    public String normalizeForLearning(String description) {
        if (verifyOnLearn) {
            // Use LLM for learning to ensure high-quality vector store entries
            return llmNormalizer.normalize(description);
        }
        return localNormalizer.normalize(description);
    }
}
```

**Pros**: 
- Zero LLM cost during statement processing (the critical path)
- High-quality entries in vector store (learning happens less frequently)
- Best balance of performance and accuracy

**Cons**: Inconsistency between search and stored values (usually acceptable)
**When to use**: When learning quality matters more than real-time normalization

---

#### Recommended Approach

**For your use case, I recommend Strategy A (Local Only) or Strategy C (LLM for Learning Only):**

1. **Start with Strategy A** - pure local normalization
2. **Monitor similarity search quality** in production
3. **If needed, upgrade to Strategy C** - use LLM only when learning new categories

This avoids the complexity of confidence calculation while still providing a path to improve quality if needed.

## Implementation Checklist

- [ ] Create `LocalDescriptionNormalizer` class
- [ ] Implement all high-confidence regex patterns
- [ ] Add transaction type dictionary
- [ ] Add bank name dictionary
- [ ] Create comprehensive unit tests
- [ ] Add configuration flag to switch between local/LLM/hybrid
- [ ] Add logging to track normalization performance
- [ ] Add metrics for monitoring accuracy
- [ ] Document pattern library for future maintenance

## Risks and Mitigations

| Risk | Impact | Mitigation |
|------|--------|------------|
| Lower accuracy for edge cases | Some descriptions poorly normalized | Hybrid approach with LLM fallback |
| Maintenance burden | New patterns require code changes | Externalize patterns to config file |
| Bank-specific formats | Different banks = different patterns | Build pattern library over time |
| German-specific rules | May not work for other languages | Document language assumptions |

## Conclusion

**Local Java-based normalization is FEASIBLE and RECOMMENDED** for the following reasons:

1. **Significant Performance Gain**: <1ms vs 500-2000ms per description
2. **Cost Reduction**: Zero API costs vs accumulated LLM costs
3. **Predictable Patterns**: Bank statement descriptions follow well-defined formats
4. **Manageable Complexity**: The transformation rules from the examples are straightforward
5. **Hybrid Safety Net**: LLM fallback can handle edge cases if needed

The estimated development effort is **2-3 days** for a robust implementation with good test coverage.

---

## Implementation Status (COMPLETED ✅)

**Date:** July 8, 2026

The local Java-based normalizer has been successfully implemented and tested.

### Files Created/Modified

1. **`LocalDescriptionNormalizer.java`** - New class with comprehensive regex patterns
2. **`LocalDescriptionNormalizerTest.java`** - 41 unit tests covering all patterns
3. **`VectorStoreService.java`** - Modified to use local normalizer with property toggle
4. **`application.properties`** - Added `spendmanager.normalization.use-local=true`
5. **`pom.xml`** - Added UTF-8 encoding for Maven compiler

### Key Implementation Details

- **Pattern Order Matters**: Reference patterns (Gläubiger-ID, Mandatsref, etc.) must be processed BEFORE IBAN patterns because some reference values (e.g., `DE50ZZZ00000094129`) match the IBAN format
- **UTF-8 Encoding**: Added `project.build.sourceEncoding=UTF-8` and compiler encoding configuration for proper handling of German umlauts in regex patterns
- **Property Toggle**: Use `spendmanager.normalization.use-local=false` to revert to LLM-based normalization if issues are found

### Test Results

- **Total Tests**: 41
- **Passed**: 41
- **Failed**: 0
- **Coverage Areas**:
  - IBAN removal
  - BIC/SWIFT removal
  - Account number removal
  - Date/timestamp removal
  - Reference number removal (including Gläubiger-ID)
  - Long number removal
  - Separator cleaning
  - Case normalization
  - SEPA suffix removal
  - Real-world examples from LLM prompt
  - Performance benchmarks

### Production Monitoring Checklist

- [ ] Monitor normalization quality in production
- [ ] Collect any edge cases not handled by regex patterns
- [ ] Refine patterns based on real-world data
- [ ] Consider deprecating LLM-based normalization after sufficient validation
