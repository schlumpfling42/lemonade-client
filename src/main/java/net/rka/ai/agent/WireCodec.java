package net.rka.ai.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A request to the model, and its answer, as text a person can read and change, and back.
 *
 * <pre>
 * === system ===
 * the system message
 *
 * === user ===
 * a message of the user
 *
 * === assistant ===
 * what the model said earlier
 *
 * === tool calls ===
 * [ {"id": "call_1", "name": "readFile", "arguments": {"path": "A.java"}} ]
 *
 * === tool result id=call_1 tool=readFile ===
 * the result
 * </pre>
 * Every block starts with a line "=== kind ===" and ends at the next one (one blank line between blocks belongs to the format, not to the text).
 * A line of a text that itself starts with "===" is written with a backslash in front, so a text can never be taken for a block start.
 * "tool calls" belongs to the assistant block before it. A response has the blocks "thinking", "text" and "tool calls".
 * What the text does not carry (the request's tool definitions, parameters, a message's images, a response's token counts) stays as it was:
 * a block that was not changed gives back the very message it came from.
 */
public final class WireCodec {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern HEADER = Pattern.compile("^=== (system|user|assistant|tool calls|tool result|thinking|text)( .*)? ===$");
    private static final Pattern TOOL_RESULT_ATTRIBUTES = Pattern.compile("id=(\\S*) tool=(\\S*)( error)?");

    private WireCodec() {
    }

    // ---- request ----

    /** The messages of the request as text. */
    public static String requestText(ChatRequest request) {
        StringBuilder out = new StringBuilder();
        for (ChatMessage message : request.messages()) {
            if (message instanceof SystemMessage system) {
                block(out, "system", system.text());
            } else if (message instanceof UserMessage user) {
                block(out, "user", textOf(user));
            } else if (message instanceof AiMessage ai) {
                if (ai.text() != null && !ai.text().isEmpty() || !ai.hasToolExecutionRequests()) block(out, "assistant", ai.text() == null ? "" : ai.text());
                if (ai.hasToolExecutionRequests()) block(out, "tool calls", callsText(ai.toolExecutionRequests()));
            } else if (message instanceof ToolExecutionResultMessage result) {
                block(out, "tool result id=" + result.id() + " tool=" + result.toolName()
                        + (Boolean.TRUE.equals(result.isError()) ? " error" : ""), result.text());
            } else {
                block(out, "user", String.valueOf(message));
            }
        }
        return out.toString();
    }

    /**
     * The request with the messages of {@code text}; the tool definitions and parameters are those of {@code original}.
     *
     * @throws IllegalArgumentException with a line number when the text is not in the format
     */
    public static ChatRequest parseRequest(String text, ChatRequest original) {
        List<ChatMessage> parsed = new ArrayList<>();
        for (Block block : blocks(text)) {
            switch (block.kind) {
                case "system" -> parsed.add(SystemMessage.from(block.text));
                case "user" -> parsed.add(UserMessage.from(block.text));
                case "assistant" -> {
                    if (!block.text.isEmpty()) parsed.add(AiMessage.from(block.text)); // an empty one is no message
                }
                case "tool calls" -> {
                    List<ToolExecutionRequest> calls = calls(block);
                    if (!parsed.isEmpty() && parsed.get(parsed.size() - 1) instanceof AiMessage last && !last.hasToolExecutionRequests()) {
                        parsed.set(parsed.size() - 1, AiMessage.builder().text(last.text()).toolExecutionRequests(calls).build());
                    } else {
                        parsed.add(AiMessage.builder().toolExecutionRequests(calls).build());
                    }
                }
                case "tool result" -> {
                    Matcher attributes = TOOL_RESULT_ATTRIBUTES.matcher(block.attributes.strip());
                    if (!attributes.matches()) throw problem(block, "a tool result needs: === tool result id=<id> tool=<name> ===");
                    parsed.add(ToolExecutionResultMessage.builder().id(attributes.group(1)).toolName(attributes.group(2))
                            .text(block.text).isError(attributes.group(3) != null ? Boolean.TRUE : null).build());
                }
                default -> throw problem(block, "a request has the blocks system, user, assistant, tool calls and tool result, not " + block.kind);
            }
        }
        if (parsed.isEmpty()) throw new IllegalArgumentException("The request has no message: write at least one block, such as \"=== user ===\".");
        // A message that was not changed stays the very message it was (an image of a user message is not in the text)
        List<ChatMessage> before = original.messages();
        for (int i = 0; i < parsed.size() && i < before.size(); i++) {
            if (sameAsText(parsed.get(i), before.get(i))) parsed.set(i, before.get(i));
        }
        return original.toBuilder().messages(parsed).build();
    }

    /** What the request carries besides the messages, to read: the model, the numbers and the tool names. */
    public static String settingsText(ChatRequest request) {
        var p = request.parameters();
        List<String> parts = new ArrayList<>();
        if (p.modelName() != null) parts.add("model " + p.modelName());
        if (p.temperature() != null) parts.add("temperature " + p.temperature());
        if (p.maxOutputTokens() != null) parts.add("at most " + p.maxOutputTokens() + " tokens to answer");
        if (p.toolChoice() != null) parts.add("tool choice " + p.toolChoice());
        List<ToolSpecification> tools = p.toolSpecifications();
        parts.add(tools == null || tools.isEmpty() ? "no tools" : tools.size() + " tools");
        return String.join(" · ", parts);
    }

    /** The tool definitions of the request (name and description of each) to read; they are sent as they are. */
    public static String toolsText(ChatRequest request) {
        List<ToolSpecification> tools = request.parameters().toolSpecifications();
        if (tools == null || tools.isEmpty()) return "(no tools in this request)";
        StringBuilder out = new StringBuilder();
        for (ToolSpecification tool : tools) {
            out.append(tool.name()).append(": ").append(tool.description() == null ? "" : tool.description().replaceAll("\\s+", " ")).append('\n');
        }
        return out.toString();
    }

    // ---- response ----

    /** The answer as text: its thinking (if any), its text and its tool calls. */
    public static String responseText(ChatResponse response) {
        AiMessage ai = response.aiMessage();
        StringBuilder out = new StringBuilder();
        if (ai.thinking() != null && !ai.thinking().isEmpty()) block(out, "thinking", ai.thinking());
        block(out, "text", ai.text() == null ? "" : ai.text());
        if (ai.hasToolExecutionRequests()) block(out, "tool calls", callsText(ai.toolExecutionRequests()));
        return out.toString();
    }

    /**
     * The answer with the blocks of {@code text}; token counts and the finish reason are those of {@code original}.
     *
     * @throws IllegalArgumentException with a line number when the text is not in the format
     */
    public static ChatResponse parseResponse(String text, ChatResponse original) {
        String thinking = null;
        String said = null;
        List<ToolExecutionRequest> calls = List.of();
        for (Block block : blocks(text)) {
            switch (block.kind) {
                case "thinking" -> thinking = block.text;
                case "text" -> said = block.text;
                case "tool calls" -> calls = calls(block);
                default -> throw problem(block, "an answer has the blocks thinking, text and tool calls, not " + block.kind);
            }
        }
        AiMessage.Builder ai = AiMessage.builder();
        ai.text(said == null || said.isEmpty() ? null : said);
        if (thinking != null && !thinking.isEmpty()) ai.thinking(thinking);
        if (!calls.isEmpty()) ai.toolExecutionRequests(calls);
        AiMessage result = ai.build();
        // Not changed: the very answer the server gave
        AiMessage first = original.aiMessage();
        if (Objects.equals(first.text(), result.text()) && Objects.equals(first.thinking(), result.thinking())
                && first.toolExecutionRequests().equals(result.toolExecutionRequests())) {
            return original;
        }
        return original.toBuilder().aiMessage(result).build();
    }

    // ---- the format ----

    private record Block(String kind, String attributes, String text, int line) {
    }

    private static void block(StringBuilder out, String kind, String text) {
        out.append("=== ").append(kind).append(" ===\n");
        for (String line : text.split("\n", -1)) {
            out.append(line.startsWith("===") || line.startsWith("\\===") ? "\\" : "").append(line).append('\n');
        }
        out.append('\n');
    }

    private static List<Block> blocks(String text) {
        List<Block> out = new ArrayList<>();
        String kind = null;
        String attributes = "";
        int start = 0;
        List<String> lines = new ArrayList<>();
        String[] split = text.replace("\r\n", "\n").split("\n", -1);
        // The text ends with a newline: the empty piece after it is not a line
        String[] all = split.length > 1 && split[split.length - 1].isEmpty() ? java.util.Arrays.copyOf(split, split.length - 1) : split;
        for (int i = 0; i < all.length; i++) {
            Matcher header = HEADER.matcher(all[i]);
            if (header.matches()) {
                if (kind != null) out.add(finish(kind, attributes, lines, start));
                kind = header.group(1);
                attributes = header.group(2) == null ? "" : header.group(2);
                start = i + 1;
                lines = new ArrayList<>();
            } else if (kind != null) {
                // The inverse of the escape: a line that starts with "===" or "\===" was written with a backslash in front
                lines.add(all[i].startsWith("\\===") || all[i].startsWith("\\\\===") ? all[i].substring(1) : all[i]);
            } else if (!all[i].isBlank()) {
                throw new IllegalArgumentException("Line " + (i + 1) + ": text before the first block. Start with a line such as \"=== user ===\".");
            }
        }
        if (kind != null) out.add(finish(kind, attributes, lines, start));
        return out;
    }

    private static Block finish(String kind, String attributes, List<String> lines, int line) {
        List<String> kept = new ArrayList<>(lines);
        // The blank line that separates the blocks belongs to the format
        if (!kept.isEmpty() && kept.get(kept.size() - 1).isEmpty()) kept.remove(kept.size() - 1);
        return new Block(kind, attributes, String.join("\n", kept), line);
    }

    private static IllegalArgumentException problem(Block block, String what) {
        return new IllegalArgumentException("Block \"" + block.kind + "\" (line " + block.line + "): " + what);
    }

    private static String callsText(List<ToolExecutionRequest> calls) {
        ArrayNode array = JSON.createArrayNode();
        for (ToolExecutionRequest call : calls) {
            ObjectNode node = array.addObject();
            node.put("id", call.id() == null ? "" : call.id());
            node.put("name", call.name());
            String arguments = call.arguments() == null ? "" : call.arguments();
            try {
                node.set("arguments", JSON.readTree(arguments.isBlank() ? "{}" : arguments)); // readable: the arguments as JSON, not as a string
            } catch (JsonProcessingException e) {
                node.put("arguments", arguments);
            }
        }
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(array);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<ToolExecutionRequest> calls(Block block) {
        try {
            JsonNode array = JSON.readTree(block.text);
            if (array == null || !array.isArray()) throw problem(block, "the tool calls are a JSON array: [ {\"id\": ..., \"name\": ..., \"arguments\": {...}} ]");
            List<ToolExecutionRequest> calls = new ArrayList<>();
            for (JsonNode node : array) {
                if (node.path("name").asText("").isBlank()) throw problem(block, "every tool call needs a \"name\".");
                JsonNode arguments = node.path("arguments");
                calls.add(ToolExecutionRequest.builder().id(node.path("id").asText(""))
                        .name(node.path("name").asText())
                        .arguments(arguments.isMissingNode() ? "{}" : arguments.isTextual() ? arguments.asText() : arguments.toString()).build());
            }
            return calls;
        } catch (JsonProcessingException e) {
            throw problem(block, "the tool calls are not valid JSON: " + e.getOriginalMessage());
        }
    }

    private static String textOf(UserMessage user) {
        if (user.hasSingleText()) return user.singleText();
        StringBuilder out = new StringBuilder();
        for (Content content : user.contents()) {
            if (content instanceof TextContent text) out.append(out.isEmpty() ? "" : "\n").append(text.text());
        }
        return out.toString();
    }

    /** Whether the parsed message says the same as the original one (so the original, with whatever the text cannot show, is kept). */
    private static boolean sameAsText(ChatMessage parsed, ChatMessage original) {
        if (parsed instanceof SystemMessage a && original instanceof SystemMessage b) return a.text().equals(b.text());
        if (parsed instanceof UserMessage a && original instanceof UserMessage b) return textOf(a).equals(textOf(b));
        if (parsed instanceof AiMessage a && original instanceof AiMessage b) {
            return Objects.equals(a.text() == null ? "" : a.text(), b.text() == null ? "" : b.text())
                    && a.toolExecutionRequests().equals(b.toolExecutionRequests());
        }
        if (parsed instanceof ToolExecutionResultMessage a && original instanceof ToolExecutionResultMessage b) {
            return a.id().equals(b.id()) && a.toolName().equals(b.toolName()) && a.text().equals(b.text())
                    && Boolean.TRUE.equals(a.isError()) == Boolean.TRUE.equals(b.isError());
        }
        return false;
    }
}
