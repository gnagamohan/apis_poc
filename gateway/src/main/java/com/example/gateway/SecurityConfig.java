package com.example.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.savedrequest.NoOpServerRequestCache;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

/**
 * JWT is mandatory for the to-do REST API, the MCP endpoint and /auth/password/change;
 * the rest of /auth/** (login, sign-up, password reset) is public.
 * Tokens must be signed by auth-service (HS256), unexpired, and carry an "email" claim.
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        ServerAuthenticationEntryPoint unauthorized = SecurityConfig::writeUnauthorized;
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .requestCache(cache -> cache.requestCache(NoOpServerRequestCache.getInstance()))
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())  // stateless
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers("/auth/password/change").authenticated()   // before the public /auth/**
                        .pathMatchers("/auth/**").permitAll()
                        .pathMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .pathMatchers("/api/todos", "/api/todos/**", "/mcp").authenticated()
                        .anyExchange().denyAll())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(Customizer.withDefaults())
                        .authenticationEntryPoint(unauthorized))
                .exceptionHandling(e -> e.authenticationEntryPoint(unauthorized))
                .build();
    }

    @Bean
    ReactiveJwtDecoder jwtDecoder(@Value("${auth.jwt.secret}") String secret,
                                  @Value("${auth.jwt.issuer}") String issuer) {
        if (secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("auth.jwt.secret (JWT_SECRET) must be at least 32 bytes");
        }
        SecretKey key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withSecretKey(key)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                new JwtClaimValidator<Instant>("exp", exp -> exp != null),       // expiry is required...
                new JwtTimestampValidator(Duration.ZERO),                          // ...and enforced exactly
                new JwtIssuerValidator(issuer),
                new JwtClaimValidator<String>("email", email -> email != null && !email.isBlank())));
        return decoder;
    }

    /** 401 as an RFC 7807 problem body, plus the standard WWW-Authenticate header. */
    private static Mono<Void> writeUnauthorized(org.springframework.web.server.ServerWebExchange exchange,
                                                org.springframework.security.core.AuthenticationException ex) {
        String detail = ex instanceof OAuth2AuthenticationException oauth && oauth.getError().getDescription() != null
                ? oauth.getError().getDescription()
                : "A valid Bearer token is required";
        boolean tokenPresent = exchange.getRequest().getHeaders().containsHeader(HttpHeaders.AUTHORIZATION);

        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, tokenPresent
                ? "Bearer error=\"invalid_token\", error_description=\"" + detail.replace("\"", "'") + "\""
                : "Bearer");
        response.getHeaders().set(HttpHeaders.CONTENT_TYPE, "application/problem+json");
        response.getHeaders().set(HttpHeaders.CACHE_CONTROL, "no-store");

        String body = "{\"title\":\"Unauthorized\",\"status\":401,\"detail\":\"" + json(detail)
                + "\",\"instance\":\"" + json(exchange.getRequest().getPath().value()) + "\"}";
        DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    private static String json(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
