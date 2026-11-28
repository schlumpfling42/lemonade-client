package net.rka.ai.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The tools translate MCP calls to {@link AppControl}; the application is a recording fake here. */
class ControlToolsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Records every call as "method(arguments)" and answers with something small. */
    private static final class Recorder implements AppControl {
        final List<String> calls = new ArrayList<>();

        private JsonNode node(String what) {
            calls.add(what);
            return JSON.createObjectNode().put("did", what);
        }

        public JsonNode status() { return node("status"); }
        public JsonNode conversations() { return node("conversations"); }
        public JsonNode conversation(String id) { return node("conversation(" + id + ")"); }
        public String newConversation() { calls.add("newConversation"); return "id-1"; }
        public String openConversation(String id) { calls.add("open(" + id + ")"); return "opened"; }
        public String renameConversation(String id, String title) { calls.add("rename(" + id + "," + title + ")"); return "renamed"; }
        public String deleteConversation(String id) { calls.add("delete(" + id + ")"); return "deleted"; }
        public String send(String text) { calls.add("send(" + text + ")"); return "sent"; }
        public String stop() { calls.add("stop"); return "stopping"; }
        public JsonNode await(int seconds) { return node("await(" + seconds + ")"); }
        public JsonNode traffic(String conversation) { return node("traffic(" + conversation + ")"); }
        public JsonNode trafficCall(String conversation, int n) { return node("trafficCall(" + conversation + "," + n + ")"); }
        public JsonNode held() { return node("held"); }
        public String release(String editedText) { calls.add("release(" + editedText + ")"); return "released"; }
        public String cancelHeld() { calls.add("cancelHeld"); return "cancelled"; }
        public JsonNode holds(Boolean requests, Boolean answers) { return node("holds(" + requests + "," + answers + ")"); }
        public JsonNode approvals() { return node("approvals"); }
        public String answer(String id, JsonNode arguments) { calls.add("answer(" + id + "," + arguments + ")"); return "answered"; }
        public JsonNode settings() { return node("settings"); }
        public String changeSettings(String scope, String folder, List<String> trustedCommands) {
            calls.add("changeSettings(" + scope + "," + folder + "," + trustedCommands + ")");
            return "changed";
        }
        public JsonNode memory() { return node("memory"); }
        public String resetContext() { calls.add("resetContext"); return "reset"; }
        public JsonNode log(String kind, int lines) { return node("log(" + kind + "," + lines + ")"); }
        public JsonNode skills() { return node("skills"); }
    }

    private final Recorder app = new Recorder();
    private final Map<String, McpTool> tools = ControlTools.of(app).stream().collect(Collectors.toMap(McpTool::name, t -> t));

    private String call(String tool, String arguments) throws Exception {
        return tools.get(tool).run().apply(JSON.readTree(arguments));
    }

    @Test
    void everyAspectOfTheCommunicationHasATool() {
        assertEquals(java.util.Set.of(
                "app_status", "conversations_list", "conversation_get", "conversation_new", "conversation_open", "conversation_rename",
                "conversation_delete", "message_send", "turn_stop", "turn_wait", "traffic_list", "traffic_call", "traffic_held",
                "traffic_release", "traffic_cancel", "holds_set", "approvals_pending", "approval_answer", "settings_get", "settings_set",
                "memory_get", "context_reset", "log_tail", "skills_list"), tools.keySet());
        tools.values().forEach(tool -> {
            assertTrue(tool.description().length() > 20, tool.name());
            assertEquals("object", tool.schema().get("type").asText(), tool.name());
        });
    }

    @Test
    void theArgumentsReachTheApplication() throws Exception {
        call("message_send", "{\"text\":\"hello\"}");
        call("conversation_get", "{}");
        call("conversation_get", "{\"id\":\"abc\"}");
        call("traffic_call", "{\"n\":7,\"conversation\":\"abc\"}");
        call("traffic_release", "{\"text\":\"=== user ===\\nhi\"}");
        call("traffic_release", "{}");
        call("holds_set", "{\"requests\":true}");
        call("holds_set", "{}");
        call("log_tail", "{\"kind\":\"trace\",\"lines\":5}");

        assertEquals(List.of("send(hello)", "conversation(null)", "conversation(abc)", "trafficCall(abc,7)", "release(=== user ===\nhi)", "release(null)",
                "holds(true,null)", "holds(null,null)", "log(trace,5)"), app.calls);
    }

    @Test
    void waitsAndLogsAreKeptWithinTheirLimits() throws Exception {
        call("turn_wait", "{}");
        call("turn_wait", "{\"seconds\":9999}");
        call("turn_wait", "{\"seconds\":0}");
        call("log_tail", "{\"lines\":100000}");
        call("log_tail", "{}");

        assertEquals(List.of("await(60)", "await(600)", "await(1)", "log(null,400)", "log(null,80)"), app.calls);
    }

    @Test
    void anApprovalAnswerPassesAllItsArgumentsOn() throws Exception {
        call("approval_answer", "{\"id\":\"tool_call-3\",\"run\":true,\"always\":\"conversation\"}");

        assertEquals("answer(tool_call-3,{\"id\":\"tool_call-3\",\"run\":true,\"always\":\"conversation\"})", app.calls.getFirst());
    }

    @Test
    void settingsTakeTheCommandsAsAJsonArrayOrAnArray() throws Exception {
        call("settings_set", "{\"scope\":\"global\",\"trustedCommands\":\"[\\\"git status*\\\",\\\"ls\\\"]\"}");
        call("settings_set", "{\"scope\":\"conversation\",\"folder\":\"C:/work\",\"trustedCommands\":[\"dir\"]}");
        call("settings_set", "{\"scope\":\"global\"}");

        assertEquals(List.of("changeSettings(global,null,[git status*, ls])", "changeSettings(conversation,C:/work,[dir])", "changeSettings(global,null,null)"), app.calls);
        assertThrows(IllegalArgumentException.class, () -> call("settings_set", "{\"scope\":\"global\",\"trustedCommands\":\"not json\"}"));
    }

    @Test
    void requiredArgumentsAreRequiredAndTheMessageSaysWhich() {
        for (String[] bad : new String[][]{{"message_send", "{}"}, {"conversation_open", "{\"id\":\" \"}"}, {"conversation_rename", "{\"id\":\"a\"}"},
                {"approval_answer", "{}"}, {"settings_set", "{}"}}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> call(bad[0], bad[1]), bad[0]);
            assertTrue(e.getMessage().contains("is required"), bad[0] + ": " + e.getMessage());
        }
        assertTrue(app.calls.isEmpty(), "nothing reached the application: " + app.calls);
    }

    @Test
    void theSchemasNameTheirRequiredArguments() {
        ObjectNode schema = tools.get("conversation_rename").schema();
        assertEquals(List.of("id", "title"), List.of(schema.get("required").get(0).asText(), schema.get("required").get(1).asText()));
        assertEquals("integer", tools.get("traffic_call").schema().at("/properties/n/type").asText());
        assertEquals("boolean", tools.get("holds_set").schema().at("/properties/requests/type").asText());
    }
}
