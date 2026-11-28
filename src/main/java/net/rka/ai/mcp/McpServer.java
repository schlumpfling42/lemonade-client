package net.rka.ai.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;

/**
 * An MCP server (Model Context Protocol, the "streamable HTTP" transport in its plain form: one JSON-RPC message per POST, answered with
 * one JSON message) that lets a person's assistant, such as Claude Code, work the running application.
 *
 * <h2>Safety</h2>
 * The server can answer the application's approval dialogs, so it is as powerful as the person at the keyboard. It therefore
 * <ul>
 *   <li>listens on the loopback address only,</li>
 *   <li>answers only requests that carry {@code Authorization: Bearer <token>} (compared in constant time),</li>
 *   <li>refuses requests with an {@code Origin} header (a web page in a browser sends one; a tool does not) and requests whose {@code Host}
 *       is not the loopback address (DNS rebinding),</li>
 *   <li>is off unless the person turns it on ({@code MCP_ENABLED}).</li>
 * </ul>
 * It supports {@code initialize}, {@code ping}, {@code tools/list} and {@code tools/call}; notifications are accepted and ignored.
 */
public final class McpServer implements AutoCloseable {

    public static final String NAME = "lemonade-client";
    /** The protocol versions understood, newest first; the client's choice is echoed when it is among them. */
    static final List<String> VERSIONS = List.of("2025-06-18", "2025-03-26", "2024-11-05");
    private static final int MAX_BODY = 4 * 1024 * 1024;
    private static final Set<String> LOCAL_HOSTS = Set.of("127.0.0.1", "localhost", "[::1]");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpServer http;
    private final byte[] token;
    private final Map<String, McpTool> tools = new LinkedHashMap<>();
    private final String instructions;

