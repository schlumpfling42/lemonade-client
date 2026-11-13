package net.rka.ai.eval;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Runs the checks of a case on what the run left: the project folder and the agent's final answer. Checks decide; nothing is judged by a model. */
public final class EvalChecks {

    /** @param detail why a check failed (or what it saw), for the report */
    public record Result(EvalCase.Check check, boolean ok, String detail) {
    }

    /** The longest a {@code command} check may run. */
    static final int COMMAND_SECONDS = 180;

    private EvalChecks() {
    }

    /**
     * @param project the folder the agent worked in
     * @param fixture the untouched fixture, for {@code fileUnchanged}; may be null
     * @param answer  the agent's final answer ("" when there was none)
     */
    public static List<Result> run(List<EvalCase.Check> checks, Path project, Path fixture, String answer) {
        List<Result> results = new ArrayList<>();
        for (EvalCase.Check check : checks) {
            try {
                results.add(one(check, project, fixture, answer));
            } catch (IOException | RuntimeException | InterruptedException e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                results.add(new Result(check, false, e.getClass().getSimpleName() + ": " + e.getMessage()));
            }
        }
        return results;
    }

    private static Result one(EvalCase.Check check, Path project, Path fixture, String answer) throws IOException, InterruptedException {
        String type = check.type() == null ? "" : check.type();
        switch (type) {
            case "fileExists": {
                boolean there = Files.isRegularFile(project.resolve(check.path()));
                return new Result(check, there, there ? "" : "no such file");
            }
            case "fileContains":
            case "fileNotContains": {
                Path file = project.resolve(check.path());
                if (!Files.isRegularFile(file)) return new Result(check, type.equals("fileNotContains"), "no such file");
                boolean found = matches(Files.readString(file, StandardCharsets.UTF_8), check);
                boolean ok = type.equals("fileContains") == found;
                return new Result(check, ok, ok ? "" : (found ? "it is there" : "it is not there"));
            }
            case "fileUnchanged": {
                Path now = project.resolve(check.path());
                Path before = fixture == null ? null : fixture.resolve(check.path());
                boolean same = before != null && Files.isRegularFile(now) && Files.isRegularFile(before)
                        && Files.readString(now).equals(Files.readString(before));
                return new Result(check, same, same ? "" : "the file differs from the fixture");
            }
            case "answerContains": {
                boolean ok = answer.toLowerCase().contains(check.text().toLowerCase());
                return new Result(check, ok, ok ? "" : "the answer does not say it");
            }
            case "answerMatches": {
                boolean ok = Pattern.compile(check.regex(), Pattern.DOTALL | Pattern.CASE_INSENSITIVE).matcher(answer).find();
                return new Result(check, ok, ok ? "" : "the answer does not match");
            }
            case "command":
                return command(check, project);
            default:
                return new Result(check, false, "unknown check type '" + type + "'");
        }
    }

    private static boolean matches(String text, EvalCase.Check check) {
        if (check.text() != null) return text.contains(check.text());
        return Pattern.compile(check.regex(), Pattern.MULTILINE | Pattern.DOTALL).matcher(text).find();
    }

    private static Result command(EvalCase.Check check, Path project) throws IOException, InterruptedException {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        ProcessBuilder builder = new ProcessBuilder(windows ? List.of("cmd", "/c", check.command()) : List.of("sh", "-c", check.command()))
                .directory(project.toFile()).redirectErrorStream(true);
        Process process = builder.start();
        // Read on a thread: a command that prints a lot must not block on a full pipe while we wait for it
        StringBuilder output = new StringBuilder();
        Thread reader = new Thread(() -> {
            try {
                output.append(new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException e) {
                output.append("(output unreadable: ").append(e.getMessage()).append(')');
            }
        });
        reader.start();
        if (!process.waitFor(COMMAND_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            return new Result(check, false, "timed out after " + COMMAND_SECONDS + " s");
        }
        reader.join(5_000);
        int expected = check.exit() == null ? 0 : check.exit();
        String text = output.toString();
        if (process.exitValue() != expected) {
            return new Result(check, false, "exit code " + process.exitValue() + " (wanted " + expected + "): " + clip(text));
        }
        if (check.outputContains() != null && !text.contains(check.outputContains())) {
            return new Result(check, false, "the output does not contain '" + check.outputContains() + "': " + clip(text));
        }
        return new Result(check, true, "");
    }

    private static String clip(String text) {
        String flat = text.strip().replaceAll("\\s+", " ");
        return flat.length() > 300 ? flat.substring(0, 300) + "…" : flat;
    }
}
