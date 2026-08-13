# Table-Based Categorization Concept

## Executive Summary

This document proposes replacing the current Redis vector database approach for transaction categorization with a simpler, more cost-effective table-based approach. The new approach stores **categorization rules** (extracted keywords with their assigned categories) in a PostgreSQL table and sends them as **tabular guidance** to the LLM during categorization.

---

## Current Architecture (Redis Vector-Based)

### How It Works Today

1. **Learning Phase** (`VectorStoreService.learnCorrectCategory`):
   - When a transaction is manually categorized, the description is normalized
   - An embedding vector is generated using GCP Vertex AI
   - The document is stored in Redis with: `category`, `operation`, `account`, `tenant`, `content_payload`, `vector`

2. **Categorization Phase** (`RagService.categorizeBatchSequential`):
   - Transaction description is normalized
   - Embedding vector is generated for the query
   - Redis vector similarity search finds similar documents
   - Similar documents are formatted as context and sent to LLM

### Current Costs & Issues

1. **Redis Service Cost**: ~$50-150/month for managed Redis
2. **Embedding API Costs**: Each categorization requires embedding generation
3. **Accuracy Issues**: Vector similarity doesn't always capture the user's intent
4. **Complexity**: Managing Redis infrastructure and vector indices

---

## Proposed Architecture (Table-Based)

### Core Concept

1. **Learning Phase**: When a transaction is manually categorized, use LLM to extract meaningful keywords from the description, then store a rule with account, operation, keywords, and category.

2. **Categorization Phase**: Query the table for all rules matching the transaction's account, format them as a guidance table, and include in the LLM prompt.

---

## Detailed Design

### 1. Data Model

#### Entity: `CategorizationRule`

```java
@Entity
@Table(name = "categorization_rule")
public class CategorizationRule {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @ManyToOne
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;
    
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionOperation operation;  // PLUS or MINUS
    
    @ManyToOne
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;
    
    @Column(name = "keywords", columnDefinition = "TEXT", nullable = false)
    private String keywords;  // LLM-extracted keywords, comma-separated
    
    @Column(name = "original_description", columnDefinition = "TEXT")
    private String originalDescription;  // For reference/debugging
    
    @Column(name = "created_at")
    private LocalDateTime createdAt;
    
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
```

#### Database Schema

```sql
CREATE TABLE categorization_rule (
    id BIGSERIAL PRIMARY KEY,
    account_id BIGINT NOT NULL REFERENCES account(id),
    operation VARCHAR(10) NOT NULL,  -- 'PLUS' or 'MINUS'
    category_id BIGINT NOT NULL REFERENCES category(id),
    keywords TEXT NOT NULL,          -- LLM-extracted keywords
    original_description TEXT,       -- Original for reference
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP
);

-- Index for account-based lookups
CREATE INDEX idx_categorization_rule_account 
    ON categorization_rule(account_id);
```

---

### 2. Learning Phase (Rule Creation)

#### Trigger
When a transaction is **manually categorized** (user selects/changes the category).

#### Process Flow

```
User Action: Categorize transaction as "PayPal Demand"
    Transaction:
        Account: "Commerzbank"
        Operation: MINUS
        Description: "PayPal Europe S.a.r.l. et Cie S.C.A / 30.04 / 1049922347904/PP.1544.PP/. , 
                     Ihr Einkauf bei / End-to-End-Ref.: 1049922347904 / Mandatsref: 5H5J224NDYKZL / 
                     Gläubiger-ID: LU96ZZZ0000000000000000058 / SEPA-BASISLASTSCHRIFT wiederholend"
        Category: "PayPal Demand"

System:
1. Call LLM to extract keywords from description
   → LLM returns: "PayPal Europe S.a.r.l. et Cie S.C.A, Ihr Einkauf bei, SEPA-BASISLASTSCHRIFT wiederholend"

2. Create or update CategorizationRule:
   {
       account: "Commerzbank",
       operation: MINUS,
       category: "PayPal Demand",
       keywords: "PayPal Europe S.a.r.l. et Cie S.C.A, Ihr Einkauf bei, SEPA-BASISLASTSCHRIFT wiederholend",
       originalDescription: <full description>
   }
```

