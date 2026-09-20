-- Baseline migration. Schema objects arrive in V002 onwards; this file exists so that a
-- brand-new database and an already-migrated one converge on the same extension set.
create extension if not exists pgcrypto;
