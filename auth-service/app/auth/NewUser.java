package auth;

import java.time.Instant;

/** A freshly registered user (never includes the password hash). */
public record NewUser(long id, String email, Instant createdAt) {
}