    /**
     * @param port         the port on the loopback address; 0 picks a free one ({@link #port})
     * @param token        what clients must send as the bearer token
     * @param instructions what the server tells the client about itself and how to use the tools
     */
    public McpServer(int port, String token, List<McpTool> tools, String instructions) throws IOException {
        if (token == null || token.length() < 16) throw new IllegalArgumentException("The token must have at least 16 characters.");
        this.token = token.getBytes(StandardCharsets.UTF_8);
        this.instructions = instructions;
        for (McpTool tool : tools) {
            if (this.tools.put(tool.name(), tool) != null) throw new IllegalArgumentException("Two tools are called " + tool.name());
        }
        http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        http.createContext("/mcp", this::handle);
        // A tool may wait for a long time (the turn of the application), so every request gets its own thread
        http.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "mcp-request");
            t.setDaemon(true);
            return t;
        }));
    }

    public void start() {
        http.start();
    }

    public int port() {
        return http.getAddress().getPort();
    }

    /** The address the server listens on: always the loopback address. */
    public InetAddress address() {
        return http.getAddress().getAddress();
    }

    @Override
    public void close() {
        http.stop(0);
    }

    // ---- HTTP ----

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!"POST".equals(exchange.getRequestMethod())) {
                send(exchange, 405, "text/plain", "POST only");
                return;
            }
            if (exchange.getRequestHeaders().getFirst("Origin") != null || !localHost(exchange.getRequestHeaders().getFirst("Host"))) {
                send(exchange, 403, "text/plain", "Only local tools may use this server.");
                return;
            }
            if (!authorized(exchange.getRequestHeaders().getFirst("Authorization"))) {
                exchange.getResponseHeaders().add("WWW-Authenticate", "Bearer");
                send(exchange, 401, "text/plain", "A bearer token is required.");
                return;
            }
            byte[] body = exchange.getRequestBody().readNBytes(MAX_BODY + 1);
            if (body.length > MAX_BODY) {
                send(exchange, 413, "text/plain", "Too big.");
                return;
            }
            JsonNode message;
            try {
                message = JSON.readTree(body);
            } catch (IOException e) {
                send(exchange, 400, "application/json", JSON.writeValueAsString(error(null, -32700, "Parse error")));
                return;
            }
            JsonNode answer = message.isArray() ? answers((ArrayNode) message) : process(message);
            if (answer == null || (answer.isArray() && answer.isEmpty())) {
                send(exchange, 202, "text/plain", ""); // only notifications: nothing to answer
            } else {
                send(exchange, 200, "application/json", JSON.writeValueAsString(answer));
            }
        } catch (RuntimeException e) {
            // never let a bug of a tool take the server down
            byte[] text = ("error: " + e).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, text.length);
            exchange.getResponseBody().write(text);
        }
    }

    private static boolean localHost(String host) {
        if (host == null) return false;
        String name = host.startsWith("[") ? host.substring(0, host.indexOf(']') + 1) : host.contains(":") ? host.substring(0, host.indexOf(':')) : host;
        return LOCAL_HOSTS.contains(name.toLowerCase());
    }

    private boolean authorized(String header) {
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) return false;
        return MessageDigest.isEqual(header.substring(7).strip().getBytes(StandardCharsets.UTF_8), token);
    }

    private static void send(HttpExchange exchange, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", type + "; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) exchange.getResponseBody().write(bytes);
    }

    // ---- JSON-RPC ----

    private ArrayNode answers(ArrayNode batch) {
        ArrayNode out = JSON.createArrayNode();
        for (JsonNode message : batch) {
            JsonNode answer = process(message);
            if (answer != null) out.add(answer);
        }
        return out;
    }

    /** The answer to one message, or null for a notification (a message without an id). */
    ObjectNode process(JsonNode message) {
        JsonNode id = message.get("id");
        String method = message.path("method").asText("");
        if (id == null || id.isNull()) return null;
        if (method.isEmpty()) return error(id, -32600, "Invalid request");
        JsonNode params = message.has("params") ? message.get("params") : JSON.createObjectNode();
        try {
            return switch (method) {
                case "initialize" -> result(id, initialize(params));
                case "ping" -> result(id, JSON.createObjectNode());
                case "tools/list" -> result(id, list());
                case "tools/call" -> result(id, call(params));
                default -> error(id, -32601, "Method not found: " + method);
            };
        } catch (IllegalArgumentException e) {
            return error(id, -32602, e.getMessage());
        }
    }

    private ObjectNode initialize(JsonNode params) {
        String asked = params.path("protocolVersion").asText("");
        ObjectNode result = JSON.createObjectNode();
        result.put("protocolVersion", VERSIONS.contains(asked) ? asked : VERSIONS.getFirst());
        result.putObject("capabilities").putObject("tools").put("listChanged", false);
        result.putObject("serverInfo").put("name", NAME).put("version", "1.0.0");
        result.put("instructions", instructions);
        return result;
    }

    private ObjectNode list() {
        ObjectNode result = JSON.createObjectNode();
        ArrayNode array = result.putArray("tools");
        for (McpTool tool : tools.values()) {
            ObjectNode entry = array.addObject();
            entry.put("name", tool.name());
            entry.put("description", tool.description());
            entry.set("inputSchema", tool.schema());
        }
        return result;
    }

    private ObjectNode call(JsonNode params) {
        McpTool tool = tools.get(params.path("name").asText(""));
        if (tool == null) throw new IllegalArgumentException("Unknown tool: " + params.path("name").asText(""));
        JsonNode arguments = params.has("arguments") && params.get("arguments").isObject() ? params.get("arguments") : JSON.createObjectNode();
        ObjectNode result = JSON.createObjectNode();
        boolean failed = false;
        String text;
        try {
            text = tool.run().apply(arguments);
        } catch (IllegalArgumentException e) {
            failed = true;
            text = e.getMessage();
        } catch (RuntimeException e) {
            failed = true;
            text = "The tool failed: " + e;
        }
        result.putArray("content").addObject().put("type", "text").put("text", text == null ? "" : text);
        result.put("isError", failed);
        return result;
    }

    private static ObjectNode result(JsonNode id, JsonNode result) {
        ObjectNode out = JSON.createObjectNode();
        out.put("jsonrpc", "2.0");
        out.set("id", id);
        out.set("result", result);
        return out;
    }

    private static ObjectNode error(JsonNode id, int code, String message) {
        ObjectNode out = JSON.createObjectNode();
        out.put("jsonrpc", "2.0");
        if (id == null) out.putNull("id");
        else out.set("id", id);
        out.putObject("error").put("code", code).put("message", message);
        return out;
    }
}
