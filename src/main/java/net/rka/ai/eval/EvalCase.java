package net.rka.ai.eval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * One evaluation: a small project (the fixture) is copied to a fresh folder, the prompt is sent to the agent as the user's message,
 * and the checks are run on what is left. A case file is JSON in {@code evals/cases}:
 * <pre>{@code
 * { "id": "add-method", "description": "...", "fixture": "calc", "prompt": "Add a subtract method to Calc",
 *   "timeoutMinutes": 15, "expectCodingJob": true,
 *   "checks": [ {"type": "fileContains", "path": "src/Calc.java", "text": "subtract"},
 *               {"type": "command", "command": "javac -d out src/*.java"} ] }
 * }</pre>
 * Check types: {@code fileExists}, {@code fileContains} ({@code text} or {@code regex}), {@code fileNotContains},
 * {@code fileUnchanged} (equal to the fixture's file), {@code command} (run in the project folder; passes when the exit code is
 * {@code exit}, default 0, and the output contains {@code outputContains} when given), {@code answerContains} and
 * {@code answerMatches} (the agent's final answer).
 *
 * @param expectCodingJob true: the coding skill must have been started; false: it must not; null: not checked
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EvalCase(String id, String description, String fixture, String prompt, int timeoutMinutes, Boolean expectCodingJob,
                       List<Check> checks) {

    /** The minutes a case may take when the file does not say. */
    public static final int DEFAULT_TIMEOUT_MINUTES = 15;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Check(String type, String path, String text, String regex, String command, Integer exit, String outputContains) {

        /** A short description for the report. */
        public String describe() {
            return switch (type == null ? "" : type) {
                case "fileExists", "fileUnchanged" -> type + " " + path;
                case "fileContains", "fileNotContains" -> type + " " + path + " " + (text != null ? "'" + text + "'" : "/" + regex + "/");
                case "command" -> "command `" + command + "`" + (outputContains == null ? "" : " prints '" + outputContains + "'");
                case "answerContains" -> "answer contains '" + text + "'";
                case "answerMatches" -> "answer matches /" + regex + "/";
                default -> String.valueOf(type);
            };
        }
    }

    public EvalCase {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("a case needs an id");
        if (prompt == null || prompt.isBlank()) throw new IllegalArgumentException("case " + id + " needs a prompt");
        timeoutMinutes = timeoutMinutes <= 0 ? DEFAULT_TIMEOUT_MINUTES : timeoutMinutes;
        checks = checks == null ? List.of() : List.copyOf(checks);
        fixture = fixture == null ? "" : fixture;
        description = description == null ? "" : description;
    }

    private static final ObjectMapper JSON = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public static EvalCase parse(String json) throws IOException {
        return JSON.readValue(json, EvalCase.class);
    }

    public static EvalCase load(Path file) throws IOException {
        return JSON.readValue(file.toFile(), EvalCase.class);
    }
}