#### LLM Prompt for Keyword Extraction

```
Extract the meaningful keywords from this bank transaction description that would help 
identify similar transactions in the future. Remove technical identifiers like:
- IBANs, BICs, account numbers
- Reference numbers (End-to-End-Ref, Mandatsref, Gläubiger-ID)
- Dates and timestamps
- Transaction IDs

Keep:
- Company/merchant names
- Transaction type indicators
- Meaningful descriptive phrases

Transaction Description:
"{description}"

Return only the extracted keywords as a comma-separated list.

Keywords:
```

#### Example Keyword Extractions

| Original Description | Extracted Keywords |
|---------------------|-------------------|
| `PayPal Europe S.a.r.l. et Cie S.C.A / 30.04 / 1049922347904/PP.1544.PP/. , Ihr Einkauf bei / End-to-End-Ref.: 1049922347904 / Mandatsref: 5H5J224NDYKZL / Gläubiger-ID: LU96ZZZ0000000000000000058 / SEPA-BASISLASTSCHRIFT wiederholend` | `PayPal Europe S.a.r.l. et Cie S.C.A, Ihr Einkauf bei, SEPA-BASISLASTSCHRIFT wiederholend` |
| `SAP SE GEHALT/LOHN JUNE 2025 REF123456` | `SAP SE, GEHALT/LOHN` |
| `REWE SAGT DANKE 12345 Ref.ABC123 Kto.987654` | `REWE SAGT DANKE` |
| `Amazon EU S.a.r.l. / Marketplace Order #123-456` | `Amazon EU S.a.r.l., Marketplace` |

---

### 3. Categorization Phase (Using Rules)

#### Trigger
When categorizing new transactions (batch or single).

#### Process Flow

```
Transactions to categorize:
    Transaction 1: Account="Commerzbank", Operation=MINUS, Description="PayPal Europe ..."
    Transaction 2: Account="Commerzbank", Operation=PLUS, Description="SAP SE PAYROLL..."
    Transaction 3: Account="DKB", Operation=MINUS, Description="Amazon EU..."

System:
1. Collect unique account IDs from transactions: [Commerzbank, DKB]

2. Query categorization rules for these accounts:
   SELECT * FROM categorization_rule 
   WHERE account_id IN (commerzbank_id, dkb_id)
   ORDER BY account_id, operation, keywords

3. Format rules as guidance table for LLM prompt

4. Call LLM with transactions + guidance table
```

#### LLM Prompt Structure (Updated)

```
You are an expert financial assistant whose sole job is to categorize financial transactions.
You will be given MULTIPLE transactions to categorize in a single request.

For each transaction, you should:
1. Primarily rely on the description to assign the most fitting category
2. Use the operation type (PLUS for credit, MINUS for debit) as additional context
3. Use the CATEGORIZATION GUIDANCE TABLE below if the keywords match

CATEGORIZATION GUIDANCE:
Please use the following table as guidance for categorization. If the keywords in the table 
match the transaction description, use the corresponding category. If no guidance fits, 
categorize based on the description and available categories.

| Account      | Operation | Keywords                                                                    | Category       |
|--------------|-----------|-----------------------------------------------------------------------------|----------------|
| Commerzbank  | MINUS     | PayPal Europe S.a.r.l. et Cie S.C.A, Ihr Einkauf bei, SEPA-BASISLASTSCHRIFT | PayPal Demand  |
| Commerzbank  | PLUS      | SAP SE, GEHALT/LOHN                                                         | Salary         |
| Commerzbank  | MINUS     | REWE SAGT DANKE                                                             | Groceries      |
| DKB          | MINUS     | Amazon EU S.a.r.l., Marketplace                                             | Shopping       |

AVAILABLE CATEGORIES:
{categories}

TRANSACTIONS TO CATEGORIZE:
{transactions}

Return a JSON array with category assignments...
```

---

### 4. Rule Selection Logic

#### For Single Transaction
```java
public List<CategorizationRule> findRulesForTransaction(Transaction transaction) {
    return categorizationRuleRepository
        .findByAccountId(transaction.getAccount().getId());
}
```

