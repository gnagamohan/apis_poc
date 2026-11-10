package auth;

import com.typesafe.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Forgot-password (emailed one-time token) and change-password (logged in). */
@Singleton
public class PasswordService {

    public enum ChangeResult { CHANGED, WRONG_CURRENT_PASSWORD, SAME_AS_CURRENT, UNKNOWN_USER }

    private static final Logger log = LoggerFactory.getLogger(PasswordService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository users;
    private final PasswordRepository passwords;
    private final PasswordHasher hasher;
    private final Mailer mailer;
    private final DatabaseExecutionContext dbContext;
    private final Duration tokenTtl;
    private final String publicBaseUrl;

    @Inject
    public PasswordService(UserRepository users, PasswordRepository passwords, PasswordHasher hasher, Mailer mailer,
                           DatabaseExecutionContext dbContext, Config config) {
        this.users = users;
        this.passwords = passwords;
        this.hasher = hasher;
        this.mailer = mailer;
        this.dbContext = dbContext;
        this.tokenTtl = config.getDuration("passwordReset.ttl");
        this.publicBaseUrl = config.getString("passwordReset.publicBaseUrl");
    }

    /**
     * Starts a reset in the background and returns immediately, so the response (and its timing) is the same
     * whether or not the email belongs to an account.
     */
    public void requestReset(String email) {
        CompletableFuture.runAsync(() -> {
            Optional<User> user = users.findByEmail(email).filter(User::enabled);
            if (user.isEmpty()) {
                log.info("Password reset requested for an unknown or disabled account");
                return;
            }
            String token = newToken();
            Instant expiresAt = Instant.now().plus(tokenTtl);
            passwords.createResetToken(user.get().id(), sha256(token), expiresAt);
            sendQuietly(user.get().email(), "Reset your apis-poc password", """
                    Someone (hopefully you) asked to reset the password for this account.

                    Reset token: %s

                    It can be used once and expires in %d minutes (at %s).
                    To set a new password, call:

                      POST %s/auth/password-reset/confirm
                      Content-Type: application/json

                      {"token": "%s", "newPassword": "<your new password>"}

                    If you didn't ask for this, ignore this email; your password stays the same.
                    """.formatted(token, tokenTtl.toMinutes(), expiresAt, publicBaseUrl, token));
            log.info("Password reset token issued for user id {}", user.get().id());
        }, dbContext).exceptionally(t -> {
            log.error("Password reset request failed", t);
            return null;
        });
    }

    /** True if the token was valid (now used up) and the password was changed. */
    public CompletionStage<Boolean> confirmReset(String token, String newPassword) {
        return CompletableFuture.supplyAsync(() -> {
            String newHash = hasher.hash(newPassword);   // hashed first, so valid and invalid tokens take as long
            Optional<User> user = passwords.resetWithToken(sha256(token), newHash);
            user.ifPresent(u -> {
                log.info("Password reset completed for user id {}", u.id());
                notifyChanged(u.email());
            });
            return user.isPresent();
        }, dbContext);
    }

    public CompletionStage<ChangeResult> change(String email, String currentPassword, String newPassword) {
        return CompletableFuture.supplyAsync(() -> {
            Optional<User> found = users.findByEmail(email).filter(User::enabled);
            if (found.isEmpty()) {
                return ChangeResult.UNKNOWN_USER;
            }
            User user = found.get();
            if (!hasher.verify(currentPassword, user.passwordHash())) {
                return ChangeResult.WRONG_CURRENT_PASSWORD;
            }
            if (hasher.verify(newPassword, user.passwordHash())) {
                return ChangeResult.SAME_AS_CURRENT;
            }
            passwords.changePassword(user.id(), hasher.hash(newPassword));
            log.info("Password changed for user id {}", user.id());
            notifyChanged(user.email());
            return ChangeResult.CHANGED;
        }, dbContext);
    }

    // ------------------------------------------------------------------------------------------

    private void notifyChanged(String email) {
        sendQuietly(email, "Your apis-poc password was changed", """
                The password for this account was just changed.

                If this wasn't you, reset your password right away:
                  POST %s/auth/password-reset/request  {"email": "<your email>"}
                """.formatted(publicBaseUrl));
    }

    private void sendQuietly(String to, String subject, String text) {
        try {
            mailer.send(to, subject, text);
        } catch (Exception e) {
            log.error("Could not send '{}' email", subject, e);
        }
    }

    /** 32 random bytes, URL-safe Base64 (43 characters). */
    private static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
