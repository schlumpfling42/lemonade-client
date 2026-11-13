package net.rka.ai.agent;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Keeps the conversation of a tool loop short: the results of reads from earlier rounds shrink to one line, the last
 * {@link #KEEP_ROUNDS} rounds stay whole. A read is cheap to do again (the file is on disk), but every character left in the conversation
 * is read again by the model on every later request, so a loop of thirty rounds ended up sending 130,000 characters.
 * <p>
 * Only reads shrink ({@link #READS}); the output of a command or an edit's answer is not something that can be fetched again. Short
 * results stay. Like {@link ContextGroom}, this changes what the model is sent, not what is stored, and the same messages always give
 * the same text, so the model server's cache stays valid between requests. {@link ToolCallRunner#withoutRepeats} is given the same view,
 * so a read the model repeats because its result is gone is run and answered, not called a repeat.
 */
public final class RollingResults {

    /** How many of the last rounds of tool calls keep their results whole. */
    public static final int KEEP_ROUNDS = 4;
    /** A result up to this long stays whole: a line is not worth a note. */
    static final int SHORT = 600;

    /** The tools whose results can be fetched again. */
    static final Set<String> READS = Set.of("readFile", "readSection", "outline", "listFiles", "findFiles", "searchFiles", "findParts",
            "javaRead", "javaOutline", "javaGraph", "javaFind", "javaUsages", "javaProject");

    /** The results that stay whole while they are no more than this many characters together (about 15,000 tokens). */
    static final int MAX_HELD_CHARS = 60_000;

    /** The start of a shortened result that stays in the conversation, so the model remembers what it was about. */
    static final int DIGEST = 400;

    private RollingResults() {
    }

    private static String digest(String text) {
        return text.length() <= DIGEST ? text : text.substring(0, DIGEST);
    }

    /** The messages as the model should see them. */
    public static List<ChatMessage> apply(List<ChatMessage> messages) {
        // The calls by id, and the ids of the last rounds
        Map<String, ToolExecutionRequest> calls = new HashMap<>();
        List<List<String>> rounds = new ArrayList<>();
        for (ChatMessage message : messages) {
            if (message instanceof AiMessage ai && ai.hasToolExecutionRequests()) {
                List<String> ids = new ArrayList<>();
                for (ToolExecutionRequest call : ai.toolExecutionRequests()) {
                    calls.put(call.id(), call);
                    ids.add(call.id());
                }
                rounds.add(ids);
            }
        }
        if (rounds.size() <= KEEP_ROUNDS) return messages;
        // Changing an old message makes the model server read everything after it again (about 20 s at 5,000 tokens): only when the
        // results really are too many for the context is that worth it
        int held = 0;
        for (ChatMessage message : messages) if (message instanceof ToolExecutionResultMessage result && result.text() != null) held += result.text().length();
        if (held <= MAX_HELD_CHARS) return messages;
        Set<String> recent = new java.util.HashSet<>();
        rounds.subList(rounds.size() - KEEP_ROUNDS, rounds.size()).forEach(recent::addAll);

        List<ChatMessage> out = new ArrayList<>(messages.size());
        for (ChatMessage message : messages) {
            if (message instanceof ToolExecutionResultMessage result && !recent.contains(result.id())
                    && READS.contains(result.toolName()) && result.text() != null && result.text().length() > SHORT
                    && !Boolean.TRUE.equals(result.isError())) {
                ToolExecutionRequest call = calls.get(result.id());
                String arguments = call == null || call.arguments() == null ? "" : call.arguments().replaceAll("\\s+", " ");
                if (arguments.length() > 120) arguments = arguments.substring(0, 120) + "…";
                out.add(ToolExecutionResultMessage.builder().id(result.id()).toolName(result.toolName())
                        .text("(" + result.toolName() + " " + arguments + " returned " + result.text().length()
                                + " characters, shortened to keep the conversation short. It began:\n" + digest(result.text())
                                + "\n[...] Call it again if you need the rest.)").build());
            } else {
                out.add(message);
            }
        }
        return out;
    }
}
