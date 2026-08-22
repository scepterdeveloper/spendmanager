-- Migration to swap name and description columns in ACCOUNT_GROUP table
-- The columns were populated with swapped values:
-- - name column currently contains long descriptions
-- - description column currently contains short names/labels
-- This migration swaps them so:
-- - name column will contain short names/labels (e.g., "Liquidity")
-- - description column will contain long descriptions (e.g., "Liquid Cash Sources")

-- Use a temp column approach to avoid data loss during swap
-- Step 1: Add a temporary column
ALTER TABLE account_group ADD COLUMN temp_name VARCHAR(255);

-- Step 2: Copy description (short labels) to temp
UPDATE account_group SET temp_name = description;

-- Step 3: Copy name (long descriptions) to description
UPDATE account_group SET description = name;

-- Step 4: Copy temp (short labels) to name
UPDATE account_group SET name = temp_name;

-- Step 5: Drop temporary column
ALTER TABLE account_group DROP COLUMN temp_name;