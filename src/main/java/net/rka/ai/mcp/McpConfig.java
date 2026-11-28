package net.rka.ai.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.function.Function;

/**
 * How the MCP server is set up: {@code MCP_ENABLED} ({@code true} turns it on; it is off otherwise), {@code MCP_PORT} (default
 * {@value #DEFAULT_PORT}) and the bearer token: {@code MCP_TOKEN}, else the one kept in the connection file ({@code ~/chat/mcp.json}),
 * else a new random one, which is written there. The file also says the URL, so a client is set up with what it holds.
 *
 * @param file where the URL and the token are kept for the person (and for the tools they set up)
 */
public record McpConfig(boolean enabled, int port, String token, Path file) {

    public static final int DEFAULT_PORT = 9391;
    private static final ObjectMapper JSON = new ObjectMapper().enable(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT);

    /** The URL clients connect to. */
    public String url() {
        return "http://127.0.0.1:" + port + "/mcp";
    }

    /**
     * @param file the connection file
     * @param env  reads a setting (the environment and {@code .env})
     * @return the configuration; a disabled one is returned without touching the file
     */
    public static McpConfig load(Path file, Function<String, String> env) {
        boolean enabled = "true".equalsIgnoreCase(String.valueOf(env.apply("MCP_ENABLED")).strip());
        if (!enabled) return new McpConfig(false, DEFAULT_PORT, "", file);
        int port = DEFAULT_PORT;
        String configuredPort = env.apply("MCP_PORT");
        if (configuredPort != null && !configuredPort.isBlank()) {
            try {
                port = Integer.parseInt(configuredPort.strip());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("MCP_PORT must be a port number, not " + configuredPort);
            }
            if (port < 0 || port > 65535) throw new IllegalArgumentException("MCP_PORT must be a port number, not " + configuredPort);
        }
        String token = env.apply("MCP_TOKEN");
        if (token == null || token.isBlank()) token = tokenOf(file);
        if (token == null) token = newToken();
        McpConfig config = new McpConfig(true, port, token.strip(), file);
        config.write();
        return config;
    }

    /** The server is started on port 0 in tests: this is the same configuration with the port that was really taken. */
    public McpConfig withPort(int actual) {
        McpConfig config = new McpConfig(enabled, actual, token, file);
        config.write();
        return config;
    }

    private void write() {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            ObjectNode node = JSON.createObjectNode();
            node.put("url", url());
            node.put("token", token);
            node.put("claudeCode", "claude mcp add --transport http lemonade-client " + url() + " --header \"Authorization: Bearer " + token + "\"");
            Files.writeString(file, JSON.writeValueAsString(node), StandardCharsets.UTF_8);
            try {
                Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------")); // only the person: the token lets anyone drive the app
            } catch (UnsupportedOperationException | IOException e) {
                // not a POSIX file system (Windows): the user's folder is already theirs alone
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write " + file, e);
        }
    }

    private static String tokenOf(Path file) {
        try {
            if (!Files.isRegularFile(file)) return null;
            JsonNode node = JSON.readTree(Files.readString(file, StandardCharsets.UTF_8));
            String token = node.path("token").asText("");
            return token.length() >= 16 ? token : null;
        } catch (IOException e) {
            return null; // unreadable: a new token
        }
    }

    private static String newToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
