package auth;

public record User(long id, String email, String passwordHash, boolean enabled) {
}
