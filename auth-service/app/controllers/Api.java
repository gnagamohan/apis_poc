package controllers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import play.libs.Json;
import play.mvc.Http;
import play.mvc.Result;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.regex.Pattern;

import static play.mvc.Results.status;

/** Request parsing, validation and RFC 7807 error responses shared by the auth controllers. */
final class Api {

    static final int MAX_EMAIL_LENGTH = 320;
    static final int MAX_PASSWORD_BYTES = 72;   // BCrypt only uses the first 72 bytes
    static final int MIN_NEW_PASSWORD_LENGTH = 8;
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private Api() {
    }

    /** A request that fails validation; becomes a 400 problem response. */
    static final class InvalidRequest extends RuntimeException {
        InvalidRequest(String message) {
            super(message);
        }
    }

    static JsonNode parseObject(String raw, String expectedShape) {
        try {
            JsonNode body = Json.parse(raw);
            if (body.isObject()) {
                return body;
            }
        } catch (RuntimeException ignored) {
            // fall through
        }
        throw new InvalidRequest("Body must be JSON: " + expectedShape);
    }

    static String requiredString(JsonNode body, String field) {
        JsonNode v = body.get(field);
        if (v == null || !v.isTextual()) {
            throw new InvalidRequest("'" + field + "' is required and must be a string");
        }
        return v.asText();
    }

    /** Trimmed, lower-cased, format-checked email. */
    static String email(JsonNode body, String field) {
        String email = requiredString(body, field).trim().toLowerCase(Locale.ROOT);
        if (email.length() > MAX_EMAIL_LENGTH || !EMAIL.matcher(email).matches()) {
            throw new InvalidRequest("'" + field + "' must be a valid email address");
        }
        return email;
    }

    /** An existing password: 1-72 bytes. */
    static String password(JsonNode body, String field) {
        return password(body, field, 1);
    }

    /** A password being set: at least 8 characters, at most 72 bytes. */
    static String newPassword(JsonNode body, String field) {
        return password(body, field, MIN_NEW_PASSWORD_LENGTH);
    }

    private static String password(JsonNode body, String field, int minLength) {
        String password = requiredString(body, field);
        if (password.length() < minLength || password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            throw new InvalidRequest(minLength > 1
                    ? "'" + field + "' must be at least " + minLength + " characters and at most "
                            + MAX_PASSWORD_BYTES + " bytes"
                    : "'" + field + "' must be 1-" + MAX_PASSWORD_BYTES + " bytes");
        }
        return password;
    }

    /** RFC 7807 problem response, same shape as todo-service errors. */
    static Result problem(int status, String title, String detail, Http.Request request) {
        ObjectNode body = Json.newObject()
                .put("title", title)
                .put("status", status)
                .put("detail", detail)
                .put("instance", request.path());
        return status(status, body).as("application/problem+json").withHeader(Http.HeaderNames.CACHE_CONTROL, "no-store");
    }

    static <T> CompletionStage<T> done(T value) {
        return CompletableFuture.completedFuture(value);
    }
}
