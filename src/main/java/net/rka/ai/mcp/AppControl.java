package net.rka.ai.mcp;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * What the MCP server can do with the running application: the window's conversations, the turn in progress, the calls to the model
 * (the traffic sidebar), the questions that wait for the person, the settings and memory, and the logs. The implementation is the window
 * ({@code ChatControl}); the tools ({@link ControlTools}) only translate between MCP and this interface, so they are tested with a fake.
 *
 * <p>Every method may be called from any thread. A refusal or a mistake of the caller is an {@link IllegalArgumentException} with a
 * message the caller can act on; it becomes the tool's error.
 */
public interface AppControl {

    // ---- the application and its conversations ----

    /** The model, whether a turn runs, the open conversation, what waits for the person, the hold boxes of the traffic. */
    JsonNode status();

    /** The saved conversations, newest first: id, title, folder, updated. */
    JsonNode conversations();

    /** A conversation (the open one when {@code id} is null): title, folder, its messages in order, its memory. */
    JsonNode conversation(String id);

    /** Starts a new conversation and opens it; returns its id. */
    String newConversation();

    String openConversation(String id);

    String renameConversation(String id, String title);

    /** Deletes a conversation (and its traffic) without asking the person: the caller is trusted to have meant it. */
    String deleteConversation(String id);

    // ---- the turn ----

    /** Types {@code text} into the open conversation as if the person had, and sends it; refused while a turn runs. */
    String send(String text);

    /** Stops the turn in progress, like the Stop button. */
    String stop();

    /**
     * Waits until the turn is over, a question waits for an answer, or a call of the model is held, but at most {@code seconds};
     * returns the status then, with {@code waitedFor} saying which of these ended the wait.
     */
    JsonNode await(int seconds);

    // ---- the calls to the model ----

    /** The calls of a conversation (the open one when null): number and summary line. */
    JsonNode traffic(String conversation);

    /** One call: the settings, the whole request as text, the tools and the whole answer as text. */
    JsonNode trafficCall(String conversation, int n);

    /** The call that is held, if any: whether it is a request or an answer, and its text, which may be edited and released. */
    JsonNode held();

    /** Lets the held call go on: the request is sent or the answer is used, as it is or as {@code editedText}. */
    String release(String editedText);

    /** Cancels the held call: it fails like a call the server refused. */
    String cancelHeld();

    /** Sets (when given) and returns the two hold boxes. */
    JsonNode holds(Boolean requests, Boolean answers);

    // ---- the questions for the person ----

    /** The questions that wait: approvals, plans, questions, skill reviews. */
    JsonNode approvals();

    /** Answers a waiting question instead of the person. */
    String answer(String id, JsonNode arguments);

    // ---- settings, memory, logs, skills ----

    JsonNode settings();

    /** Changes the trusted folder and/or the trusted commands of the global ({@code scope} "global") or the open conversation's settings. */
    String changeSettings(String scope, String folder, java.util.List<String> trustedCommands);

    JsonNode memory();

    /** Forgets the open conversation's summary of older messages (the facts stay), like the Context button's reset. */
    String resetContext();

    /** The last {@code lines} lines of the readable agent log of today ({@code kind} "agent") or of the span file ("trace"). */
    JsonNode log(String kind, int lines);

    JsonNode skills();
}
