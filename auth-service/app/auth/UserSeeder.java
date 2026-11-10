package auth;

import com.typesafe.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import play.api.db.evolutions.ApplicationEvolutions;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Locale;

/** Creates the user from AUTH_SEED_USER_EMAIL / AUTH_SEED_USER_PASSWORD at startup if it doesn't exist. */
@Singleton
public class UserSeeder {

    private static final Logger log = LoggerFactory.getLogger(UserSeeder.class);

    @Inject
    public UserSeeder(ApplicationEvolutions evolutions,   // injected so the users table exists first
                      Config config, UserRepository users, PasswordHasher hasher) {
        String email = config.getString("auth.seedUser.email").trim().toLowerCase(Locale.ROOT);
        String password = config.getString("auth.seedUser.password");
        if (email.isEmpty() || password.isEmpty()) {
            log.info("No seed user configured");
            return;
        }
        boolean created = users.insertIfAbsent(email, hasher.hash(password));
        log.info(created ? "Seed user {} created" : "Seed user {} already exists (password unchanged)", email);
    }
}
