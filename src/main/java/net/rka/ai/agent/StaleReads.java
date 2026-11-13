package net.rka.ai.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * Takes file text out of the conversation once it is no longer worth its place: a result of a read that the conversation has since
 * read again (the same file, section, member or search) or has changed (an edit, a patch, a write of the same file or type) is
 * replaced by a one-line note. Only what is left is a true picture of the files, and a long turn does not carry the same
 * 3,000 tokens three times.
 * <p>
 * Why not rewrite everything as soon as the model has used it: a local model server keeps the start of the prompt in its cache, and
 * changing an early message makes it read everything after it again. A result is replaced only when a later message makes it wrong or
 * double, which happens once, and from then on every request has the same text, so the cache is valid again. Small results stay as they
 * are: shortening them saves nothing.
 */
public final class StaleReads {

    /** Tools whose result is file text or a picture of a file. */
    private static final Set<String> READS = Set.of("readFile", "readSection", "outline", "searchFiles", "javaRead", "javaReadBlock",
            "javaBlocks", "javaOutline");
    /** Tools that change a file, named by {@code path} or by the type's name ({@code type}). */
    private static final Set<String> CHANGES = Set.of("editSection", "writeFile", "patchFile", "javaAdd", "javaReplace", "javaDelete",
            "javaReplaceBlock", "javaInsertBlock", "javaDeleteBlock", "javaPatch", "javaImport", "javaCreate");
    /** A result shorter than this is left alone. */
    static final int MIN_CHARS = 1500;

    private static final ObjectMapper JSON = new ObjectMapper();

    private StaleReads() {
    }

    /** The messages with the results that are double or outdated replaced; the same list when there are none. */
    public static List<ChatMessage> apply(List<ChatMessage> messages) {
        // the call behind each result
        Map<String, ToolExecutionRequest> calls = new HashMap<>();
        for (ChatMessage message : messages) {
            if (message instanceof AiMessage ai) ai.toolExecutionRequests().forEach(call -> calls.put(call.id(), call));
        }
        // from the end: the reads seen so far (a later one makes an earlier equal one double) and the changes seen so far
        Set<String> laterReads = new java.util.HashSet<>();
        Set<String> laterChanges = new java.util.HashSet<>();
        Map<Integer, String> replaced = new HashMap<>();
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (!(messages.get(i) instanceof ToolExecutionResultMessage result)) continue;
            ToolExecutionRequest call = calls.get(result.id());
            if (call == null) continue;
            if (CHANGES.contains(call.name())) {
                laterChanges.addAll(subjects(call));
            } else if (READS.contains(call.name())) {
                String key = call.name() + " " + normalized(call.arguments());
                if (result.text().length() >= MIN_CHARS && !Boolean.TRUE.equals(result.isError())) {
                    if (laterReads.contains(key)) {
                        replaced.put(i, "this result was replaced: the same " + call.name() + " was done again below");
                    } else if (subjects(call).stream().anyMatch(laterChanges::contains)) {
                        replaced.put(i, "this result is out of date: the file was changed after it was read");
                    }
                }
                if (!Boolean.TRUE.equals(result.isError())) laterReads.add(key); // a read that failed does not replace the one before it
            }
        }
        if (replaced.isEmpty()) return messages;
        List<ChatMessage> out = new ArrayList<>(messages);
        replaced.forEach((index, why) -> {
            ToolExecutionResultMessage result = (ToolExecutionResultMessage) messages.get(index);
            out.set(index, ToolExecutionResultMessage.builder().id(result.id()).toolName(result.toolName())
                    .text("[" + why + "; " + result.text().length() + " characters; call " + result.toolName() + " again if you need it]")
                    .isError(result.isError()).build());
        });
        return out;
    }

    /** What a call is about: the file path, and the type's name for the Java tools. */
    private static Set<String> subjects(ToolExecutionRequest call) {
        Set<String> subjects = new java.util.HashSet<>();
        try {
            JsonNode arguments = JSON.readTree(call.arguments() == null ? "{}" : call.arguments());
            if (arguments.hasNonNull("path")) subjects.add("path:" + arguments.get("path").asText());
            if (arguments.hasNonNull("type")) subjects.add("type:" + arguments.get("type").asText());
            if (arguments.hasNonNull("target")) { // javaOutline: a type or a path
                String target = arguments.get("target").asText();
                subjects.add(target.endsWith(".java") ? "path:" + target : "type:" + target);
            }
        } catch (Exception e) {
            // arguments that are not JSON: no subject
        }
        return subjects;
    }

    private static String normalized(String arguments) {
        try {
            return JSON.writeValueAsString(JSON.readTree(arguments == null ? "{}" : arguments)); // the same arguments in the same form
        } catch (Exception e) {
            return String.valueOf(arguments);
        }
    }
}
