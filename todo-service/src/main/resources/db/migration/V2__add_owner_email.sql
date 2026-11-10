-- Each todo belongs to the user (email from the JWT, passed by the gateway as X-User-Email)
ALTER TABLE todos ADD COLUMN owner_email VARCHAR(320);

-- Rows created before authentication existed are given to the configured legacy owner
UPDATE todos SET owner_email = '${legacyOwnerEmail}' WHERE owner_email IS NULL;

ALTER TABLE todos ALTER COLUMN owner_email SET NOT NULL;

CREATE INDEX idx_todos_owner_id ON todos (owner_email, id);
