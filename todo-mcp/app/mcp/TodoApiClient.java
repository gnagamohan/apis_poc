package mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.typesafe.config.Config;
import play.libs.Json;
import play.libs.ws.WSClient;
import play.libs.ws.WSRequest;
import play.libs.ws.WSResponse;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.concurrent.CompletionStage;

/** Thin HTTP client for the to-do REST API (reached through Spring Cloud Gateway). */
@Singleton
public class TodoApiClient {

    /** Status code plus parsed JSON body (NullNode when the body is empty or not JSON). */
    public record ApiResponse(int status, JsonNode body) {
        public boolean ok() {
            return status >= 200 && status < 300;
        }

        /** "detail" from an RFC 7807 problem response, if present. */
        public String detail() {
            return body != null && body.hasNonNull("detail") ? body.get("detail").asText() : null;
        }
    }

    private final WSClient ws;
    private final String baseUrl;

    @Inject
    public TodoApiClient(WSClient ws, Config config) {
        this.ws = ws;
        this.baseUrl = stripTrailingSlash(config.getString("todo.api.baseUrl"));
    }

    public CompletionStage<ApiResponse> list(String authorization, Boolean completed) {
        WSRequest request = json(ws.url(baseUrl), authorization);
        if (completed != null) {
            request = request.addQueryParameter("completed", completed.toString());
        }
        return request.get().thenApply(TodoApiClient::toApiResponse);
    }

    public CompletionStage<ApiResponse> get(String authorization, long id) {
        return json(ws.url(baseUrl + "/" + id), authorization).get().thenApply(TodoApiClient::toApiResponse);
    }

    public CompletionStage<ApiResponse> create(String authorization, JsonNode body) {
        return json(ws.url(baseUrl), authorization).post(body).thenApply(TodoApiClient::toApiResponse);
    }

    public CompletionStage<ApiResponse> update(String authorization, long id, JsonNode body) {
        return json(ws.url(baseUrl + "/" + id), authorization).put(body).thenApply(TodoApiClient::toApiResponse);
    }

    public CompletionStage<ApiResponse> delete(String authorization, long id) {
        return json(ws.url(baseUrl + "/" + id), authorization).delete().thenApply(TodoApiClient::toApiResponse);
    }

    /** Adds Accept and forwards the MCP caller's Authorization header (the gateway requires a JWT). */
    private static WSRequest json(WSRequest request, String authorization) {
        WSRequest r = request.addHeader("Accept", "application/json");
        return authorization == null || authorization.isBlank() ? r : r.addHeader("Authorization", authorization);
    }

    private static ApiResponse toApiResponse(WSResponse response) {
        String raw = response.getBody();
        JsonNode body = NullNode.getInstance();
        if (raw != null && !raw.isBlank()) {
            try {
                body = Json.parse(raw);
            } catch (RuntimeException notJson) {
                body = Json.newObject().put("detail", raw);
            }
        }
        return new ApiResponse(response.getStatus(), body);
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