#### For Batch of Transactions
```java
public List<CategorizationRule> findRulesForBatch(List<Transaction> transactions) {
    // Collect unique account IDs
    Set<Long> accountIds = transactions.stream()
        .map(t -> t.getAccount().getId())
        .collect(Collectors.toSet());
    
    // Fetch all rules for these accounts
    return categorizationRuleRepository
        .findByAccountIdIn(accountIds);
}
```

#### Formatting Rules as Guidance Table
```java
public String formatRulesAsGuidanceTable(List<CategorizationRule> rules) {
    if (rules.isEmpty()) {
        return "No historical categorization guidance available.";
    }
    
    StringBuilder sb = new StringBuilder();
    sb.append("| Account | Operation | Keywords | Category |\n");
    sb.append("|---------|-----------|----------|----------|\n");
    
    for (CategorizationRule rule : rules) {
        sb.append(String.format("| %s | %s | %s | %s |\n",
            rule.getAccount().getName(),
            rule.getOperation().name(),
            rule.getKeywords(),
            rule.getCategory().getName()
        ));
    }
    
    return sb.toString();
}
```

---

### 5. Handling Edge Cases

#### Case: No Rules Exist Yet (Cold Start)
- System has no categorization rules
- LLM categorizes based on description and available categories only
- Same behavior as current system when vector store is empty

#### Case: Rule Update (Re-categorization)
When user re-categorizes an already-categorized transaction:

```
Option A: Create new rule (keep history)
- Always create a new rule entry
- May result in multiple rules for same keywords
- Pro: Full history preserved
- Con: Table grows, potential conflicts

Option B: Update existing rule (recommended)
- Check if a rule with same account + operation + similar keywords exists
- If yes: update the category
- If no: create new rule
- Pro: Cleaner table, reflects current user intent
- Con: Loses history of changes
```

**Recommendation**: Option B with soft delete (add `is_active` flag) for audit trail.

#### Case: Conflicting Rules
Same keywords could have different categories:
```
| Account     | Operation | Keywords           | Category    |
|-------------|-----------|-------------------|-------------|
| Commerzbank | MINUS     | Amazon EU         | Shopping    |
| Commerzbank | MINUS     | Amazon EU, AWS    | Cloud/IT    |
```

**Solution**: 
- LLM uses its judgment when multiple rules partially match
- Rules with more specific keywords (more matches) should be preferred
- Consider adding a `priority` or `specificity` field

---

### 6. Performance Considerations

#### Table Size Estimation
- Average user: 20-50 accounts × 10-20 rules per account = 200-1000 rules
- Power user: 50 accounts × 50 rules = 2500 rules
- Still manageable for simple SELECT queries

#### Query Performance
```sql
-- Simple index scan, very fast
SELECT * FROM categorization_rule 
WHERE account_id IN (1, 2, 3)
ORDER BY account_id, operation;
```

#### Guidance Table Size in Prompt
- Each rule ≈ 100-150 characters
- 50 rules ≈ 5-7.5KB of context
- Well within LLM context limits (even 4K token models)

**Limit**: Consider capping at 100 rules per categorization call to avoid excessive prompt size.

---

### 7. LLM Costs Analysis

#### Current (Redis + Embeddings)
- Embedding generation per transaction: ~$0.0001-0.0005
- LLM categorization call: ~$0.001-0.01
- Redis service: ~$50-150/month fixed

#### Proposed (Table-Based)
- Keyword extraction per manual categorization: ~$0.001-0.005 (one-time)
- LLM categorization call: ~$0.001-0.01 (same as before)
- No Redis cost
- No embedding cost during categorization

**Net savings**: Eliminates Redis cost ($50-150/month) + embedding costs (~$0.0001-0.0005 per transaction)

**New cost**: Keyword extraction LLM call when learning (~$0.001-0.005 per manual categorization)

---

### 8. Updated Prompt Template

#### File: `category-rag-prompt-batch-v2.st`

