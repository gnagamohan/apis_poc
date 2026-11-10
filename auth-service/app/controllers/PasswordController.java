package controllers;

import auth.PasswordService;
import com.fasterxml.jackson.databind.JsonNode;
import play.libs.Json;
import play.mvc.BodyParser;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;

import javax.inject.Inject;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

import static controllers.Api.done;
import static controllers.Api.problem;

/**
 * Forgot-password (POST /password-reset/request, /password-reset/confirm: public) and
 * change-password (POST /password/change: the gateway requires a JWT and passes its email as X-User-Email).
 */
public class PasswordController extends Controller {

    static final String USER_EMAIL_HEADER = "X-User-Email";
    private static final int MAX_TOKEN_LENGTH = 200;

    private final PasswordService passwords;

    @Inject
    public PasswordController(PasswordService passwords) {
        this.passwords = passwords;
    }

    /** {"email"} -> 202, always (doesn't reveal whether the account exists). Emails a token if it does. */
    @BodyParser.Of(BodyParser.TolerantText.class)
    public Result requestReset(Http.Request request) {
        String email;
        try {
            email = Api.email(Api.parseObject(request.body().asText(), "{\"email\": ...}"), "email");
        } catch (Api.InvalidRequest e) {
            return problem(BAD_REQUEST, "Bad Request", e.getMessage(), request);
        }
        passwords.requestReset(email);
        return status(ACCEPTED, Json.newObject().put("message",
                "If an account exists for this email, a password reset token has been sent to it."))
                .withHeader(CACHE_CONTROL, "no-store");
    }

    /** {"token", "newPassword"} -> 204; 400 if the token is invalid, expired or already used. */
    @BodyParser.Of(BodyParser.TolerantText.class)
    public CompletionStage<Result> confirmReset(Http.Request request) {
        String token;
        String newPassword;
        try {
            JsonNode body = Api.parseObject(request.body().asText(), "{\"token\": ..., \"newPassword\": ...}");
            token = Api.requiredString(body, "token").trim();
            if (token.isEmpty() || token.length() > MAX_TOKEN_LENGTH) {
                throw new Api.InvalidRequest("'token' must be the token from the reset email");
            }
            newPassword = Api.newPassword(body, "newPassword");
        } catch (Api.InvalidRequest e) {
            return done(problem(BAD_REQUEST, "Bad Request", e.getMessage(), request));
        }
        return passwords.confirmReset(token, newPassword)
                .thenApply(ok -> ok
                        ? noContent().withHeader(CACHE_CONTROL, "no-store")
                        : problem(BAD_REQUEST, "Bad Request", "Reset token is invalid, expired or already used", request))
                .exceptionally(t -> AuthController.unavailable(t, request));
    }

    /** Logged in. {"currentPassword", "newPassword"} -> 204. */
    @BodyParser.Of(BodyParser.TolerantText.class)
    public CompletionStage<Result> change(Http.Request request) {
        Optional<String> email = request.header(USER_EMAIL_HEADER).map(String::trim).filter(e -> !e.isEmpty());
        if (email.isEmpty()) {
            return done(problem(UNAUTHORIZED, "Unauthorized", "A valid Bearer token is required", request));
        }
        String currentPassword;
        String newPassword;
        try {
            JsonNode body = Api.parseObject(request.body().asText(),
                    "{\"currentPassword\": ..., \"newPassword\": ...}");
            currentPassword = Api.password(body, "currentPassword");
            newPassword = Api.newPassword(body, "newPassword");
        } catch (Api.InvalidRequest e) {
            return done(problem(BAD_REQUEST, "Bad Request", e.getMessage(), request));
        }
        return passwords.change(email.get(), currentPassword, newPassword)
                .thenApply(result -> switch (result) {
                    case CHANGED -> noContent().withHeader(CACHE_CONTROL, "no-store");
                    case WRONG_CURRENT_PASSWORD ->
                            problem(FORBIDDEN, "Forbidden", "Current password is incorrect", request);
                    case SAME_AS_CURRENT -> problem(BAD_REQUEST, "Bad Request",
                            "'newPassword' must be different from the current password", request);
                    case UNKNOWN_USER -> problem(UNAUTHORIZED, "Unauthorized", "Account not found or disabled", request);
                })
                .exceptionally(t -> AuthController.unavailable(t, request));
    }
}
