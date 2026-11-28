package net.rka.ai.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The server's setup, and the list of questions that wait. */
class McpConfigAndAsksTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path dir;

    @Test
    void theServerIsOffUnlessTheSettingSaysTrueAndAnOffServerTouchesNoFile() {
        Path file = dir.resolve("mcp.json");

        assertFalse(McpConfig.load(file, name -> null).enabled());
        assertFalse(McpConfig.load(file, Map.of("MCP_ENABLED", "false")::get).enabled());
        assertFalse(McpConfig.load(file, Map.of("MCP_ENABLED", "yes please")::get).enabled());
        assertFalse(Files.exists(file));
    }

    @Test
    void anEnabledServerGetsARandomTokenThatIsKeptAndTheUrlIsWrittenForTheClient() throws Exception {
        Path file = dir.resolve("chat").resolve("mcp.json");

        McpConfig first = McpConfig.load(file, Map.of("MCP_ENABLED", "true")::get);
        McpConfig second = McpConfig.load(file, Map.of("MCP_ENABLED", "true")::get);

        assertTrue(first.enabled());
        assertEquals(McpConfig.DEFAULT_PORT, first.port());
        assertTrue(first.token().length() >= 32, first.token());
        assertEquals(first.token(), second.token(), "the token is kept in the file, so a client set up once keeps working");
        JsonNode written = JSON.readTree(Files.readString(file));
        assertEquals("http://127.0.0.1:" + McpConfig.DEFAULT_PORT + "/mcp", written.get("url").asText());
        assertEquals(first.token(), written.get("token").asText());
        assertTrue(written.get("claudeCode").asText().contains("claude mcp add --transport http lemonade-client " + first.url()));
    }

    @Test
    void thePortAndTheTokenCanBeSetAndABadPortIsRefused() throws Exception {
        Path file = dir.resolve("mcp.json");

        McpConfig set = McpConfig.load(file, Map.of("MCP_ENABLED", "TRUE", "MCP_PORT", "9500", "MCP_TOKEN", "my-own-token-of-enough-length")::get);

        assertEquals(9500, set.port());
        assertEquals("my-own-token-of-enough-length", set.token());
        assertEquals("http://127.0.0.1:9500/mcp", JSON.readTree(Files.readString(file)).get("url").asText());
        assertThrows(IllegalArgumentException.class, () -> McpConfig.load(file, Map.of("MCP_ENABLED", "true", "MCP_PORT", "http")::get));
        assertThrows(IllegalArgumentException.class, () -> McpConfig.load(file, Map.of("MCP_ENABLED", "true", "MCP_PORT", "70000")::get));
    }

    @Test
    void aTooShortTokenInTheFileIsReplaced() throws Exception {
        Path file = dir.resolve("mcp.json");
        Files.writeString(file, "{\"token\":\"short\"}");

        McpConfig config = McpConfig.load(file, Map.of("MCP_ENABLED", "true")::get);

        assertNotEquals("short", config.token());
        assertTrue(config.token().length() >= 32);
    }

    @Test
    void waitingQuestionsAreListedInOrderAnsweredOnceAndGoWhenDone() {
        Asks asks = new Asks();
        List<String> answered = new java.util.ArrayList<>();
        String first = asks.register("tool_call", JSON.createObjectNode().put("tool", "runCommand"), args -> {
            answered.add(args.toString());
            return "ok";
        });
        String second = asks.register("plan", JSON.createObjectNode(), args -> "plan answered");

        assertEquals(List.of(first, second), asks.list().stream().map(Asks.Item::id).toList());
        assertEquals(2, asks.count());
        assertEquals("ok", asks.answer(first, JSON.createObjectNode().put("run", true)));
        assertEquals(List.of("{\"run\":true}"), answered);

        asks.done(first);
        IllegalArgumentException gone = assertThrows(IllegalArgumentException.class, () -> asks.answer(first, JSON.createObjectNode()));
        assertTrue(gone.getMessage().contains(second), "it says what is still waiting: " + gone.getMessage());
        assertEquals(1, asks.count());
    }

    @Test
    void aRefusedAnswerLeavesTheQuestionWaiting() {
        Asks asks = new Asks();
        String id = asks.register("question", JSON.createObjectNode(), args -> {
            throw new IllegalArgumentException("choose one of [a, b]");
        });

        assertThrows(IllegalArgumentException.class, () -> asks.answer(id, JSON.createObjectNode()));
        assertEquals(1, asks.count());
    }
}