```
You are an expert financial assistant whose sole job is to categorize financial transactions.
You will be given MULTIPLE transactions to categorize in a single request.

For each transaction, you should:
1. Check if the CATEGORIZATION GUIDANCE TABLE below contains a matching rule
2. If keywords from a rule match the transaction description, use that category
3. Consider the operation type (PLUS for credit, MINUS for debit) when matching
4. If no guidance matches, categorize based on the description and available categories

CATEGORIZATION GUIDANCE:
{guidanceTable}

RULES:
1. Each category MUST be chosen from this list: {categories}
2. DO NOT create new category names - only use existing categories from the list above
3. Prefer guidance table matches over generic categorization
4. Return your answer as a JSON array in the EXACT format shown below

TRANSACTIONS TO CATEGORIZE:
{transactions}

You must categorize exactly {transactionCount} transaction(s).

Return a JSON array with exactly {transactionCount} entries:
[
  {{"index": 1, "category": "ExactCategoryName"}},
  {{"index": 2, "category": "ExactCategoryName"}}
]

IMPORTANT: Return ONLY the JSON array, no additional text or explanation.

JSON Response:
```

---

### 9. Migration Strategy

#### Phase 1: Implement Parallel System
1. Create `CategorizationRule` entity and repository
2. Implement `CategorizationRuleService`
3. When learning (manual categorization):
   - Write to BOTH Redis (existing) AND new table
4. Log comparison of results

#### Phase 2: Feature Flag Switchover
1. Add property: `spendmanager.categorization.use-table-based=false`
2. When `true`:
   - Use table-based rules for categorization
   - Skip Redis/embeddings entirely
3. Monitor accuracy and user feedback

#### Phase 3: Deprecate Redis
1. Remove Redis writes in learning phase
2. Remove VectorStoreService dependency from RagService
3. Remove Redis configuration
4. Clean up unused code

---

## Summary: Key Design Decisions

| Decision Point | Choice | Rationale |
|---------------|--------|-----------|
| Keyword Extraction | LLM-based | More intelligent extraction than regex |
| Rule Storage | PostgreSQL table | Uses existing infrastructure |
| Rule Selection | Account-based | User's example shows account-specific rules |
| Guidance Format | Markdown table | Clear, structured format for LLM |
| Matching Logic | LLM judgment | Let LLM decide if keywords match |
| Rule Updates | Update existing | Cleaner table, current intent |
| Context Limit | ~100 rules max | Balance between guidance and prompt size |

---

## Finalized Design Decisions

### 1. Keyword Extraction Timing: **Asynchronous**
- Rule creation/update happens in **background** (async)
- User is **not blocked** when saving a transaction
- Use existing `@Async` task executor infrastructure
- Rule appears in the system after LLM call completes

#### Multi-Tenant Async Handling (CRITICAL)
The application uses a multi-tenant architecture where `TenantContext` is stored in `ThreadLocal`. 
Since async tasks run on different threads from the thread pool, the tenant context is **NOT automatically propagated**.

**Solution (Already Established Pattern):**
The codebase already handles this correctly in services like `AccountBalanceService`. We will follow the same pattern:

1. **Capture `tenantId` BEFORE the async call** (in the calling method, while still on the request thread)
2. **Pass `tenantId` as a parameter** to the async method
3. **Set `TenantContext.setTenantId(tenantId)` at the START** of the async method
4. **Clear `TenantContext.clear()` in the finally block** to prevent memory leaks

**Example Pattern (from existing `AccountBalanceService`):**
```java
// In Controller/Service (request thread - has TenantContext)
String tenantId = TenantContext.getTenantId();  // Capture before async
categorizationRuleService.extractAndSaveRuleAsync(transaction, tenantId);

// In CategorizationRuleService (async method - new thread)
@Async("transactionProcessingExecutor")
public void extractAndSaveRuleAsync(Transaction transaction, String tenantId) {
    try {
        // Set tenant context for the async thread
        TenantContext.setTenantId(tenantId);
        
        // Business logic here - database calls will use correct tenant schema
        String keywords = llmKeywordExtractor.extract(transaction.getDescription());
        CategorizationRule rule = new CategorizationRule();
        // ... create/update rule
        ruleRepository.save(rule);  // Uses tenant's schema
        
    } finally {
        TenantContext.clear();  // Prevent memory leaks
    }
}
```

