package net.rka.ai.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * What the coding jobs of a conversation were and how they ended, from the jobs themselves: the goal of each {@code startCodingJob} call and
 * the first line of its report (the outcome the graph wrote from the job's state: "Done: every change is made and verified", "Stopped: …",
 * "Still failing after 3 verify attempts: …"). It is what the model is told about the jobs instead of what it once wrote about them: a
 * model's own account of a job is where an invented class or an invented failure got into a conversation and stayed.
 */
final class JobLedger {

    /** How many of the latest jobs are listed. */
    static final int MAX_JOBS = 4;
    private static final ObjectMapper JSON = new ObjectMapper();

    private JobLedger() {
    }

    /** The jobs as lines, oldest first, at most {@link #MAX_JOBS} of the latest; empty when no job ran. */
    static List<String> lines(List<ChatMessage> messages) {
        List<String> jobs = new ArrayList<>();
        for (ChatMessage message : messages) {
            if (!(message instanceof AiMessage ai)) continue;
            for (ToolExecutionRequest call : ai.toolExecutionRequests()) {
                if (!CodingGraph.START_TOOL.equals(call.name())) continue;
                String outcome = messages.stream().filter(m -> m instanceof ToolExecutionResultMessage r && r.id().equals(call.id()))
                        .map(m -> firstLine(((ToolExecutionResultMessage) m).text())).findFirst().orElse("no report (the job did not finish)");
                jobs.add("goal \"" + cut(goalOf(call), 200) + "\" → " + cut(outcome, 220));
            }
        }
        return jobs.size() <= MAX_JOBS ? jobs : jobs.subList(jobs.size() - MAX_JOBS, jobs.size());
    }

    /** The note for the end of a request, or "" when no job ran. */
    static String note(List<ChatMessage> messages) {
        List<String> lines = lines(messages);
        if (lines.isEmpty()) return "";
        StringBuilder note = new StringBuilder("Coding jobs in this conversation, oldest first (what they did is in their reports; this is how they ended):");
        for (int i = 0; i < lines.size(); i++) note.append("\n").append(i + 1).append(". ").append(lines.get(i));
        return note.toString();
    }

    private static String goalOf(ToolExecutionRequest call) {
        try {
            JsonNode arguments = JSON.readTree(call.arguments() == null ? "{}" : call.arguments());
            return arguments.path("goal").asText("");
        } catch (Exception e) {
            return "";
        }
    }

    private static String firstLine(String text) {
        return text.lines().filter(l -> !l.isBlank()).findFirst().orElse("").strip();
    }

    private static String cut(String text, int max) {
        String one = text.replaceAll("\\s+", " ").strip();
        return one.length() > max ? one.substring(0, max) + "…" : one;
    }
}
