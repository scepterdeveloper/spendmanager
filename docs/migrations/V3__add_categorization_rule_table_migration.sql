-- Migration V3: Add categorization_rule table for table-based categorization
-- Date: 2026-08-13
-- Description: Adds the categorization_rule table to all existing tenant schemas
--              This table stores rules learned from user's manual categorizations
--              for LLM guidance during auto-categorization.
--
-- IMPORTANT: Run this on your PostgreSQL database (Supabase) BEFORE deploying the new code
--            to existing tenant schemas. New tenants will automatically get this table.
--
-- How to run:
--   Option 1: Supabase SQL Editor - paste this script and run
--   Option 2: psql -h db.gazvxpunuuhvenlaicem.supabase.co -U postgres -d postgres -f V3__add_categorization_rule_table_migration.sql
--
-- What this script does:
--   1. Creates the categorization_rule table in the public schema (if not already created by Hibernate)
--   2. Creates the table in ALL existing tenant schemas (tenant_*)
--   3. Adds all necessary indexes for performance
--   4. Safely skips if table already exists (idempotent)
--
-- Dependencies:
--   - Requires account table to exist (foreign key reference)
--   - Requires category table to exist (foreign key reference)

-- ============================================
-- STEP 1: Create table in PUBLIC schema
-- ============================================
-- Note: Hibernate with ddl-auto=update will likely create this automatically,
-- but we include it here for completeness and to ensure proper structure.

CREATE TABLE IF NOT EXISTS public.categorization_rule (
    id BIGSERIAL PRIMARY KEY,
    account_id BIGINT NOT NULL REFERENCES public.account(id) ON DELETE CASCADE,
    operation VARCHAR(10) NOT NULL CHECK (operation IN ('PLUS', 'MINUS')),
    category_id BIGINT NOT NULL REFERENCES public.category(id) ON DELETE CASCADE,
    keywords TEXT NOT NULL,
    original_description TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP
);

-- Create indexes for public schema
CREATE INDEX IF NOT EXISTS idx_categorization_rule_account 
    ON public.categorization_rule(account_id);

CREATE INDEX IF NOT EXISTS idx_categorization_rule_account_operation_keywords 
    ON public.categorization_rule(account_id, operation, keywords);

CREATE INDEX IF NOT EXISTS idx_categorization_rule_category 
    ON public.categorization_rule(category_id);

COMMENT ON TABLE public.categorization_rule IS 'Stores categorization rules learned from manual categorizations. Used as LLM guidance for auto-categorization.';

-- Note: RAISE NOTICE can only be used inside DO blocks, so we skip logging here
-- The public schema table is now created/verified

-- ============================================
-- STEP 2: Create table in ALL tenant schemas
-- ============================================
DO $$
DECLARE
    schema_name TEXT;
    table_exists BOOLEAN;
    account_table_exists BOOLEAN;
    category_table_exists BOOLEAN;
BEGIN
    -- Loop through all tenant schemas (those starting with 'tenant_')
    FOR schema_name IN 
        SELECT nspname 
        FROM pg_namespace 
        WHERE nspname LIKE 'tenant_%'
        ORDER BY nspname
    LOOP
        -- Check if the categorization_rule table already exists
        SELECT EXISTS (
            SELECT 1 
            FROM information_schema.tables 
            WHERE table_schema = schema_name 
            AND table_name = 'categorization_rule'
        ) INTO table_exists;
        
        IF table_exists THEN
            RAISE NOTICE 'Table %.categorization_rule already exists (skipped)', schema_name;
            CONTINUE;
        END IF;
        
        -- Verify prerequisite tables exist
        SELECT EXISTS (
            SELECT 1 FROM information_schema.tables 
            WHERE table_schema = schema_name AND table_name = 'account'
        ) INTO account_table_exists;
        
        SELECT EXISTS (
            SELECT 1 FROM information_schema.tables 
            WHERE table_schema = schema_name AND table_name = 'category'
        ) INTO category_table_exists;
        
        IF NOT account_table_exists THEN
            RAISE WARNING 'Skipping %.categorization_rule - account table does not exist!', schema_name;
            CONTINUE;
        END IF;
        
        IF NOT category_table_exists THEN
            RAISE WARNING 'Skipping %.categorization_rule - category table does not exist!', schema_name;
            CONTINUE;
        END IF;
        
        -- Create the categorization_rule table
        EXECUTE format(
            'CREATE TABLE %I.categorization_rule (
                id BIGSERIAL PRIMARY KEY,
                account_id BIGINT NOT NULL REFERENCES %I.account(id) ON DELETE CASCADE,
                operation VARCHAR(10) NOT NULL CHECK (operation IN (''PLUS'', ''MINUS'')),
                category_id BIGINT NOT NULL REFERENCES %I.category(id) ON DELETE CASCADE,
                keywords TEXT NOT NULL,
                original_description TEXT,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP
            )',
            schema_name, schema_name, schema_name
        );
        
        -- Create index for account-based lookups
        EXECUTE format(
            'CREATE INDEX IF NOT EXISTS idx_categorization_rule_account 
                ON %I.categorization_rule(account_id)',
            schema_name
        );
        
        -- Create composite index for deduplication checks
        EXECUTE format(
            'CREATE INDEX IF NOT EXISTS idx_categorization_rule_account_operation_keywords 
                ON %I.categorization_rule(account_id, operation, keywords)',
            schema_name
        );
        
        -- Create index for category-based queries
        EXECUTE format(
            'CREATE INDEX IF NOT EXISTS idx_categorization_rule_category 
                ON %I.categorization_rule(category_id)',
            schema_name
        );
        
        RAISE NOTICE 'Created categorization_rule table and indexes in schema: %', schema_name;
    END LOOP;
    
    RAISE NOTICE '================================================';
    RAISE NOTICE 'Migration V3 complete!';
    RAISE NOTICE '================================================';
END $$;

-- ============================================
-- VERIFICATION QUERY (Run separately to verify)
-- ============================================
-- SELECT 
--     table_schema,
--     table_name,
--     (SELECT COUNT(*) FROM information_schema.columns 
--      WHERE information_schema.columns.table_schema = information_schema.tables.table_schema 
--      AND information_schema.columns.table_name = information_schema.tables.table_name) as column_count
-- FROM information_schema.tables 
-- WHERE table_name = 'categorization_rule'
-- ORDER BY table_schema;