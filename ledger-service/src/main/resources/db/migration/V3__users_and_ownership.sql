-- V3: users, and scoping data to the user who owns it.

CREATE TABLE users (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- Usernames are lowercase letters, digits, '.', '_' and '-' (checked in Java and here).
    username      VARCHAR(50)  NOT NULL UNIQUE,
    -- BCrypt hash, never the password itself. BCrypt embeds its own random salt.
    password_hash VARCHAR(100) NOT NULL,
    role          VARCHAR(20)  NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL,

    CONSTRAINT users_role_valid     CHECK (role IN ('CUSTOMER', 'ADMIN')),
    CONSTRAINT users_username_valid CHECK (username ~ '^[a-z0-9._-]{3,50}$')
);

-- accounts.owner_id holds the owner's username. The SYSTEM account was owned by
-- 'system', which is a valid username: anyone registering as "system" would
-- have owned the bank's funding account. '#' can never appear in a username,
-- so '#system' cannot be claimed by any user.
UPDATE accounts SET owner_id = '#system' WHERE type = 'SYSTEM';

-- Idempotency keys are now per user. Without this, two customers who happened
-- to pick the same key would collide, and one could even read the other's
-- stored response. The primary key becomes (client_id, idempotency_key).
ALTER TABLE idempotency_keys ADD COLUMN client_id VARCHAR(50);
UPDATE idempotency_keys SET client_id = '#legacy' WHERE client_id IS NULL;
ALTER TABLE idempotency_keys ALTER COLUMN client_id SET NOT NULL;
ALTER TABLE idempotency_keys DROP CONSTRAINT idempotency_keys_pkey;
ALTER TABLE idempotency_keys ADD PRIMARY KEY (client_id, idempotency_key);
