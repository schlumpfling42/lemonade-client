package net.rka.ai.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import net.rka.ai.tools.WorkFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Notices that the agent is about to do coding work by hand and turns it into a coding job, so the user
 * never has to say "that's a coding job".
 *
 * <h2>How it works</h2>
 * The model decides whether a request is coding work by calling {@code startCodingJob}, and a local model
 * often doesn't: it reads a file and starts rewriting it (minutes of generation, often an edit that doesn't
 * even apply). The first sign that it is happening is a tool call that changes an existing source file.
 * {@link #startCall} looks at the model's reply for exactly that, and when it finds it returns the
 * {@code startCodingJob} call that replaces the reply's calls ({@link AgentGraph#callModel}), with the user's
 * request as the goal. From there the normal graph runs: the router sends the call to the {@code skill-coding}
 * subgraph, the job finds the code, plans (the user approves), makes the changes, verifies, and answers the call.
 * <p>
 * What counts as coding work: a {@code writeFile} or {@code editSection} of an existing file
 * with a source or build file type ({@link #CODE_EXTENSIONS}, {@link #BUILD_FILES}) when it is more than a tiny
 * change ({@value #TINY_CHARS} characters of new text) or when it is the turn's second such change. Notes,
 * text and Markdown files, new files and tiny edits stay with the direct tools, as the tool's description says.
 * Reading code is never redirected: answering a question about the code needs no job.
 */
final class CodingRedirect {

    /** New text up to this length, in one change of one file, is a quick fix the agent makes itself. */
    static final int TINY_CHARS = 200;
    /** A user message shorter than this ("implement it") is a follow-up: the goal includes the messages before it. */
    /** A message shorter than this does not say what it is about by itself ("fix the buttons", "implement the changes"). */
    static final int SHORT_REQUEST_CHARS = 60;
    private static final int MAX_GOAL_MESSAGES = 3;
    private static final int MAX_GOAL_MESSAGE_CHARS = 400;
    private static final int MAX_REPORT_CHARS = 900;
    /** A recommendation or a plan of the assistant is the thing a short message points at: it is kept longer than a job report. */
    private static final int MAX_ANSWER_CHARS = 3000;
    /** What the nearest earlier work says is too little to know the task from (the older task is then added). */
    private static final int SHORT_EARLIER_CHARS = 200;
    private static final int MAX_EARLIER_ITEMS = 3;

    /** Tools that change code (the Java tools name a type, the others a path): by hand they are a job's work. */
    private static final Set<String> CHANGING_TOOLS = Set.of("writeFile", "editSection", "patchFile", "javaAdd", "javaReplace", "javaDelete",
            "javaReplaceBlock", "javaInsertBlock", "javaDeleteBlock", "javaPatch", "javaImport");
    private static final Set<String> JAVA_TOOLS = Set.of("javaAdd", "javaReplace", "javaDelete", "javaReplaceBlock", "javaInsertBlock",
            "javaDeleteBlock", "javaPatch", "javaImport");
    private static final Set<String> CODE_EXTENSIONS = Set.of("java", "kt", "kts", "scala", "groovy", "gradle",
            "js", "jsx", "mjs", "cjs", "ts", "tsx", "svelte", "vue", "html", "css", "scss", "py", "rb", "go", "rs",
            "c", "h", "cc", "cpp", "hpp", "cs", "swift", "php", "sql", "sh", "ps1", "bat");
    private static final Set<String> BUILD_FILES = Set.of("pom.xml", "package.json", "cargo.toml", "go.mod",
            "requirements.txt", "pyproject.toml", "build.gradle", "build.gradle.kts", "settings.gradle");
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * @param messages the conversation so far (the user's request and this turn's earlier calls)
     * @param reply    the model's reply
     * @param folder   the conversation's trusted folder
     * @return the {@code startCodingJob} call to use instead of the reply's calls, or empty to leave the reply alone
     */
    Optional<ToolExecutionRequest> startCall(List<ChatMessage> messages, AiMessage reply, Path folder) {
        if (!reply.hasToolExecutionRequests()) return Optional.empty();
        if (reply.toolExecutionRequests().stream().anyMatch(c -> CodingGraph.START_TOOL.equals(c.name()))) return Optional.empty();
        List<ToolExecutionRequest> changes = reply.toolExecutionRequests().stream().filter(c -> changesCode(c, folder)).toList();
        if (changes.isEmpty()) return Optional.empty();

        // The turn's earlier changes: everything after the user's last message
        int from = 0;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof UserMessage) {
                from = i + 1;
                break;
            }
        }
        long earlier = messages.subList(from, messages.size()).stream()
                .filter(m -> m instanceof AiMessage)
                .flatMap(m -> ((AiMessage) m).toolExecutionRequests().stream())
                .filter(c -> changesCode(c, folder)).count();
        boolean big = changes.stream().anyMatch(c -> newText(c).length() > TINY_CHARS);
        if (!big && changes.size() + earlier < 2) return Optional.empty();

        String goal = goal(messages, from);
        if (goal.isBlank()) return Optional.empty();
        return Optional.of(startCall(goal));
    }

    // A source or build file's name in the text: "ChatWindow.java", "src/app.py", "package.json"
    private static final java.util.regex.Pattern CODE_FILE_MENTION = java.util.regex.Pattern.compile(
            "(?i)[\\w$./\\\\-]*(?:\\.(?:" + String.join("|", CODE_EXTENSIONS) + ")|(?<![\\w.-])(?:"
                    + String.join("|", BUILD_FILES).replace(".", "\\.") + "))(?![\\w])");

    /**
     * The {@code startCodingJob} call for a reply that says what it will do to a source file and stops
     * ("Let me make these changes to ChatWindow.java:"), or empty when the text names no source file. The model
     * promised the very work a job does, so the job does it, with the user's message as the goal, instead of the
     * conversation waiting on a call the model doesn't make.
     *
     * @param messages the conversation so far (the user's request is in it)
     * @param reply    a plain answer that announces an action (see {@link AgentGraph#announcesWithoutActing})
     */
    Optional<ToolExecutionRequest> startCallForAnnouncement(List<ChatMessage> messages, AiMessage reply) {
        if (reply.text() == null || !CODE_FILE_MENTION.matcher(reply.text()).find()) return Optional.empty();
        String goal = goal(messages, messages.size());
        if (goal.isBlank()) return Optional.empty();
        return Optional.of(startCall(goal));
    }

    /**
     * The {@code startCodingJob} call for the user's latest message ({@link CodingTriage} said it is coding work),
     * or empty when the conversation has no user message with text.
     */
    Optional<ToolExecutionRequest> startCallForRequest(List<ChatMessage> messages, boolean continuing) {
        return startCallForRequest(messages, continuing, "");
    }

    /**
     * @param objective the task the user is working on in their own words ({@link Objective}), "" when there is none: a message that
     *                  points back at earlier work says what it is about through it
     */
    Optional<ToolExecutionRequest> startCallForRequest(List<ChatMessage> messages, boolean continuing, String objective) {
        // A message that says what to do by itself is the whole goal: earlier requests would pull the job back to them
        String goal;
        if (!continuing) {
            goal = latestRequest(messages);
        } else {
            // "implement that" points at what was said or done last, not at the conversation's first request: the nearest earlier answer or
            // job comes first. The task kept as a fact is older and is only added when that is too little to know what it is about.
            String earlier = earlierWork(messages, lastUserMessage(messages));
            if (!earlier.isBlank()) {
                goal = "The user's latest message is: \"" + latestRequest(messages) + "\". Do what it asks, and only that. It points back at what was"
                        + " said or done just before, which is background that says what it is about." + earlier
                        + (earlier.length() < SHORT_EARLIER_CHARS && !objective.isBlank()
                        ? " The task of this conversation (older), in the user's words: \"" + objective + "\"." : "");
            } else if (!objective.isBlank()) {
                goal = "The user's latest message is: \"" + latestRequest(messages) + "\". Do what it asks, and only that. It points back at the task"
                        + " they are working on, in their words: \"" + objective + "\".";
            } else {
                goal = goal(messages, messages.size());
            }
        }
        return goal.isBlank() ? Optional.empty() : Optional.of(startCall(goal));
    }

    /** The call that starts a coding job with this goal. */
    ToolExecutionRequest startCallForGoal(String goal) {
        return startCall(goal);
    }

    private static ToolExecutionRequest startCall(String goal) {
        return ToolExecutionRequest.builder().id("call_" + UUID.randomUUID().toString().replace("-", ""))
                .name(CodingGraph.START_TOOL).arguments(JSON.createObjectNode().put("goal", goal).toString()).build();
    }

    private static boolean changesCode(ToolExecutionRequest call, Path folder) {
        if (!CHANGING_TOOLS.contains(call.name())) return false;
        JsonNode arguments = arguments(call);
        if (arguments == null) return false;
        if (JAVA_TOOLS.contains(call.name())) return !arguments.path("type").asText("").isBlank(); // a type of the project: existing code
        String path = arguments.path("path").asText("");
        if (path.isBlank()) return false;
        String name = path.substring(Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\')) + 1).toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        if (!BUILD_FILES.contains(name) && (dot < 0 || !CODE_EXTENSIONS.contains(name.substring(dot + 1)))) return false;
        try {
            return Files.isRegularFile(WorkFolder.resolveInside(folder, path)); // a new file isn't a change of existing code
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** The text a change writes: {@code newText} of an edit, {@code content} of a write. */
    private static String newText(ToolExecutionRequest call) {
        JsonNode arguments = arguments(call);
        if (arguments == null) return "";
        String field = switch (call.name()) {
            case "writeFile" -> "content";
            case "patchFile", "javaPatch" -> "patch";
            case "javaAdd", "javaReplace", "javaReplaceBlock", "javaInsertBlock" -> "code";
            default -> "newText";
        };
        return arguments.path(field).asText("");
    }

    private static JsonNode arguments(ToolExecutionRequest call) {
        try {
            return call.arguments() == null ? null : JSON.readTree(call.arguments());
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** The user's latest message with text, as it is: the goal when it needs nothing before it. */
    private static String latestRequest(List<ChatMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof UserMessage user) {
                String text = user.hasSingleText() ? user.singleText() : String.valueOf(user.contents());
                if (!text.isBlank()) return text.length() > MAX_GOAL_MESSAGE_CHARS * 4 ? text.substring(0, MAX_GOAL_MESSAGE_CHARS * 4) + "…" : text;
            }
        }
        return "";
    }

    /**
     * The goal of the job: the user message that started the turn. A short follow-up ("implement it", "yes, do
     * that") means nothing to a job, which sees only its goal, so the user messages before it come along.
     *
     * @param turnStart the index after the turn's user message
     */
    private static String goal(List<ChatMessage> messages, int turnStart) {
        List<String> requests = new java.util.ArrayList<>();
        int latest = -1; // the index of the user message the goal is for
        for (int i = turnStart - 1; i >= 0 && requests.size() < MAX_GOAL_MESSAGES; i--) {
            if (messages.get(i) instanceof UserMessage user) {
                if (latest < 0) latest = i;
                String text = user.hasSingleText() ? user.singleText() : String.valueOf(user.contents());
                if (!text.isBlank()) requests.add(text.length() > MAX_GOAL_MESSAGE_CHARS
                        ? text.substring(0, MAX_GOAL_MESSAGE_CHARS) + "…" : text);
                if (text.length() >= SHORT_REQUEST_CHARS) break; // a full request needs no earlier ones
            }
        }
        if (requests.isEmpty()) return "";
        if (requests.size() == 1 && requests.getFirst().length() >= SHORT_REQUEST_CHARS) return requests.get(0);
        java.util.Collections.reverse(requests);
        List<String> background = new java.util.ArrayList<>(requests.subList(0, requests.size() - 1));
        // A short message ("implement the changes") points at work, and the work is not in the user's other short messages: it is in
        // the goal and the report of the job before, or in what the assistant last said
        String earlier = earlierWork(messages, Math.max(latest, 0));
        if (background.isEmpty() && earlier.isEmpty()) return requests.getLast();
        return "The user's latest message is: \"" + requests.getLast() + "\". Do what it asks, and only that. It points back at earlier work,"
                + " which is background that says what it is about."
                + (background.isEmpty() ? "" : " The user's earlier messages (oldest first): " + String.join(" | ", background) + ".")
                + earlier;
    }

    /** The index of the user's latest message, or the size of the list when there is none. */
    private static int lastUserMessage(List<ChatMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) if (messages.get(i) instanceof UserMessage) return i;
        return messages.size();
    }

    /**
     * What happened just before the message at {@code before}: the nearest earlier thing, going backwards, that is either an answer of the
     * assistant (a recommendation, a status, a plan) or a coding job (its goal and report). The nearest one, not the first one of the
     * conversation: a recommendation given after a job is what "implement that" means.
     */
    private static String earlierWork(List<ChatMessage> messages, int before) {
        StringBuilder work = new StringBuilder();
        int found = 0;
        // Going backwards: the nearest answer or job first, and while what was found is too little to tell what it is about ("OK.", "What do
        // you mean?"), the one before it, at most three in all
        for (int i = Math.min(before, messages.size()) - 1; i >= 0 && found < MAX_EARLIER_ITEMS && work.length() < SHORT_EARLIER_CHARS; i--) {
            if (!(messages.get(i) instanceof AiMessage ai)) continue;
            boolean isJob = false;
            for (ToolExecutionRequest call : ai.toolExecutionRequests()) {
                if (!CodingGraph.START_TOOL.equals(call.name())) continue;
                JsonNode arguments = arguments(call);
                String goal = arguments == null ? "" : arguments.path("goal").asText("");
                String report = messages.stream().filter(m -> m instanceof ToolExecutionResultMessage r && r.id().equals(call.id()))
                        .map(m -> ((ToolExecutionResultMessage) m).text()).findFirst().orElse("");
                work.append(" Before that a job had the goal: \"").append(cut(goal, MAX_GOAL_MESSAGE_CHARS)).append("\".")
                        .append(report.isBlank() ? "" : " Its report: \"" + cut(report, MAX_REPORT_CHARS) + "\".");
                found++;
                isJob = true;
                break;
            }
            if (!isJob && !ai.hasToolExecutionRequests() && ai.text() != null && !ai.text().isBlank()) {
                work.append(found == 0 ? " What the assistant said last: \"" : " Before that the assistant said: \"")
                        .append(cut(ai.text(), MAX_ANSWER_CHARS)).append("\".");
                found++;
            }
        }
        return work.toString();
    }

    private static String cut(String text, int max) {
        String one = text.replaceAll("\\s+", " ").strip();
        return one.length() > max ? one.substring(0, max) + "…" : one;
    }
}
