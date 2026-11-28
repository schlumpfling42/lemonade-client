package net.rka.ai.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

import static net.rka.ai.mcp.McpTool.bool;
import static net.rka.ai.mcp.McpTool.integer;
import static net.rka.ai.mcp.McpTool.noArguments;
import static net.rka.ai.mcp.McpTool.requiredText;
import static net.rka.ai.mcp.McpTool.arguments;
import static net.rka.ai.mcp.McpTool.text;

/**
 * The tools of the MCP server, one per thing a person can do in the application (and a few they cannot: read the logs, answer a question
 * from outside). They only translate between MCP and {@link AppControl}.
 */
public final class ControlTools {

    private static final ObjectMapper JSON = new ObjectMapper().enable(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT);

    /** What the server tells a client about itself. */
    public static final String INSTRUCTIONS = """
            This server controls a running lemonade-client (a chat application for a local language model). With it you can do what the person at the \
            keyboard can: write to the model, watch and change every call to the model, and answer the application's questions.
            A turn: message_send starts it (it runs in the background); turn_wait waits until it is over or something needs you; turn_stop stops it.
            The calls to the model: traffic_list / traffic_call show them. With holds on (holds_set) every request (or answer) waits until you \
            traffic_held to read it, then traffic_release (optionally with your edited text) or traffic_cancel. The text format is the one of the \
            traffic sidebar: blocks headed "=== user ===" and so on.
            Questions for the person (approving a command, a plan, a question of a skill) show in approvals_pending; approval_answer answers one. \
            A command you approve really runs on the person's machine: read it first.""";

    private ControlTools() {
    }

