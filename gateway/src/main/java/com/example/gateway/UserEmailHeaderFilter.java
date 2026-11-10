package com.example.gateway;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Optional;

/**
 * Passes the authenticated user's email to downstream services as X-User-Email.
 * Any X-User-Email sent by the client is always removed first, so it can't be spoofed.
 */
@Component
public class UserEmailHeaderFilter implements GlobalFilter, Ordered {

    public static final String USER_EMAIL_HEADER = "X-User-Email";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .filter(JwtAuthenticationToken.class::isInstance)
                .map(auth -> Optional.ofNullable(((JwtAuthenticationToken) auth).getToken().getClaimAsString("email")))
                .defaultIfEmpty(Optional.empty())
                .flatMap(email -> {
                    ServerHttpRequest request = exchange.getRequest().mutate()
                            .headers(h -> {
                                h.remove(USER_EMAIL_HEADER);
                                email.ifPresent(e -> h.set(USER_EMAIL_HEADER, e));
                            })
                            .build();
                    return chain.filter(exchange.mutate().request(request).build());
                });
    }

    @Override
    public int getOrder() {
        return -1;  // before the routing filters
    }
}
