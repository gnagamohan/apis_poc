package auth;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.typesafe.config.Config;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** Issues HS256-signed access tokens. */
@Singleton
public class JwtService {

    public record IssuedToken(String token, long expiresInSeconds, Instant expiresAt) {
    }

    private final Algorithm algorithm;
    private final String issuer;
    private final Duration ttl;
    private final Clock clock;

    @Inject
    public JwtService(Config config) {
        this(config.getString("jwt.secret"), config.getString("jwt.issuer"),
                config.getDuration("jwt.ttl"), Clock.systemUTC());
    }

    JwtService(String secret, String issuer, Duration ttl, Clock clock) {
        if (secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("jwt.secret (JWT_SECRET) must be at least 32 bytes for HS256");
        }
        this.algorithm = Algorithm.HMAC256(secret);
        this.issuer = issuer;
        this.ttl = ttl;
        this.clock = clock;
    }

    public IssuedToken issue(User user) {
        // JWT timestamps are whole seconds
        Instant now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        Instant expiresAt = now.plus(ttl);
        String token = JWT.create()
                .withIssuer(issuer)
                .withSubject(String.valueOf(user.id()))
                .withClaim("email", user.email())
                .withIssuedAt(now)
                .withExpiresAt(expiresAt)
                .withJWTId(UUID.randomUUID().toString())
                .sign(algorithm);
        return new IssuedToken(token, ttl.toSeconds(), expiresAt);
    }
}