    public static List<McpTool> of(AppControl app) {
        List<McpTool> tools = new ArrayList<>();
        // the application and its conversations
        tools.add(tool("app_status", "The model, whether a turn runs, the open conversation, how many questions wait, the hold boxes.", noArguments(),
                args -> json(app.status())));
        tools.add(tool("conversations_list", "The saved conversations, newest first (id, title, folder).", noArguments(), args -> json(app.conversations())));
        tools.add(tool("conversation_get", "A conversation with all its messages (the open one without an id), its summary and facts.",
                arguments().string("id", "the conversation's id; leave out for the open one").build(), args -> json(app.conversation(text(args, "id")))));
        tools.add(tool("conversation_new", "Starts a new conversation and opens it. Returns its id.", noArguments(), args -> app.newConversation()));
        tools.add(tool("conversation_open", "Opens a saved conversation in the window (not while a turn runs).",
                arguments().string("id", "the conversation's id").required("id").build(), args -> app.openConversation(requiredText(args, "id"))));
        tools.add(tool("conversation_rename", "Renames a saved conversation.",
                arguments().string("id", "the conversation's id").string("title", "the new title").required("id", "title").build(),
                args -> app.renameConversation(requiredText(args, "id"), requiredText(args, "title"))));
        tools.add(tool("conversation_delete", "Deletes a saved conversation and its traffic for good, without asking the person.",
                arguments().string("id", "the conversation's id").required("id").build(), args -> app.deleteConversation(requiredText(args, "id"))));
        // the turn
        tools.add(tool("message_send", "Writes a message in the open conversation as the person would and sends it. The turn runs in the background: "
                + "use turn_wait. Refused while a turn runs.", arguments().string("text", "the message").required("text").build(),
                args -> app.send(requiredText(args, "text"))));
        tools.add(tool("turn_stop", "Stops the turn in progress, like the Stop button.", noArguments(), args -> app.stop()));
        tools.add(tool("turn_wait", "Waits until the turn is over, a question waits for an answer or a call of the model is held, for at most `seconds` "
                + "(default 60, at most 600). Says in `waitedFor` which of these ended the wait.",
                arguments().integer("seconds", "the longest wait, default 60").build(), args -> json(app.await(Math.max(1, Math.min(600, integer(args, "seconds", 60)))))));
        // the calls to the model
        tools.add(tool("traffic_list", "The calls to the model of a conversation (the open one without an id): number and a line each.",
                arguments().string("conversation", "the conversation's id; leave out for the open one").build(), args -> json(app.traffic(text(args, "conversation")))));
        tools.add(tool("traffic_call", "One call to the model: its settings, the whole request, its tools and the whole answer, as text.",
                arguments().integer("n", "the call's number").string("conversation", "the conversation's id; leave out for the open one").required("n").build(),
                args -> json(app.trafficCall(text(args, "conversation"), integer(args, "n", -1)))));
        tools.add(tool("traffic_held", "The call that is held, if one is: whether it is a request or an answer, and its text. "
                + "Hold boxes decide which calls wait (holds_set).", noArguments(), args -> json(app.held())));
        tools.add(tool("traffic_release", "Lets the held call go on: the request is sent, or the answer is used. With `text` it is your edited version "
                + "(same format as traffic_held shows; a text that does not parse is refused with the reason and the call stays held).",
                arguments().string("text", "the edited request or answer; leave out to go on with the original").build(), args -> app.release(text(args, "text"))));
        tools.add(tool("traffic_cancel", "Cancels the held call: it fails like a call that the server refused.", noArguments(), args -> app.cancelHeld()));
        tools.add(tool("holds_set", "Sets which calls wait for you: every request and/or every answer. Without arguments it only reports.",
                arguments().bool("requests", "hold every request").bool("answers", "hold every answer").build(),
                args -> json(app.holds(bool(args, "requests"), bool(args, "answers")))));
        // the questions for the person
        tools.add(tool("approvals_pending", "The questions that wait for an answer: approval of a command or tool call, of a plan, a question of a skill, "
                + "the review of a drafted skill. Each has an id, a kind and its details.", noArguments(), args -> json(app.approvals())));
        tools.add(tool("approval_answer", "Answers a waiting question instead of the person. By kind: "
                + "tool_call: run (true/false), optionally always (\"conversation\" or \"everywhere\") and entry (what to trust; default the command itself). "
                + "plan: decision (approve/revise/deny) and comment (for revise). "
                + "question: choice (one of the choices) and comment, or dismiss (true). "
                + "skill_review: decision (save/revise/cancel) and comment. A command you approve really runs on the person's machine.",
                arguments().string("id", "the question's id from approvals_pending").bool("run", "tool_call: run it").string("always", "tool_call: trust it in the conversation or everywhere")
                        .string("entry", "tool_call: what to trust").string("decision", "plan or skill_review: the decision").string("comment", "plan, question or skill_review: the comment")
                        .string("choice", "question: the choice").bool("dismiss", "question: dismiss it").required("id").build(),
                args -> app.answer(requiredText(args, "id"), args)));
        // settings, memory, logs, skills
        tools.add(tool("settings_get", "The trusted folder and trusted commands, global and of the open conversation.", noArguments(), args -> json(app.settings())));
        tools.add(tool("settings_set", "Changes the trusted folder and/or the trusted commands (the whole list) of the global settings or of the open conversation. "
                + "Trusted commands run without asking.", arguments().string("scope", "global or conversation").string("folder", "the trusted folder")
                .string("trustedCommands", "the trusted commands as a JSON array of strings, e.g. [\"git status*\"]").required("scope").build(),
                args -> app.changeSettings(requiredText(args, "scope"), text(args, "folder"), commands(args))));
        tools.add(tool("memory_get", "The open conversation's summary of older messages and its facts.", noArguments(), args -> json(app.memory())));
        tools.add(tool("context_reset", "Forgets the open conversation's summary of older messages (the facts stay).", noArguments(), args -> app.resetContext()));
        tools.add(tool("log_tail", "The last lines of the application's readable log (kind agent, default) or of today's span file (kind trace).",
                arguments().string("kind", "agent or trace").integer("lines", "how many lines, default 80, at most 400").build(),
                args -> json(app.log(text(args, "kind"), Math.max(1, Math.min(400, integer(args, "lines", 80)))))));
        tools.add(tool("skills_list", "The loaded skills and the files with errors.", noArguments(), args -> json(app.skills())));
        return tools;
    }

    private static McpTool tool(String name, String description, ObjectNode schema, java.util.function.Function<JsonNode, String> run) {
        return new McpTool(name, description, schema, run);
    }

    private static String json(JsonNode node) {
        try {
            return JSON.writeValueAsString(node);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The trusted commands argument: a JSON array of strings (or an array node), null when it is not given. */
    private static List<String> commands(JsonNode args) {
        JsonNode value = args.get("trustedCommands");
        if (value == null || value.isNull()) return null;
        try {
            ArrayNode array = value.isArray() ? (ArrayNode) value : (ArrayNode) JSON.readTree(value.asText());
            List<String> commands = new ArrayList<>();
            for (JsonNode item : array) commands.add(item.asText());
            return commands;
        } catch (Exception e) {
            throw new IllegalArgumentException("`trustedCommands` must be a JSON array of strings.");
        }
    }
}
