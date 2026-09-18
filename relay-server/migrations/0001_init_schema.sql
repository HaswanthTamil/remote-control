-- Remote control relay - device keystore schema (SQLite / Turso).
-- The relay applies this automatically at startup (idempotent).
-- You can also run it manually against a Turso database:
--   turso db shell <db-name> < schema.sql
CREATE TABLE IF NOT EXISTS devices (
  device_id TEXT PRIMARY KEY,
  public_key TEXT NOT NULL,
  device TEXT NOT NULL,
  paired_at TEXT NOT NULL
);