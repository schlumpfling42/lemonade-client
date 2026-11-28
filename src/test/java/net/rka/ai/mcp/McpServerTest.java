package net.rka.ai.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The MCP server over real HTTP on the loopback address: the protocol, and who is let in. */
class McpServerTest {

    private static final String TOKEN = "a-test-token-of-enough-length";
    private static final ObjectMapper JSON = new ObjectMapper();

    private McpServer server;
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void start() throws Exception {
        McpTool echo = new McpTool("echo", "Says back what it is given.", McpTool.arguments().string("text", "what to say").required("text").build(),
                args -> "echo: " + McpTool.requiredText(args, "text"));
        McpTool crash = new McpTool("crash", "Always fails.", McpTool.noArguments(), args -> {
            throw new IllegalStateException("boom");
        });
        server = new McpServer(0, TOKEN, List.of(echo, crash), "Use echo.");
        server.start();
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private HttpResponse<String> post(String body, String authorization) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/mcp"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        if (authorization != null) request.header("Authorization", authorization);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode rpc(String body) throws Exception {
        HttpResponse<String> response = post(body, "Bearer " + TOKEN);
        assertEquals(200, response.statusCode(), response.body());
        return JSON.readTree(response.body());
    }

    @Test
    void initializeEchoesAKnownProtocolVersionAndNamesTheServerAndItsTools() throws Exception {
        JsonNode answer = rpc("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-03-26\"}}");

        assertEquals(1, answer.get("id").asInt());
        assertEquals("2025-03-26", answer.at("/result/protocolVersion").asText());
        assertEquals("lemonade-client", answer.at("/result/serverInfo/name").asText());
        assertTrue(answer.at("/result/capabilities/tools").isObject());
        assertEquals("Use echo.", answer.at("/result/instructions").asText());
        // an unknown version is answered with the newest one this server speaks
        assertEquals("2025-06-18", rpc("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"1999\"}}")
                .at("/result/protocolVersion").asText());
    }

    @Test
    void toolsAreListedWithTheirSchemasAndCalled() throws Exception {
        JsonNode list = rpc("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/list\"}");
        assertEquals("echo", list.at("/result/tools/0/name").asText());
        assertEquals("text", list.at("/result/tools/0/inputSchema/required/0").asText());

        JsonNode call = rpc("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{\"name\":\"echo\",\"arguments\":{\"text\":\"hi\"}}}");
        assertEquals("echo: hi", call.at("/result/content/0/text").asText());
        assertFalse(call.at("/result/isError").asBoolean());
    }

    @Test
    void aMistakeOfTheCallerAndAFailureOfAToolAreErrorResultsNotProtocolErrors() throws Exception {
        JsonNode missing = rpc("{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\",\"params\":{\"name\":\"echo\",\"arguments\":{}}}");
        assertTrue(missing.at("/result/isError").asBoolean());
        assertTrue(missing.at("/result/content/0/text").asText().contains("`text` is required"), missing.toString());

        JsonNode crashed = rpc("{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"tools/call\",\"params\":{\"name\":\"crash\"}}");
        assertTrue(crashed.at("/result/isError").asBoolean());
        assertTrue(crashed.at("/result/content/0/text").asText().contains("boom"));

        JsonNode unknown = rpc("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\",\"params\":{\"name\":\"nope\"}}");
        assertEquals(-32602, unknown.at("/error/code").asInt());
        assertEquals(-32601, rpc("{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"resources/list\"}").at("/error/code").asInt());
    }

    @Test
    void notificationsAreAcceptedWithoutAnAnswerAndABatchIsAnsweredInOrder() throws Exception {
        HttpResponse<String> notification = post("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", "Bearer " + TOKEN);
        assertEquals(202, notification.statusCode());
        assertEquals("", notification.body());

        JsonNode batch = rpc("[{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"},{\"jsonrpc\":\"2.0\",\"method\":\"notifications/x\"},"
                + "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}]");
        assertEquals(2, batch.size());
        assertEquals(1, batch.get(0).get("id").asInt());
        assertEquals(2, batch.get(1).get("id").asInt());
    }

    @Test
    void withoutTheTokenOrWithAWrongOneNothingIsAnswered() throws Exception {
        String ping = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}";
        assertEquals(401, post(ping, null).statusCode());
        assertEquals(401, post(ping, "Bearer wrong-token-of-enough-length").statusCode());
        assertEquals(401, post(ping, "Basic " + TOKEN).statusCode());
    }

    @Test
    void aBrowsersRequestWithAnOriginIsRefusedEvenWithTheToken() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/mcp"))
                .header("Authorization", "Bearer " + TOKEN).header("Origin", "http://evil.example")
                .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}")).build();

        assertEquals(403, client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    @Test
    void aRequestForAnotherHostNameIsRefusedAgainstDnsRebinding() throws Exception {
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), server.port())) {
            String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}";
            socket.getOutputStream().write(("POST /mcp HTTP/1.1\r\nHost: evil.example:" + server.port() + "\r\nAuthorization: Bearer " + TOKEN
                    + "\r\nContent-Type: application/json\r\nContent-Length: " + body.length() + "\r\nConnection: close\r\n\r\n" + body)
                    .getBytes(StandardCharsets.UTF_8));
            InputStream in = socket.getInputStream();
            String status = new String(in.readNBytes(12), StandardCharsets.UTF_8);
            assertTrue(status.startsWith("HTTP/1.1 403"), status);
        }
    }

    @Test
    void onlyPostIsAnsweredAndATooShortTokenIsNotAccepted() throws Exception {
        HttpRequest get = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/mcp")).GET().build();
        assertEquals(405, client.send(get, HttpResponse.BodyHandlers.ofString()).statusCode());
        assertThrows(IllegalArgumentException.class, () -> new McpServer(0, "short", List.of(), ""));
    }

    @Test
    void theServerListensOnTheLoopbackAddressOnly() {
        assertTrue(server.port() > 0);
        assertTrue(server.address().isLoopbackAddress(), server.address().toString());
    }
}
