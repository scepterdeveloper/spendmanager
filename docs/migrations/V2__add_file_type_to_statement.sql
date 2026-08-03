-- Migration: Add file_type column to statement table for CSV support
-- Date: 2026-04-02
-- Description: Adds the file_type column to the statement table in all tenant schemas
--              to support CSV file uploads alongside PDF statements.
--
-- IMPORTANT: Run this on your PostgreSQL database (Supabase) BEFORE deploying the new code
--            to existing tenant schemas. New tenants will automatically get this column.
--
-- How to run:
--   Option 1: Supabase SQL Editor - paste this script and run
--   Option 2: psql -h db.gazvxpunuuhvenlaicem.supabase.co -U postgres -d postgres -f V2__add_file_type_to_statement.sql

-- First, add the column to the public schema (if not already added by Hibernate)
ALTER TABLE public.statement 
ADD COLUMN IF NOT EXISTS file_type VARCHAR(255) DEFAULT 'PDF';

-- Update any existing records to have the default value explicitly set
UPDATE public.statement 
SET file_type = 'PDF' 
WHERE file_type IS NULL;

-- Now add the column to all existing tenant schemas
DO $$
DECLARE
    schema_name TEXT;
    column_exists BOOLEAN;
BEGIN
    -- Loop through all tenant schemas (those starting with 'tenant_')
    FOR schema_name IN 
        SELECT nspname 
        FROM pg_namespace 
        WHERE nspname LIKE 'tenant_%'
    LOOP
        -- Check if the column doesn't already exist
        SELECT EXISTS (
            SELECT 1 
            FROM information_schema.columns 
            WHERE table_schema = schema_name 
            AND table_name = 'statement' 
            AND column_name = 'file_type'
        ) INTO column_exists;
        
        IF NOT column_exists THEN
            -- Add the file_type column with default value 'PDF'
            EXECUTE format(
                'ALTER TABLE %I.statement ADD COLUMN file_type VARCHAR(255) DEFAULT ''PDF''',
                schema_name
            );
            
            -- Set default value for any existing records
            EXECUTE format(
                'UPDATE %I.statement SET file_type = ''PDF'' WHERE file_type IS NULL',
                schema_name
            );
            
            RAISE NOTICE 'Added file_type column to %.statement', schema_name;
        ELSE
            RAISE NOTICE 'Column file_type already exists in %.statement (skipped)', schema_name;
        END IF;
    END LOOP;
    
    RAISE NOTICE 'Migration complete!';
END $$;