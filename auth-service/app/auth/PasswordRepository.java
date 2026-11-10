package auth;

import play.db.Database;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/** Password changes and reset tokens (blocking JDBC; call from {@link DatabaseExecutionContext}). */
@Singleton
public class PasswordRepository {

    private final Database db;

    @Inject
    public PasswordRepository(Database db) {
        this.db = db;
    }

    public void createResetToken(long userId, String tokenHash, Instant expiresAt) {
        db.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO password_reset_tokens (user_id, token_hash, expires_at) VALUES (?, ?, ?)")) {
                ps.setLong(1, userId);
                ps.setString(2, tokenHash);
                ps.setTimestamp(3, Timestamp.from(expiresAt));
                ps.executeUpdate();
            }
        });
    }

    /**
     * In one transaction: uses up the token (only if unused and unexpired), sets the new password and
     * invalidates the user's other outstanding tokens. Returns the user, or empty if the token isn't valid.
     */
    public Optional<User> resetWithToken(String tokenHash, String newPasswordHash) {
        return db.withTransaction(conn -> {
            Long userId;
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE password_reset_tokens SET used_at = now() "
                            + "WHERE token_hash = ? AND used_at IS NULL AND expires_at > now() RETURNING user_id")) {
                ps.setString(1, tokenHash);
                try (ResultSet rs = ps.executeQuery()) {
                    userId = rs.next() ? rs.getLong(1) : null;
                }
            }
            if (userId == null) {
                return Optional.empty();
            }
            Optional<User> user = setPassword(conn, userId, newPasswordHash);
            invalidateResetTokens(conn, userId);
            return user;
        });
    }

    /** Sets a new password and invalidates outstanding reset tokens. */
    public void changePassword(long userId, String newPasswordHash) {
        db.withTransaction(conn -> {
            setPassword(conn, userId, newPasswordHash);
            invalidateResetTokens(conn, userId);
        });
    }

    private static Optional<User> setPassword(Connection conn, long userId, String hash) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE users SET password_hash = ?, password_changed_at = now() WHERE id = ? AND enabled "
                        + "RETURNING id, email, password_hash, enabled")) {
            ps.setString(1, hash);
            ps.setLong(2, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next()
                        ? Optional.of(new User(rs.getLong("id"), rs.getString("email"),
                                rs.getString("password_hash"), rs.getBoolean("enabled")))
                        : Optional.empty();
            }
        }
    }

    private static void invalidateResetTokens(Connection conn, long userId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE password_reset_tokens SET used_at = now() WHERE user_id = ? AND used_at IS NULL")) {
            ps.setLong(1, userId);
            ps.executeUpdate();
        }
    }
}
