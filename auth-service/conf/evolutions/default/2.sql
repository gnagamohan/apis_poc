# --- password reset tokens

# token_hash is the SHA-256 (hex) of the reset token; the token itself is never stored.
# (No inline SQL comments here: Play evolutions join each statement onto one line.)

# --- !Ups
ALTER TABLE users ADD COLUMN password_changed_at TIMESTAMPTZ;

CREATE TABLE password_reset_tokens (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id    BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash CHAR(64)    NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at    TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_password_reset_tokens_user ON password_reset_tokens (user_id);

# --- !Downs
DROP TABLE password_reset_tokens;
ALTER TABLE users DROP COLUMN password_changed_at;
