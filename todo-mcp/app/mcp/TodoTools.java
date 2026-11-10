package mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import play.libs.Json;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.function.BiFunction;

/** The five MCP tools, each backed by one call (two for update) to the to-do REST API. */
@Singleton
public class TodoTools {

    private static final Logger log = LoggerFactory.getLogger(TodoTools.class);

    /** Thrown for a tool name that does not exist (reported as a JSON-RPC protocol error). */
    public static class UnknownToolException extends RuntimeException {
        public UnknownToolException(String name) {
            super("Unknown tool: " + name);
        }
    }

    /** Bad tool arguments (reported as a tool execution error so the model can correct itself). */
    static class InvalidArgumentException extends RuntimeException {
        InvalidArgumentException(String message) {
            super(message);
        }
    }

    private final TodoApiClient api;

    @Inject
    public TodoTools(TodoApiClient api) {
        this.api = api;
    }

    // ---------------------------------------------------------------- definitions (tools/list)

    public ArrayNode definitions() {
        ArrayNode tools = Json.newArray();

        tools.add(tool("list_todos", "List todos",
                "List to-do items, ordered by id. Optionally filter by completion status.",
                schema().set("properties", Json.newObject()
                        .set("completed", prop("boolean",
                                "true = only completed, false = only open; omit to list all"))),
                true, false, true));

        tools.add(tool("get_todo", "Get todo",
                "Get a single to-do item by its id.",
                schema(idProp(), "id"),
                true, false, true));

        tools.add(tool("create_todo", "Create todo",
                "Create a new to-do item. Returns the created item including its id.",
                schema(Json.newObject()
                                .<ObjectNode>set("title", prop("string", "Short title (1-255 characters)"))
                                .<ObjectNode>set("description", prop("string", "Optional details (max 2000 characters)"))
                                .set("completed", prop("boolean", "Initial status; defaults to false")),
                        "title"),
                false, false, false));

        tools.add(tool("update_todo", "Update todo",
                "Update a to-do item. Only the fields you pass are changed; e.g. pass {id, completed:true} "
                        + "to mark it done.",
                schema(idProp()
                                .<ObjectNode>set("title", prop("string", "New title (1-255 characters)"))
                                .<ObjectNode>set("description", prop("string", "New description (max 2000 characters)"))
                                .set("completed", prop("boolean", "New completion status")),
                        "id"),
                false, false, true));

        tools.add(tool("delete_todo", "Delete todo",
                "Permanently delete a to-do item by its id.",
                schema(idProp(), "id"),
                false, true, true));

        return tools;
    }

    // ---------------------------------------------------------------- execution (tools/call)

    /**
     * Returns an MCP CallToolResult. Throws {@link UnknownToolException} for unknown tool names.
     * {@code authorization} is the caller's Authorization header, forwarded to the to-do API.
     */
    public CompletionStage<ObjectNode> call(String name, JsonNode arguments, String authorization) {
        JsonNode args = arguments == null || arguments.isNull() ? Json.newObject() : arguments;
        BiFunction<String, JsonNode, CompletionStage<ObjectNode>> handler = switch (name) {
            case "list_todos" -> this::listTodos;
            case "get_todo" -> this::getTodo;
            case "create_todo" -> this::createTodo;
            case "update_todo" -> this::updateTodo;
            case "delete_todo" -> this::deleteTodo;
            default -> throw new UnknownToolException(name);
        };

        CompletionStage<ObjectNode> result;
        try {
            if (!args.isObject()) {
                throw new InvalidArgumentException("arguments must be a JSON object");
            }
            result = handler.apply(authorization, args);
        } catch (InvalidArgumentException e) {
            return CompletableFuture.completedFuture(error(e.getMessage()));
        }
        return result.exceptionally(t -> {
            Throwable cause = t instanceof CompletionException && t.getCause() != null ? t.getCause() : t;
            log.warn("Tool {} failed calling the to-do API", name, cause);
            return error("To-do API is unavailable: " + cause.getMessage());
        });
    }

    private CompletionStage<ObjectNode> listTodos(String auth, JsonNode args) {
        Boolean completed = optBoolean(args, "completed");
        return api.list(auth, completed).thenApply(r -> {
            if (!r.ok()) return apiError(r, null);
            ObjectNode data = Json.newObject();
            data.put("count", r.body().size());
            data.set("todos", r.body());
            return success(data);
        });
    }

    private CompletionStage<ObjectNode> getTodo(String auth, JsonNode args) {
        long id = requireId(args);
        return api.get(auth, id).thenApply(r -> r.ok() ? success(r.body()) : apiError(r, id));
    }

    private CompletionStage<ObjectNode> createTodo(String auth, JsonNode args) {
        String title = optString(args, "title");
        if (title == null || title.isBlank()) {
            throw new InvalidArgumentException("'title' is required and must not be blank");
        }
        ObjectNode body = Json.newObject().put("title", title);
        String description = optString(args, "description");
        if (description != null) body.put("description", description);
        Boolean completed = optBoolean(args, "completed");
        if (completed != null) body.put("completed", completed);
        return api.create(auth, body).thenApply(r -> r.ok() ? success(r.body()) : apiError(r, null));
    }

