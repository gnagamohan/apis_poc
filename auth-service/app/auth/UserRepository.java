package auth;

import play.db.Database;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Optional;

/** Blocking JDBC access to authdb.users; call from {@link DatabaseExecutionContext}. */
@Singleton
public class UserRepository {

    private final Database db;

    @Inject
    public UserRepository(Database db) {
        this.db = db;
    }

    public Optional<User> findByEmail(String email) {
        return db.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id, email, password_hash, enabled FROM users WHERE lower(email) = lower(?)")) {
                ps.setString(1, email);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(new User(rs.getLong("id"), rs.getString("email"),
                            rs.getString("password_hash"), rs.getBoolean("enabled")));
                }
            }
        });
    }

    /** Inserts the user and returns it, or empty if the email (case-insensitive) is already registered. */
    public Optional<NewUser> create(String email, String passwordHash) {
        return db.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO users (email, password_hash) VALUES (?, ?) "
                            + "ON CONFLICT ((lower(email))) DO NOTHING "
                            + "RETURNING id, email, created_at")) {
                ps.setString(1, email);
                ps.setString(2, passwordHash);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(new NewUser(rs.getLong("id"), rs.getString("email"),
                            rs.getTimestamp("created_at").toInstant()));
                }
            }
        });
    }

    /** Inserts the user unless the email already exists. Returns true if a row was inserted. */
    public boolean insertIfAbsent(String email, String passwordHash) {
        return db.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO users (email, password_hash) VALUES (?, ?) "
                            + "ON CONFLICT ((lower(email))) DO NOTHING")) {
                ps.setString(1, email);
                ps.setString(2, passwordHash);
                return ps.executeUpdate() == 1;
            }
        });
    }
}
