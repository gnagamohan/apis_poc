package controllers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.typesafe.config.Config;
import mcp.TodoTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import play.libs.Json;
import play.mvc.BodyParser;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;

import javax.inject.Inject;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * MCP server over the Streamable HTTP transport, tools only.
 * Every JSON-RPC request gets a single application/json response (no SSE), which the spec allows.
 * Stateless: no Mcp-Session-Id is issued.
 */
public class McpController extends Controller {

    private static final Logger log = LoggerFactory.getLogger(McpController.class);

    /** Newest first; the first entry is offered when the client asks for an unknown version. */
    static final List<String> SUPPORTED_PROTOCOL_VERSIONS = List.of("2025-11-25", "2025-06-18", "2025-03-26");

    // JSON-RPC error codes
    private static final int PARSE_ERROR = -32700;
    private static final int INVALID_REQUEST = -32600;
    private static final int METHOD_NOT_FOUND = -32601;
    private static final int INVALID_PARAMS = -32602;
    private static final int INTERNAL_ERROR = -32603;

    private final TodoTools tools;
    private final List<String> allowedOriginHosts;
    private final String serverName;
    private final String serverVersion;

    @Inject
    public McpController(TodoTools tools, Config config) {
        this.tools = tools;
        this.allowedOriginHosts = config.getStringList("mcp.allowedOriginHosts");
        this.serverName = config.getString("mcp.server.name");
        this.serverVersion = config.getString("mcp.server.version");
    }

    @BodyParser.Of(BodyParser.TolerantText.class)
    public CompletionStage<Result> post(Http.Request request) {
        if (!originAllowed(request)) {
            return done(status(FORBIDDEN, error(null, INVALID_REQUEST, "Origin not allowed")));
        }

        Optional<String> protocolHeader = request.header("MCP-Protocol-Version");
        if (protocolHeader.isPresent() && !SUPPORTED_PROTOCOL_VERSIONS.contains(protocolHeader.get())) {
            return done(badRequest(error(null, INVALID_REQUEST,
                    "Unsupported MCP-Protocol-Version: " + protocolHeader.get())));
        }

        JsonNode message;
        try {
            message = Json.parse(request.body().asText());
        } catch (RuntimeException e) {
            return done(badRequest(error(null, PARSE_ERROR, "Parse error: body is not valid JSON")));
        }

        if (message.isArray()) {
            return done(badRequest(error(null, INVALID_REQUEST, "JSON-RPC batching is not supported")));
        }
        if (!message.isObject() || !"2.0".equals(message.path("jsonrpc").asText())) {
            return done(badRequest(error(null, INVALID_REQUEST, "Expected a JSON-RPC 2.0 object")));
        }

        JsonNode id = message.get("id");
        boolean hasMethod = message.hasNonNull("method");

        // Notifications (no id) and client responses: acknowledge with 202 and no body.
        if (!hasMethod || id == null || id.isNull()) {
            if (hasMethod) {
                log.debug("Notification {}", message.get("method").asText());
            }
            return done(status(ACCEPTED));
        }

        String method = message.get("method").asText();
        JsonNode params = message.path("params");
        log.info("MCP request {} (id={})", method, id);

        CompletionStage<ObjectNode> response;
        try {
            response = switch (method) {
                case "initialize" -> done(result(id, initialize(params)));
                case "ping" -> done(result(id, Json.newObject()));
                case "tools/list" -> done(result(id, Json.newObject().set("tools", tools.definitions())));
                case "tools/call" -> callTool(id, params, request.header("Authorization").orElse(null));
                default -> done(error(id, METHOD_NOT_FOUND, "Method not found: " + method));
            };
        } catch (RuntimeException e) {
            log.error("Error handling {}", method, e);
            response = done(error(id, INTERNAL_ERROR, "Internal error"));
        }
        return response.thenApply(body -> ok(body).as("application/json"));
    }

    /** No server-initiated SSE stream and no sessions to terminate. */
    public Result methodNotAllowed() {
        return status(METHOD_NOT_ALLOWED).withHeader("Allow", "POST");
    }

    public Result health() {
        return ok(Json.newObject().put("status", "UP"));
    }

    // ------------------------------------------------------------------------------------------

    private ObjectNode initialize(JsonNode params) {
        String requested = params.path("protocolVersion").asText("");
        String version = SUPPORTED_PROTOCOL_VERSIONS.contains(requested)
                ? requested
                : SUPPORTED_PROTOCOL_VERSIONS.get(0);

        ObjectNode result = Json.newObject().put("protocolVersion", version);
        result.putObject("capabilities").putObject("tools").put("listChanged", false);
        result.putObject("serverInfo")
                .put("name", serverName)
                .put("title", "To-do MCP server")
                .put("version", serverVersion);
        result.put("instructions",
                "Manage a to-do list. Use list_todos to find ids, then get_todo, update_todo "
                        + "(partial updates, e.g. {id, completed:true}) or delete_todo. create_todo returns the new id.");
        return result;
    }

    private CompletionStage<ObjectNode> callTool(JsonNode id, JsonNode params, String authorization) {
        JsonNode name = params.get("name");
        if (name == null || !name.isTextual()) {
            return done(error(id, INVALID_PARAMS, "params.name (tool name) is required"));
        }
        try {
            return tools.call(name.asText(), params.get("arguments"), authorization).thenApply(r -> result(id, r));
        } catch (TodoTools.UnknownToolException e) {
            return done(error(id, INVALID_PARAMS, e.getMessage()));
        }
    }

    /** DNS-rebinding protection: browser requests must come from an allowed Origin. */
    private boolean originAllowed(Http.Request request) {
        Optional<String> origin = request.header("Origin");
        if (origin.isEmpty() || origin.get().isBlank() || "null".equals(origin.get())) {
            return origin.isEmpty() || origin.get().isBlank();
        }
        try {
            String host = URI.create(origin.get()).getHost();
            return host != null && allowedOriginHosts.contains(host.toLowerCase());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static ObjectNode result(JsonNode id, JsonNode result) {
        ObjectNode response = Json.newObject().put("jsonrpc", "2.0");
        response.set("id", id);
        response.set("result", result);
        return response;
    }

    private static ObjectNode error(JsonNode id, int code, String message) {
        ObjectNode response = Json.newObject().put("jsonrpc", "2.0");
        response.set("id", id == null ? Json.newObject().nullNode() : id);
        response.putObject("error").put("code", code).put("message", message);
        return response;
    }

    private static <T> CompletionStage<T> done(T value) {
        return CompletableFuture.completedFuture(value);
    }
}
