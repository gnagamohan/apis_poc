package auth;

import com.google.inject.AbstractModule;

public class AuthModule extends AbstractModule {
    @Override
    protected void configure() {
        // Validate the JWT config and create the seed user at startup, not on the first request.
        bind(JwtService.class).asEagerSingleton();
        bind(UserSeeder.class).asEagerSingleton();
    }
}
