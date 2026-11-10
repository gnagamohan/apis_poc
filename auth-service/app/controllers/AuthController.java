package controllers;

import auth.AuthService;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import play.libs.Json;
import play.mvc.BodyParser;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;

import javax.inject.Inject;
import java.util.concurrent.CompletionStage;

import static controllers.Api.done;
import static controllers.Api.problem;

public class AuthController extends Controller {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);
    private static final String CREDENTIALS = "{\"email\": ..., \"password\": ...}";

    private final AuthService authService;

    @Inject
    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /** POST /login {"email", "password"} -> 200 {"access_token", "token_type", "expires_in"} */
    @BodyParser.Of(BodyParser.TolerantText.class)
    public CompletionStage<Result> login(Http.Request request) {
        String email;
        String password;
        try {
            JsonNode body = Api.parseObject(request.body().asText(), CREDENTIALS);
            email = Api.email(body, "email");
            password = Api.password(body, "password");
        } catch (Api.InvalidRequest e) {
            return done(problem(BAD_REQUEST, "Bad Request", e.getMessage(), request));
        }
        return authService.login(email, password)
                .thenApply(token -> token
                        .map(t -> ok(Json.newObject()
                                .put("access_token", t.token())
                                .put("token_type", "Bearer")
                                .put("expires_in", t.expiresInSeconds()))
                                .withHeader(CACHE_CONTROL, "no-store"))
                        .orElseGet(() -> problem(UNAUTHORIZED, "Unauthorized", "Invalid email or password", request)))
                .exceptionally(t -> unavailable(t, request));
    }

    /** POST /signup {"email", "password"} -> 201 {"id", "email", "createdAt"}; 409 if the email is taken */
    @BodyParser.Of(BodyParser.TolerantText.class)
    public CompletionStage<Result> signup(Http.Request request) {
        String email;
        String password;
        try {
            JsonNode body = Api.parseObject(request.body().asText(), CREDENTIALS);
            email = Api.email(body, "email");
            password = Api.newPassword(body, "password");
        } catch (Api.InvalidRequest e) {
            return done(problem(BAD_REQUEST, "Bad Request", e.getMessage(), request));
        }
        return authService.signup(email, password)
                .thenApply(created -> created
                        .map(u -> status(CREATED, Json.newObject()
                                .put("id", u.id())
                                .put("email", u.email())
                                .put("createdAt", u.createdAt().toString()))
                                .withHeader(CACHE_CONTROL, "no-store"))
                        .orElseGet(() -> problem(CONFLICT, "Conflict", "An account with this email already exists", request)))
                .exceptionally(t -> unavailable(t, request));
    }

    public Result health() {
        return ok(Json.newObject().put("status", "UP"));
    }

    static Result unavailable(Throwable t, Http.Request request) {
        log.error("{} failed with an internal error", request.path(), t);
        return problem(SERVICE_UNAVAILABLE, "Service Unavailable", "Authentication is temporarily unavailable", request);
    }
}
