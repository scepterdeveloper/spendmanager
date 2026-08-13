-- Migration V3: Add categorization_rule table for table-based categorization
-- This table stores rules learned from user's manual categorizations
-- Rules are used as guidance for the LLM when categorizing new transactions

CREATE TABLE IF NOT EXISTS categorization_rule (
    id BIGSERIAL PRIMARY KEY,
    account_id BIGINT NOT NULL REFERENCES account(id) ON DELETE CASCADE,
    operation VARCHAR(10) NOT NULL CHECK (operation IN ('PLUS', 'MINUS')),
    category_id BIGINT NOT NULL REFERENCES category(id) ON DELETE CASCADE,
    keywords TEXT NOT NULL,
    original_description TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP
);

-- Index for account-based lookups (most common query pattern)
CREATE INDEX IF NOT EXISTS idx_categorization_rule_account 
    ON categorization_rule(account_id);

-- Composite index for deduplication checks
CREATE INDEX IF NOT EXISTS idx_categorization_rule_account_operation_keywords 
    ON categorization_rule(account_id, operation, keywords);

-- Index for category-based queries (when deleting categories)
CREATE INDEX IF NOT EXISTS idx_categorization_rule_category 
    ON categorization_rule(category_id);

COMMENT ON TABLE categorization_rule IS 'Stores categorization rules learned from manual categorizations. Used as LLM guidance for auto-categorization.';
COMMENT ON COLUMN categorization_rule.account_id IS 'The account this rule applies to';
COMMENT ON COLUMN categorization_rule.operation IS 'Transaction operation type: PLUS (credit) or MINUS (debit)';
COMMENT ON COLUMN categorization_rule.category_id IS 'The category to assign when this rule matches';
COMMENT ON COLUMN categorization_rule.keywords IS 'LLM-extracted keywords from transaction description';
COMMENT ON COLUMN categorization_rule.original_description IS 'Original transaction description for reference';