    private CompletionStage<ObjectNode> updateTodo(String auth, JsonNode args) {
        long id = requireId(args);
        String title = optString(args, "title");
        String description = optString(args, "description");
        Boolean completed = optBoolean(args, "completed");
        if (title == null && description == null && completed == null) {
            throw new InvalidArgumentException("Pass at least one of 'title', 'description' or 'completed'");
        }
        if (title != null && title.isBlank()) {
            throw new InvalidArgumentException("'title' must not be blank");
        }
        // The REST API's PUT replaces the whole item, so read it first and merge.
        return api.get(auth, id).thenCompose(current -> {
            if (!current.ok()) {
                return CompletableFuture.completedFuture(apiError(current, id));
            }
            JsonNode c = current.body();
            ObjectNode body = Json.newObject();
            body.put("title", title != null ? title : c.path("title").asText());
            if (description != null) {
                body.put("description", description);
            } else if (c.hasNonNull("description")) {
                body.put("description", c.get("description").asText());
            }
            body.put("completed", completed != null ? completed : c.path("completed").asBoolean());
            return api.update(auth, id, body).thenApply(r -> r.ok() ? success(r.body()) : apiError(r, id));
        });
    }

    private CompletionStage<ObjectNode> deleteTodo(String auth, JsonNode args) {
        long id = requireId(args);
        return api.delete(auth, id).thenApply(r -> r.ok()
                ? success(Json.newObject().put("id", id).put("deleted", true))
                : apiError(r, id));
    }

    // ---------------------------------------------------------------- results

    private static ObjectNode success(JsonNode data) {
        ObjectNode result = Json.newObject();
        result.putArray("content").addObject().put("type", "text").put("text", data.toString());
        result.set("structuredContent", data);
        result.put("isError", false);
        return result;
    }

    private static ObjectNode error(String message) {
        ObjectNode result = Json.newObject();
        result.putArray("content").addObject().put("type", "text").put("text", message);
        result.put("isError", true);
        return result;
    }

    private static ObjectNode apiError(TodoApiClient.ApiResponse r, Long id) {
        String detail = r.detail();
        String message = switch (r.status()) {
            case 401 -> "Unauthorized: the access token is missing or expired. Log in again via /auth/login.";
            case 404 -> id != null ? "Todo " + id + " not found" : "Not found";
            case 400 -> "Invalid request" + (detail != null ? ": " + detail : "");
            default -> "To-do API returned HTTP " + r.status() + (detail != null ? ": " + detail : "");
        };
        return error(message);
    }

    // ---------------------------------------------------------------- argument helpers

    private static long requireId(JsonNode args) {
        JsonNode id = args.get("id");
        if (id == null || id.isNull()) {
            throw new InvalidArgumentException("'id' is required");
        }
        if (id.canConvertToLong() && (id.isIntegralNumber() || (id.isNumber() && id.asDouble() == id.asLong()))) {
            return id.asLong();
        }
        if (id.isTextual()) {
            try {
                return Long.parseLong(id.asText().trim());
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        throw new InvalidArgumentException("'id' must be an integer");
    }

    private static String optString(JsonNode args, String field) {
        JsonNode v = args.get(field);
        if (v == null || v.isNull()) return null;
        if (!v.isTextual()) throw new InvalidArgumentException("'" + field + "' must be a string");
        return v.asText();
    }

    private static Boolean optBoolean(JsonNode args, String field) {
        JsonNode v = args.get(field);
        if (v == null || v.isNull()) return null;
        if (v.isBoolean()) return v.asBoolean();
        if (v.isTextual() && ("true".equalsIgnoreCase(v.asText()) || "false".equalsIgnoreCase(v.asText()))) {
            return Boolean.parseBoolean(v.asText());
        }
        throw new InvalidArgumentException("'" + field + "' must be a boolean");
    }

    // ---------------------------------------------------------------- schema helpers

    private static ObjectNode tool(String name, String title, String description, ObjectNode inputSchema,
                                   boolean readOnly, boolean destructive, boolean idempotent) {
        ObjectNode tool = Json.newObject()
                .put("name", name)
                .put("title", title)
                .put("description", description);
        tool.set("inputSchema", inputSchema);
        ObjectNode annotations = tool.putObject("annotations");
        annotations.put("title", title);
        annotations.put("readOnlyHint", readOnly);
        if (!readOnly) {
            annotations.put("destructiveHint", destructive);
            annotations.put("idempotentHint", idempotent);
        }
        annotations.put("openWorldHint", false);
        return tool;
    }

    private static ObjectNode schema() {
        ObjectNode s = Json.newObject().put("type", "object");
        s.put("additionalProperties", false);
        return s;
    }

    private static ObjectNode schema(ObjectNode properties, String... required) {
        ObjectNode s = schema();
        s.set("properties", properties);
        ArrayNode req = s.putArray("required");
        for (String r : required) req.add(r);
        return s;
    }

    private static ObjectNode idProp() {
        return Json.newObject().set("id", prop("integer", "Id of the to-do item"));
    }

    private static ObjectNode prop(String type, String description) {
        return Json.newObject().put("type", type).put("description", description);
    }
}
