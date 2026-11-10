package auth;

import org.mindrot.jbcrypt.BCrypt;

import javax.inject.Singleton;

@Singleton
public class PasswordHasher {

    private static final int COST = 12;

    /** Hash of a random value, verified against when the email is unknown so timing doesn't reveal it. */
    private final String dummyHash = BCrypt.hashpw(java.util.UUID.randomUUID().toString(), BCrypt.gensalt(COST));

    public String hash(String password) {
        return BCrypt.hashpw(password, BCrypt.gensalt(COST));
    }

    public boolean verify(String password, String hash) {
        try {
            return BCrypt.checkpw(password, hash);
        } catch (IllegalArgumentException malformedHash) {
            return false;
        }
    }

    /** Spends the same time as a real check; always false. */
    public boolean verifyAgainstDummy(String password) {
        verify(password, dummyHash);
        return false;
    }
}
