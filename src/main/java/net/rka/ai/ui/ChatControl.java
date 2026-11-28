package net.rka.ai.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import javafx.application.Platform;
import net.rka.ai.agent.skills.SkillFile;
import net.rka.ai.config.Env;
import net.rka.ai.config.Settings;
import net.rka.ai.conversations.ConversationStore;
import net.rka.ai.mcp.AppControl;
import net.rka.ai.mcp.Asks;
import net.rka.ai.tools.FactBook;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * The window as the MCP server sees it ({@link AppControl}). The window's state belongs to the FX thread, so every access to it goes
 * through {@link #fx}; what is only on disk (saved conversations, the logs) is read from the calling thread.
 */
final class ChatControl implements AppControl {

    private static final ObjectMapper JSON = new ObjectMapper();
    /** The most characters of one message that {@link #conversation} returns. */
    static final int MAX_TEXT = 8_000;

    private final ChatWindow window;

    ChatControl(ChatWindow window) {
        this.window = window;
    }

    // ---- the FX thread ----

    /** Runs {@code job} on the FX thread and returns what it returns; an {@link IllegalArgumentException} of the job reaches the caller as it is. */
    private <T> T fx(Supplier<T> job) {
        if (Platform.isFxApplicationThread()) return job.get();
        CompletableFuture<T> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                result.complete(job.get());
            } catch (Throwable e) {
                result.completeExceptionally(e);
            }
        });
        try {
            return result.get(30, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof IllegalArgumentException refused) throw refused;
            throw new IllegalStateException("The window failed: " + e.getCause(), e.getCause());
        } catch (TimeoutException e) {
            throw new IllegalStateException("The window did not answer within 30 seconds.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted.");
        }
    }

    // ---- the application and its conversations ----

    @Override
    public JsonNode status() {
        return fx(() -> {
            ObjectNode status = JSON.createObjectNode();
            status.put("model", window.agent().modelName());
            status.put("server", window.agent().baseUrl());
            status.put("turnRunning", window.isBusy());
            ObjectNode open = status.putObject("openConversation");
            open.put("id", window.openId());
            open.put("title", window.openTitle() == null ? "" : window.openTitle());
            open.put("messages", window.historyCopy().size());
            ArrayNode asks = status.putArray("waitingQuestions");
            for (Asks.Item item : window.asks().list()) asks.addObject().put("id", item.id()).put("kind", item.kind());
            WireView.Held held = window.traffic().held();
            if (held == null) status.putNull("heldCall");
            else status.putObject("heldCall").put("kind", held.kind()).put("n", held.n());
            ObjectNode holds = status.putObject("holds");
            holds.put("requests", window.traffic().holdsRequests());
            holds.put("answers", window.traffic().holdsAnswers());
            return status;
        });
    }

    @Override
    public JsonNode conversations() {
        ArrayNode array = JSON.createArrayNode();
        for (ConversationStore.Summary s : window.store().list()) {
            array.addObject().put("id", s.id()).put("title", s.title()).put("folder", s.folder()).put("updated", s.updated().toString());
        }
        return array;
    }

    @Override
    public JsonNode conversation(String id) {
        boolean open = id == null || id.isBlank() || fx(window::openId).equals(id);
        ObjectNode out = JSON.createObjectNode();
        if (open) {
            fx(() -> {
                out.put("id", window.openId());
                out.put("title", window.openTitle() == null ? "" : window.openTitle());
                out.put("folder", window.conversationSettings().workFolderOrDefault().toString());
                out.set("messages", messages(window.historyCopy()));
                out.set("memory", memoryOf(window.summary().text(), window.facts()));
                return null;
            });
        } else {
            ConversationStore.Conversation c = load(id);
            out.put("id", c.id());
            out.put("title", c.title());
            out.put("folder", c.settings().workFolderOrDefault().toString());
            out.set("messages", messages(c.messages()));
            out.set("memory", memoryOf(c.summary().text(), c.facts()));
        }
        return out;
    }

    private ConversationStore.Conversation load(String id) {
        try {
            return window.store().load(id);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("No conversation with the id " + id + ".");
        }
    }

    /** The messages as JSON: role, text (clipped at {@link #MAX_TEXT}), and for the model's messages its thinking and tool calls; for results the call they answer. */
    static ArrayNode messages(List<ChatMessage> messages) {
        ArrayNode array = JSON.createArrayNode();
        int n = 0;
        for (ChatMessage message : messages) {
            ObjectNode entry = array.addObject();
            entry.put("n", n++);
            if (message instanceof SystemMessage system) {
                entry.put("role", "system").put("text", clip(system.text()));
            } else if (message instanceof UserMessage user) {
                entry.put("role", "user").put("text", clip(user.hasSingleText() ? user.singleText() : user.toString()));
            } else if (message instanceof AiMessage ai) {
                entry.put("role", "assistant");
                entry.put("text", clip(ai.text() == null ? "" : ai.text()));
                if (ai.thinking() != null && !ai.thinking().isBlank()) entry.put("thinking", clip(ai.thinking()));
                if (ai.hasToolExecutionRequests()) {
                    ArrayNode calls = entry.putArray("toolCalls");
                    for (ToolExecutionRequest call : ai.toolExecutionRequests()) {
                        calls.addObject().put("id", call.id()).put("name", call.name()).put("arguments", clip(call.arguments()));
                    }
                }
            } else if (message instanceof ToolExecutionResultMessage result) {
                entry.put("role", "tool").put("callId", result.id()).put("tool", result.toolName())
                        .put("error", Boolean.TRUE.equals(result.isError())).put("text", clip(result.text()));
            } else {
                entry.put("role", message.type().name().toLowerCase()).put("text", clip(message.toString()));
            }
        }
        return array;
    }

    private static String clip(String text) {
        if (text == null) return "";
        return text.length() <= MAX_TEXT ? text : text.substring(0, MAX_TEXT) + " [… " + (text.length() - MAX_TEXT) + " more characters]";
    }

    @Override
    public String newConversation() {
        return fx(() -> {
            if (window.isBusy()) throw new IllegalArgumentException("A turn is running: stop it first (turn_stop).");
            window.startNewChat();
            return window.openId();
        });
    }

    @Override
    public String openConversation(String id) {
        load(id); // refuses an unknown id with a message
        return fx(() -> {
            if (window.isBusy()) throw new IllegalArgumentException("A turn is running: stop it first (turn_stop).");
            window.openById(id);
            return "Opened " + id + ": " + window.openTitle();
        });
    }

    @Override
    public String renameConversation(String id, String title) {
        load(id);
        return fx(() -> {
            window.renameById(id, title.strip());
            return "Renamed.";
        });
    }

    @Override
    public String deleteConversation(String id) {
        load(id);
        return fx(() -> {
            if (window.isBusy() && id.equals(window.openId())) throw new IllegalArgumentException("This conversation has a turn running: stop it first (turn_stop).");
            window.deleteById(id);
            return "Deleted " + id + ".";
        });
    }

    // ---- the turn ----

    @Override
    public String send(String text) {
        return fx(() -> {
            if (window.isBusy()) throw new IllegalArgumentException("A turn is running: wait for it (turn_wait) or stop it (turn_stop).");
            if (!window.asks().list().isEmpty()) throw new IllegalArgumentException("A question waits for an answer (approvals_pending).");
            window.sendText(text);
            if (!window.isBusy()) throw new IllegalArgumentException("The window did not start a turn.");
            return "Sent. The turn runs in the background: use turn_wait.";
        });
    }

    @Override
    public String stop() {
        return fx(() -> {
            if (!window.isBusy()) throw new IllegalArgumentException("No turn is running.");
            window.stopTurn();
            return "Stopping.";
        });
    }

    @Override
    public JsonNode await(int seconds) {
        long deadline = System.nanoTime() + seconds * 1_000_000_000L;
        String why;
        while (true) {
            ObjectNode status = (ObjectNode) status();
            if (!status.path("turnRunning").asBoolean()) {
                why = "no turn is running";
                break;
            }
            if (!status.path("waitingQuestions").isEmpty()) {
                why = "a question waits for an answer";
                break;
            }
            if (!status.path("heldCall").isNull()) {
                why = "a call of the model is held";
                break;
            }
            if (System.nanoTime() >= deadline) {
                why = "the time is up";
                break;
            }
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                why = "interrupted";
                break;
            }
        }
        ObjectNode status = (ObjectNode) status();
        status.put("waitedFor", why);
        return status;
    }

    // ---- the calls to the model ----

    @Override
    public JsonNode traffic(String conversation) {
        return fx(() -> {
            ArrayNode array = JSON.createArrayNode();
            for (var entry : window.traffic().entries(conversation)) array.addObject().put("n", entry.n()).put("line", entry.line());
            return array;
        });
    }

    @Override
    public JsonNode trafficCall(String conversation, int n) {
        return fx(() -> {
            for (var entry : window.traffic().entries(conversation)) {
                if (entry.n() != n) continue;
                ObjectNode call = JSON.createObjectNode();
                call.put("n", entry.n()).put("line", entry.line()).put("settings", entry.settings()).put("request", entry.request());
                call.put("tools", entry.tools() == null ? "" : entry.tools()).put("answer", entry.response());
                return call;
            }
            throw new IllegalArgumentException("No call number " + n + " in this conversation (traffic_list shows them).");
        });
    }

    @Override
    public JsonNode held() {
        return fx(() -> {
            ObjectNode out = JSON.createObjectNode();
            WireView.Held held = window.traffic().held();
            if (held == null) return out.put("kind", "none");
            return out.put("kind", held.kind()).put("n", held.n()).put("text", held.text());
        });
    }

    @Override
    public String release(String editedText) {
        return fx(() -> window.traffic().release(editedText));
    }

    @Override
    public String cancelHeld() {
        return fx(() -> window.traffic().cancelHeld());
    }

    @Override
    public JsonNode holds(Boolean requests, Boolean answers) {
        return fx(() -> {
            window.traffic().setHolds(requests, answers);
            ObjectNode out = JSON.createObjectNode();
            out.put("requests", window.traffic().holdsRequests()).put("answers", window.traffic().holdsAnswers());
            return out;
        });
    }

    // ---- the questions for the person ----

    @Override
    public JsonNode approvals() {
        ArrayNode array = JSON.createArrayNode();
        for (Asks.Item item : window.asks().list()) {
            ObjectNode entry = array.addObject();
            entry.put("id", item.id()).put("kind", item.kind());
            entry.set("details", item.details());
        }
        return array;
    }

    @Override
    public String answer(String id, JsonNode arguments) {
        return window.asks().answer(id, arguments);
    }

    // ---- settings, memory, logs, skills ----

    @Override
    public JsonNode settings() {
        return fx(() -> {
            ObjectNode out = JSON.createObjectNode();
            out.set("global", window.globalSettings().toJson());
            out.set("conversation", window.conversationSettings().toJson());
            return out;
        });
    }

    @Override
    public String changeSettings(String scope, String folder, List<String> trustedCommands) {
        if (!List.of("global", "conversation").contains(scope)) throw new IllegalArgumentException("`scope` is global or conversation.");
        Path path = null;
        if (folder != null && !folder.isBlank()) {
            path = Path.of(folder.strip());
            if (!Files.isDirectory(path)) throw new IllegalArgumentException("This folder does not exist: " + folder);
        }
        Path chosen = path;
        return fx(() -> {
            Settings current = scope.equals("global") ? window.globalSettings() : window.conversationSettings();
            Settings updated = current;
            if (chosen != null) updated = updated.withWorkFolder(chosen);
            if (trustedCommands != null) updated = updated.withTrustedCommands(trustedCommands);
            if (scope.equals("global")) window.changeGlobalSettings(updated);
            else window.setConversationSettings(updated);
            return "The " + scope + " settings are changed.";
        });
    }

    @Override
    public JsonNode memory() {
        return fx(() -> memoryOf(window.summary().text(), window.facts()));
    }

    private static ObjectNode memoryOf(String summary, List<FactBook.Fact> facts) {
        ObjectNode out = JSON.createObjectNode();
        out.put("summary", summary == null ? "" : summary);
        ArrayNode array = out.putArray("facts");
        for (FactBook.Fact fact : facts) array.addObject().put("id", fact.id()).put("text", fact.text());
        return out;
    }

    @Override
    public String resetContext() {
        return fx(() -> {
            window.resetContext();
            return "The context was rebuilt from the messages.";
        });
    }

    @Override
    public JsonNode log(String kind, int lines) {
        boolean trace = "trace".equals(kind);
        Path dir = Path.of(Env.get("AGENT_LOG_DIR", "traces"));
        Path file = dir.resolve((trace ? "traces-" : "agent-") + LocalDate.now() + (trace ? ".jsonl" : ".log"));
        ObjectNode out = JSON.createObjectNode();
        out.put("file", file.toAbsolutePath().toString());
        if (!Files.isRegularFile(file)) return out.put("text", "(no such file yet)");
        try {
            List<String> all = Files.readAllLines(file, StandardCharsets.UTF_8);
            String text = String.join("\n", all.subList(Math.max(0, all.size() - lines), all.size()));
            return out.put("text", text.length() > 60_000 ? text.substring(text.length() - 60_000) : text);
        } catch (IOException e) {
            throw new IllegalArgumentException("Could not read " + file + ": " + e.getMessage());
        }
    }

    @Override
    public JsonNode skills() {
        var loaded = window.agent().skillStatus();
        ObjectNode out = JSON.createObjectNode();
        ArrayNode skills = out.putArray("skills");
        for (SkillFile skill : loaded.skills()) {
            skills.addObject().put("name", skill.name()).put("tool", skill.toolName()).put("description", skill.description());
        }
        out.set("errors", JSON.valueToTree(loaded.errors()));
        return out;
    }
}
