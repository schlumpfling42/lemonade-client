package net.rka.ai.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.function.Function;

/**
 * One tool of the MCP server: a name, what it does, the JSON schema of its arguments and the code that runs it. The code gets the
 * arguments (an object, empty when there are none) and returns the text of the result; it signals a refusal or a mistake of the caller
 * by throwing an {@link IllegalArgumentException} whose message is sent back as the tool's error.
 *
 * @param schema the {@code inputSchema}: build it with {@link #arguments()}
 */
public record McpTool(String name, String description, ObjectNode schema, Function<JsonNode, String> run) {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** The schema of a tool without arguments. */
    public static ObjectNode noArguments() {
        return arguments().build();
    }

    /** A builder for the schema of the arguments: {@code arguments().string("id", "the conversation").required("id").build()}. */
    public static Schema arguments() {
        return new Schema();
    }

    /** Builds an {@code inputSchema} object. */
    public static final class Schema {
        private final ObjectNode properties = JSON.createObjectNode();
        private final com.fasterxml.jackson.databind.node.ArrayNode required = JSON.createArrayNode();

        public Schema string(String name, String description) {
            return property(name, "string", description);
        }

        public Schema integer(String name, String description) {
            return property(name, "integer", description);
        }

        public Schema bool(String name, String description) {
            return property(name, "boolean", description);
        }

        public Schema required(String... names) {
            for (String name : names) required.add(name);
            return this;
        }

        private Schema property(String name, String type, String description) {
            properties.putObject(name).put("type", type).put("description", description);
            return this;
        }

        public ObjectNode build() {
            ObjectNode schema = JSON.createObjectNode();
            schema.put("type", "object");
            schema.set("properties", properties);
            if (!required.isEmpty()) schema.set("required", required);
            return schema;
        }
    }

    /** A text argument, or null when it is missing or null. */
    public static String text(JsonNode arguments, String name) {
        JsonNode value = arguments.get(name);
        return value == null || value.isNull() ? null : value.asText();
    }

    /** A text argument that must be there and not blank. */
    public static String requiredText(JsonNode arguments, String name) {
        String value = text(arguments, name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("`" + name + "` is required.");
        return value;
    }

    /** A whole-number argument, or {@code fallback} when it is missing. */
    public static int integer(JsonNode arguments, String name, int fallback) {
        JsonNode value = arguments.get(name);
        return value == null || value.isNull() ? fallback : value.asInt(fallback);
    }

    /** A true/false argument, or null when it is missing. */
    public static Boolean bool(JsonNode arguments, String name) {
        JsonNode value = arguments.get(name);
        return value == null || value.isNull() ? null : value.asBoolean();
    }
}
