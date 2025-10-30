-- Add missing columns referenced by procedures but not present in schema
-- This migration adds columns that are expected by functions in R__procedures_and_views.sql

-- Add extension column to syntax table
ALTER TABLE syntax ADD COLUMN IF NOT EXISTS extension VARCHAR(8);

-- Add recycled_original_name column to benchmarks table
ALTER TABLE benchmarks ADD COLUMN IF NOT EXISTS recycled_original_name VARCHAR(256);