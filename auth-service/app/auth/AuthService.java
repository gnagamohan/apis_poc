package auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

@Singleton
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository users;
    private final PasswordHasher hasher;
    private final JwtService jwt;
    private final DatabaseExecutionContext dbContext;

    @Inject
    public AuthService(UserRepository users, PasswordHasher hasher, JwtService jwt,
                       DatabaseExecutionContext dbContext) {
        this.users = users;
        this.hasher = hasher;
        this.jwt = jwt;
        this.dbContext = dbContext;
    }

    /** Registers a user. Empty if the email is already taken. {@code email} must already be normalised. */
    public CompletionStage<Optional<NewUser>> signup(String email, String password) {
        return CompletableFuture.supplyAsync(() -> {
            Optional<NewUser> created = users.create(email, hasher.hash(password));
            if (created.isPresent()) {
                log.info("Signed up user id {}", created.get().id());
            } else {
                log.info("Sign-up rejected: email already registered");
            }
            return created;
        }, dbContext);
    }

    /** A token when the credentials are valid, otherwise empty. Unknown email and wrong password look the same. */
    public CompletionStage<Optional<JwtService.IssuedToken>> login(String email, String password) {
        return CompletableFuture.supplyAsync(() -> {
            Optional<User> user = users.findByEmail(email);
            boolean valid = user.isPresent()
                    ? hasher.verify(password, user.get().passwordHash()) && user.get().enabled()
                    : hasher.verifyAgainstDummy(password);
            if (!valid) {
                log.info("Login failed");
                return Optional.empty();
            }
            log.info("Login succeeded for user id {}", user.get().id());
            return Optional.of(jwt.issue(user.get()));
        }, dbContext);
    }
}