**Important Notes:**
- Transaction entities passed to async methods must have their relationships loaded (use eager fetch or `@Transactional` scope)
- Or pass primitive values (accountId, categoryId, description) instead of entity references
- Use `TransactionTemplate` for programmatic transaction management inside async methods (since `@Transactional` on self-invoked methods doesn't work through Spring proxy)

**GCP Cloud Run Compatibility:**
This pattern works correctly on GCP Cloud Run because:
- The thread pool is managed by Spring Boot within the container
- Each async task sets its own tenant context
- Cloud Run's request-based billing is not affected (the container stays alive while processing)
- Multiple concurrent async tasks from different tenants are isolated via their own `TenantContext.setTenantId()` calls

### 2. Rule Deduplication: **Smart Update**
- When a transaction is manually categorized:
  1. Extract keywords using LLM (async)
  2. Check if existing rule with same **account + operation + keywords** exists
  3. If **exact match** found:
     - If category is same: do nothing (rule already correct)
     - If category different: update the category
  4. If **no exact match**: create new rule
- This avoids redundant rules while allowing category corrections

### 3. Operation as Key Parameter
- Operation (PLUS/MINUS) is a **critical** matching criterion
- LLM prompt explicitly instructs to consider operation when matching rules
- Same merchant can have different categories for PLUS vs MINUS (e.g., PayPal refund vs PayPal purchase)

### 4. User Interface: **Rule Management UI**
- Add new "Categorization Rules" management page
- Place under "Auto Categorization" section on Home page
- **Replace** the current "Training Data Management" (Redis-based) UI
- **Keep Redis code available** for fallback during pilot phase
- UI features:
  - View all rules (with pagination)
  - Filter by account, operation, category
  - Edit rule keywords/category
  - Delete rules
  - Search rules by keyword

---

## User Interface Design

### 1. Navigation
```
Home Page
└── Auto Categorization (section)
    ├── Categorization Rules [NEW - Table-based]  ← Active by default
    └── Training Data (Legacy) [Redis-based]       ← Available for fallback
```

### 2. Categorization Rules Page

#### Header
```
+----------------------------------------------------------+
| Categorization Rules                                      |
| These rules guide the AI in categorizing your transactions|
+----------------------------------------------------------+
```

#### Filters
```
+----------------------------------------------------------+
| Account: [Dropdown - All]  Operation: [All/PLUS/MINUS]   |
| Category: [Dropdown - All]  Search: [_______________]    |
+----------------------------------------------------------+
```

#### Rules Table
```
+----------+----------+-----------------------------------------+------------+--------+
| Account  | Operation| Keywords                                | Category   | Actions|
+----------+----------+-----------------------------------------+------------+--------+
| Commerz  | MINUS    | PayPal Europe, Ihr Einkauf bei          | PayPal     | ✏️ 🗑️  |
| Commerz  | PLUS     | SAP SE, GEHALT/LOHN                     | Salary     | ✏️ 🗑️  |
| DKB      | MINUS    | REWE SAGT DANKE                         | Groceries  | ✏️ 🗑️  |
| DKB      | MINUS    | Amazon EU, Marketplace                  | Shopping   | ✏️ 🗑️  |
+----------+----------+-----------------------------------------+------------+--------+
| << First | < Prev   |                          Page 1 of 5   | Next > | Last >>|
+----------+----------+-----------------------------------------+------------+--------+
```

#### Edit Modal
```
+----------------------------------------------------------+
| Edit Categorization Rule                                  |
+----------------------------------------------------------+
| Account:   [Commerzbank    ▼] (read-only)                |
| Operation: [MINUS          ▼] (read-only)                |
| Keywords:  [PayPal Europe, Ihr Einkauf bei_________]     |
| Category:  [PayPal Demand  ▼]                            |
|                                                          |
| Original Description:                                     |
| "PayPal Europe S.a.r.l. et Cie S.C.A / 30.04 /..."       |
|                                                          |
|                        [Cancel]  [Save Changes]          |
+----------------------------------------------------------+
```

### 3. Feature Toggle
```properties
# application.properties
spendmanager.categorization.use-table-based=true  # false = use Redis
```

When `use-table-based=false`:
- Rule learning writes to Redis only
- Categorization uses Redis similarity search
- "Training Data (Legacy)" UI is available
- "Categorization Rules" UI shows: "Table-based categorization is disabled"

---

## Updated Process Flows

### Rule Learning (Async) - Trigger Points

**Trigger 1: Manual Transaction Creation**
```
User creates new transaction → Save to DB → 
  → Queue async task: extractKeywordsAndCreateRule(transaction)
  → User sees success immediately
  → Background: LLM extracts keywords → Create/update rule
```

**Trigger 2: Transaction Category Update**
```
User changes category on existing transaction → Update DB →
  → Queue async task: extractKeywordsAndUpdateRule(transaction)
  → User sees success immediately
  → Background: LLM extracts keywords → Create/update rule
```

### Rule Deduplication Logic

```java
@Async
public void extractKeywordsAndCreateOrUpdateRule(Transaction transaction, String tenantId) {
    // 1. Extract keywords using LLM
    String keywords = llmKeywordExtractor.extract(transaction.getDescription());
    
    // 2. Look for existing rule
    Optional<CategorizationRule> existingRule = ruleRepository.findByAccountIdAndOperationAndKeywords(
        transaction.getAccount().getId(),
        transaction.getOperation(),
        keywords
    );
    
    if (existingRule.isPresent()) {
        CategorizationRule rule = existingRule.get();
        if (!rule.getCategory().equals(transaction.getCategoryEntity())) {
            // Category changed - update the rule
            rule.setCategory(transaction.getCategoryEntity());
            rule.setUpdatedAt(LocalDateTime.now());
            ruleRepository.save(rule);
            log.info("Updated rule {} with new category: {}", rule.getId(), transaction.getCategory());
        } else {
            // Same category - no action needed
            log.debug("Rule already exists with same category, skipping: {}", keywords);
        }
    } else {
        // Create new rule
        CategorizationRule newRule = new CategorizationRule();
        newRule.setAccount(transaction.getAccount());
        newRule.setOperation(transaction.getOperation());
        newRule.setCategory(transaction.getCategoryEntity());
        newRule.setKeywords(keywords);
        newRule.setOriginalDescription(transaction.getDescription());
        newRule.setCreatedAt(LocalDateTime.now());
        ruleRepository.save(newRule);
        log.info("Created new rule for keywords: {}", keywords);
    }
}
```

---

## Updated LLM Prompt (Operation Emphasis)

```
You are an expert financial assistant whose sole job is to categorize financial transactions.

IMPORTANT RULES FOR MATCHING:
1. The Operation type (PLUS/MINUS) is CRITICAL for matching
   - PLUS = money coming IN (salary, refunds, transfers in)
   - MINUS = money going OUT (purchases, payments, transfers out)
2. A rule with Operation=MINUS should NOT be applied to a transaction with Operation=PLUS, and vice versa
3. The same merchant can have different categories depending on operation:
   - "PayPal" + MINUS → "PayPal Purchases"
   - "PayPal" + PLUS  → "PayPal Refunds"

CATEGORIZATION GUIDANCE:
Use the following table as guidance. Match BOTH the keywords AND the operation type.
If no rule matches, categorize based on the description and available categories.

{guidanceTable}

AVAILABLE CATEGORIES:
{categories}

TRANSACTIONS TO CATEGORIZE:
{transactions}

Return a JSON array with category assignments for exactly {transactionCount} transaction(s):
[
  {{"index": 1, "category": "ExactCategoryName"}},
  {{"index": 2, "category": "ExactCategoryName"}}
]

JSON Response:
```

---

## Migration Strategy (Updated)

### Phase 1: Parallel Implementation (Pilot)
1. Create `CategorizationRule` entity, repository, service
2. Create Rule Management UI (replaces Training Data Management)
3. Implement async rule learning
4. Add feature flag `spendmanager.categorization.use-table-based=false` (disabled by default)
5. **Keep Redis code intact** for fallback

### Phase 2: Pilot Testing
1. Enable table-based for select users/tenants
2. Compare categorization accuracy
3. Gather feedback on Rule Management UI
4. Fix issues discovered during pilot

### Phase 3: Full Rollout
1. Enable table-based for all users
2. Monitor for issues
3. Keep Redis available but hidden from UI

### Phase 4: Redis Deprecation (Future)
1. Confirm table-based is stable and effective
2. Remove Redis code and dependencies
3. Delete Redis configuration
4. Archive this concept document
