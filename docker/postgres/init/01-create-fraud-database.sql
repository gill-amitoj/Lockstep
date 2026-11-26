-- Runs once, when the Postgres container first initialises its data volume.
-- Database-per-service: the fraud service gets its own database. It can never
-- read or write ledger tables, and the two schemas can change independently.
-- (POSTGRES_DB, the ledger's database, is created by the image itself.)
CREATE DATABASE lockstep_fraud;
